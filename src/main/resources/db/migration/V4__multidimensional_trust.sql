-- V4__multidimensional_trust.sql

-- 1. Table for Multidimensional Behavioral Trust Snapshots with Beta-Binomial parameters
CREATE TABLE IF NOT EXISTS user_trust_snapshot (
    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    tenant_id VARCHAR(100) NOT NULL,
    
    -- Spam dimension
    spam_alpha DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    spam_beta DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    spam_last_penalty_at TIMESTAMPTZ,
    
    -- Harassment dimension
    harassment_alpha DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    harassment_beta DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    harassment_last_penalty_at TIMESTAMPTZ,
    
    -- Coordination dimension
    coordination_alpha DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    coordination_beta DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    coordination_last_penalty_at TIMESTAMPTZ,
    
    -- General abuse dimension
    general_alpha DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    general_beta DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    general_last_penalty_at TIMESTAMPTZ,
    
    decay_rate DOUBLE PRECISION NOT NULL DEFAULT 0.0000005,
    last_active_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_trust_tenant ON user_trust_snapshot(tenant_id);

-- 2. Extend trust_event with event_type (INFRACTION vs REWARD) and evidence weight
ALTER TABLE trust_event ADD COLUMN IF NOT EXISTS event_type VARCHAR(32) DEFAULT 'INFRACTION';
ALTER TABLE trust_event ADD COLUMN IF NOT EXISTS evidence_weight DOUBLE PRECISION DEFAULT 1.0;

-- 3. Backfill snapshots for existing users
INSERT INTO user_trust_snapshot (user_id, tenant_id)
SELECT user_id, tenant_id FROM users
ON CONFLICT (user_id) DO NOTHING;

-- 4. Analytical view for multidimensional standing
CREATE OR REPLACE VIEW vw_user_trust_summary AS
SELECT 
    u.user_id,
    u.tenant_id,
    u.username,
    s.spam_alpha,
    s.spam_beta,
    (s.spam_alpha / (s.spam_alpha + GREATEST(0.001, s.spam_beta))) AS spam_score,
    (1.0 - 1.0 / SQRT(s.spam_alpha + s.spam_beta + 1.0)) AS spam_confidence,
    s.harassment_alpha,
    s.harassment_beta,
    (s.harassment_alpha / (s.harassment_alpha + GREATEST(0.001, s.harassment_beta))) AS harassment_score,
    (1.0 - 1.0 / SQRT(s.harassment_alpha + s.harassment_beta + 1.0)) AS harassment_confidence,
    s.coordination_alpha,
    s.coordination_beta,
    (s.coordination_alpha / (s.coordination_alpha + GREATEST(0.001, s.coordination_beta))) AS coordination_score,
    (1.0 - 1.0 / SQRT(s.coordination_alpha + s.coordination_beta + 1.0)) AS coordination_confidence,
    s.general_alpha,
    s.general_beta,
    (s.general_alpha / (s.general_alpha + GREATEST(0.001, s.general_beta))) AS general_score,
    (1.0 - 1.0 / SQRT(s.general_alpha + s.general_beta + 1.0)) AS general_confidence,
    s.last_active_at,
    s.updated_at
FROM users u
LEFT JOIN user_trust_snapshot s ON u.user_id = s.user_id;
