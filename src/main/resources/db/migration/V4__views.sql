-- V4__views.sql

-- vw_flagged_content: Consolidates all active flags.
CREATE OR REPLACE VIEW vw_flagged_content AS
SELECT 
    f.flag_id,
    c.tenant_id,
    c.content_id,
    c.content_text,
    u.username AS author_name,
    f.flag_type,
    f.reason,
    f.status,
    f.report_count,
    f.flagged_at
FROM content_flag f
JOIN content c ON f.content_id = c.content_id
JOIN users u ON c.user_id = u.user_id
WHERE f.status != 'RESOLVED';

-- vw_top_users: Lists highly trusted users (Trust Score > 80)
CREATE OR REPLACE VIEW vw_top_users AS
SELECT 
    u.user_id,
    u.tenant_id,
    u.username,
    t.trust_score,
    t.violation_count,
    t.accurate_reports
FROM users u
JOIN trust_score t ON u.user_id = t.user_id
WHERE t.trust_score > 80.0
ORDER BY t.trust_score DESC;

-- vw_audit_log: Formats the audit log for the dashboard
CREATE OR REPLACE VIEW vw_audit_log AS
SELECT 
    audit_id,
    action_type,
    action_details,
    reference_type,
    reference_id,
    actor_id,
    actor_type,
    action_timestamp
FROM audit_log
ORDER BY action_timestamp DESC;
