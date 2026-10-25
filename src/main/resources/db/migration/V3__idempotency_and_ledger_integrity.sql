-- V3__idempotency_and_ledger_integrity.sql

-- 1. Idempotency records for external retries
CREATE TABLE IF NOT EXISTS idempotency_record (
    tenant_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    content_id BIGINT,
    decision VARCHAR(32) NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (tenant_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_idempotency_created_at ON idempotency_record(created_at);

-- 2. Cryptographic hash chaining on moderation ledger
ALTER TABLE moderation_event ADD COLUMN IF NOT EXISTS prev_event_hash VARCHAR(64);
ALTER TABLE moderation_event ADD COLUMN IF NOT EXISTS event_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_moderation_event_tenant_created ON moderation_event(tenant_id, created_at DESC);

-- 3. Content redaction flag for data deletion compliance without breaking audit records
ALTER TABLE content ADD COLUMN IF NOT EXISTS is_redacted BOOLEAN DEFAULT FALSE;
