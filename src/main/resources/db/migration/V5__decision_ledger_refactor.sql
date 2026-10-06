-- V5__decision_ledger_refactor.sql

-- Drop the old triggers that contained hardcoded moderation logic
DROP TRIGGER IF EXISTS trg_check_rate_limits ON content;
DROP TRIGGER IF EXISTS trg_moderate_content ON content;

-- The decision ledger to track all moderation events in an immutable, explainable way
CREATE TABLE moderation_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(100) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    content_hash VARCHAR(64) NOT NULL,
    normalized_text TEXT,
    signals JSONB NOT NULL,
    policy_version INT NOT NULL,
    decision VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- BRIN index for fast temporal scans on the ledger
CREATE INDEX idx_moderation_event_created_at_brin ON moderation_event USING BRIN(created_at);
CREATE INDEX idx_moderation_event_user_id ON moderation_event(user_id);

-- Enforce immutability of the ledger
CREATE OR REPLACE FUNCTION prevent_ledger_tampering() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Moderation ledger is immutable. UPDATE or DELETE operations are forbidden.';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_moderation_event_immutable
BEFORE UPDATE OR DELETE ON moderation_event
FOR EACH ROW EXECUTE FUNCTION prevent_ledger_tampering();
