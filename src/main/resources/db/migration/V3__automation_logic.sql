-- V3__automation_logic.sql

-- ============================================================
-- 1. TEXT NORMALIZATION (Zalgo / Zero-Width defense)
-- ============================================================
CREATE OR REPLACE FUNCTION normalize_text(input_text TEXT) RETURNS TEXT AS $$
BEGIN
    -- 1. Remove zero-width characters (e.g., U+200B)
    -- 2. Basic diacritic stripping for Zalgo defense
    -- Note: PostgreSQL doesn't have a built-in NFKC normalizer without extensions like unaccent
    -- For this phase, we'll strip common invisible characters.
    RETURN regexp_replace(input_text, '[\u200B-\u200D\uFEFF]', '', 'g');
END;
$$ LANGUAGE plpgsql IMMUTABLE;


-- ============================================================
-- 2. RATE LIMITING (Burst & Sustained)
-- ============================================================
CREATE OR REPLACE FUNCTION check_rate_limits() RETURNS TRIGGER AS $$
DECLARE
    v_burst_limit INT;
    v_sustained_limit INT;
    v_burst_count INT;
    v_sustained_count INT;
BEGIN
    -- Fetch tenant thresholds
    SELECT burst_limit, sustained_limit INTO v_burst_limit, v_sustained_limit
    FROM tenant_config WHERE tenant_id = NEW.tenant_id;

    IF NOT FOUND THEN
        SELECT burst_limit, sustained_limit INTO v_burst_limit, v_sustained_limit
        FROM tenant_config WHERE tenant_id = 'GLOBAL';
    END IF;

    -- Check burst (last 1 minute)
    SELECT COUNT(*) INTO v_burst_count
    FROM content
    WHERE user_id = NEW.user_id AND created_at > NOW() - INTERVAL '1 minute';

    IF v_burst_count >= v_burst_limit THEN
        RAISE EXCEPTION 'Burst rate limit exceeded for user %', NEW.user_id;
    END IF;

    -- Check sustained (last 1 hour)
    SELECT COUNT(*) INTO v_sustained_count
    FROM content
    WHERE user_id = NEW.user_id AND created_at > NOW() - INTERVAL '1 hour';

    IF v_sustained_count >= v_sustained_limit THEN
        RAISE EXCEPTION 'Sustained rate limit exceeded for user %', NEW.user_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_check_rate_limits
BEFORE INSERT ON content
FOR EACH ROW EXECUTE FUNCTION check_rate_limits();


-- ============================================================
-- 3. CONTENT MODERATION TRIGGER (Exact, Fuzzy, Regex, Duplicates)
-- ============================================================
CREATE OR REPLACE FUNCTION moderate_content() RETURNS TRIGGER AS $$
DECLARE
    v_normalized_text TEXT;
    v_similarity_threshold DECIMAL;
    v_match_found BOOLEAN := FALSE;
    v_reason TEXT := '';
    v_duplicate_count INT;
BEGIN
    -- Normalize text
    v_normalized_text := normalize_text(NEW.content_text);

    -- Check duplicates (24 hours)
    SELECT COUNT(*) INTO v_duplicate_count
    FROM content
    WHERE content_hash = NEW.content_hash AND created_at > NOW() - INTERVAL '24 hours';
    
    IF v_duplicate_count > 0 THEN
        v_match_found := TRUE;
        v_reason := 'Duplicate content detected';
    END IF;

    -- Fetch tenant fuzzy match threshold
    SELECT trgm_similarity_threshold INTO v_similarity_threshold
    FROM tenant_config WHERE tenant_id = NEW.tenant_id;

    IF NOT FOUND THEN
        SELECT trgm_similarity_threshold INTO v_similarity_threshold
        FROM tenant_config WHERE tenant_id = 'GLOBAL';
    END IF;

    -- Exact Match / Regex Boundaries / Fuzzy Match against BANNED_WORD
    IF NOT v_match_found THEN
        PERFORM 1 FROM banned_word 
        WHERE (tenant_id = NEW.tenant_id OR tenant_id = 'GLOBAL')
          AND word_status = 'ACTIVE'
          AND (
              (match_type = 'EXACT_WORD_ONLY' AND v_normalized_text ~* ('\b' || keyword || '\b')) OR
              (match_type = 'SUBSTRING_ALLOWED' AND (
                  v_normalized_text ILIKE '%' || keyword || '%' OR 
                  similarity(v_normalized_text, keyword) > v_similarity_threshold
              ))
          ) LIMIT 1;
        
        IF FOUND THEN
            v_match_found := TRUE;
            v_reason := 'Banned keyword or fuzzy match detected';
        END IF;
    END IF;

    -- Regex PII/URL detect (Simple URL regex example)
    IF NOT v_match_found THEN
        IF v_normalized_text ~* 'https?:\/\/(www\.)?[-a-zA-Z0-9@:%._\+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_\+.~#?&//=]*)' THEN
            v_match_found := TRUE;
            v_reason := 'Suspicious URL detected';
        END IF;
    END IF;

    -- If flagged, we still insert the content, but we will write an outbox event.
    -- (The actual flag row creation will be handled by the outbox worker to decouple it,
    -- or we can insert the content_flag right here. PRD allows inserting the flag.)
    IF v_match_found THEN
        NEW.is_visible := FALSE; -- Hide pending review
        
        -- Insert into outbox_event for async processing (e.g., scoring impact, notifications)
        INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
        VALUES ('CONTENT', NEW.content_hash, 'CONTENT_FLAGGED', 
                jsonb_build_object('user_id', NEW.user_id, 'reason', v_reason, 'text', NEW.content_text));
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_moderate_content
BEFORE INSERT ON content
FOR EACH ROW EXECUTE FUNCTION moderate_content();


-- ============================================================
-- 4. TRUST SCORE RECALCULATION & SHADOW BANNING
-- ============================================================
CREATE OR REPLACE FUNCTION recalculate_trust_score(p_user_id BIGINT) RETURNS VOID AS $$
DECLARE
    v_tenant_id VARCHAR(100);
    v_violations INT;
    v_acc_reports INT;
    v_account_age_days INT;
    v_score DECIMAL;
    v_w_acc_reports DECIMAL;
    v_w_severity DECIMAL;
    v_w_acc_age DECIMAL;
    v_shadow_threshold INT;
    v_severity_sum INT := 0;
BEGIN
    SELECT tenant_id, EXTRACT(DAY FROM (NOW() - account_created_at)) INTO v_tenant_id, v_account_age_days
    FROM users WHERE user_id = p_user_id;

    -- Load config
    SELECT weight_accurate_reports, weight_severity, weight_account_age, shadow_ban_threshold
    INTO v_w_acc_reports, v_w_severity, v_w_acc_age, v_shadow_threshold
    FROM tenant_config WHERE tenant_id = v_tenant_id;

    IF NOT FOUND THEN
        SELECT weight_accurate_reports, weight_severity, weight_account_age, shadow_ban_threshold
        INTO v_w_acc_reports, v_w_severity, v_w_acc_age, v_shadow_threshold
        FROM tenant_config WHERE tenant_id = 'GLOBAL';
    END IF;

    -- Aggregate violations
    SELECT COUNT(*), COALESCE(SUM(severity_level), 0) INTO v_violations, v_severity_sum
    FROM violation WHERE user_id = p_user_id;

    -- Ensure trust_score record exists
    INSERT INTO trust_score (user_id, violation_count, accurate_reports)
    VALUES (p_user_id, v_violations, 0)
    ON CONFLICT (user_id) DO UPDATE SET violation_count = v_violations;

    SELECT accurate_reports INTO v_acc_reports FROM trust_score WHERE user_id = p_user_id;

    -- Formula
    v_score := (100 * (1 - (v_violations::DECIMAL / GREATEST(v_violations + 10, 1)))) + 
               ((v_account_age_days / 365.0) * v_w_acc_age) + 
               (v_acc_reports * v_w_acc_reports) - 
               (v_severity_sum * v_w_severity);

    -- Clamp score
    IF v_score > 100 THEN v_score := 100; END IF;
    IF v_score < 0 THEN v_score := 0; END IF;

    UPDATE trust_score SET trust_score = v_score, last_updated = NOW() WHERE user_id = p_user_id;

    -- Shadow ban check
    IF v_score < v_shadow_threshold THEN
        UPDATE users SET is_visible = FALSE WHERE user_id = p_user_id;
        
        -- Emit shadow ban event
        INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
        VALUES ('USER', p_user_id::VARCHAR, 'USER_SHADOW_BANNED', jsonb_build_object('trust_score', v_score));
    ELSE
        UPDATE users SET is_visible = TRUE WHERE user_id = p_user_id;
    END IF;
END;
$$ LANGUAGE plpgsql;


-- ============================================================
-- 5. AUDIT LOG IMMUTABILITY
-- ============================================================
CREATE OR REPLACE FUNCTION prevent_audit_tampering() RETURNS TRIGGER AS $$
BEGIN
    -- Only SUPERUSER can bypass this (or we completely lock it down)
    RAISE EXCEPTION 'Audit log is immutable. UPDATE or DELETE operations are forbidden.';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_audit_tampering();
