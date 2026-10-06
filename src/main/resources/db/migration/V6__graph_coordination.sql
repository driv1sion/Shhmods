-- V6__graph_coordination.sql

-- Materialized view to detect coordinated reporting behavior.
CREATE MATERIALIZED VIEW coordination_clusters AS
WITH recent_reports AS (
    SELECT r.reporter_id, c.user_id AS target_id
    FROM report r
    JOIN content c ON r.content_id = c.content_id
    WHERE r.reported_at > NOW() - INTERVAL '24 hours'
),
reporter_pairs AS (
    SELECT
        r1.reporter_id AS reporter_a,
        r2.reporter_id AS reporter_b,
        count(*) AS shared_targets
    FROM recent_reports r1
    JOIN recent_reports r2
      ON r1.target_id = r2.target_id
     AND r1.reporter_id < r2.reporter_id
    GROUP BY r1.reporter_id, r2.reporter_id
)
SELECT reporter_a, reporter_b, shared_targets
FROM reporter_pairs
WHERE shared_targets >= 3;

CREATE INDEX idx_coordination_reporter_a ON coordination_clusters(reporter_a);
CREATE INDEX idx_coordination_reporter_b ON coordination_clusters(reporter_b);

-- Materialized view to detect coordinated content posting (spam rings).
CREATE MATERIALIZED VIEW content_clusters AS
WITH recent_content AS (
    SELECT user_id, content_hash
    FROM content
    WHERE created_at > NOW() - INTERVAL '1 hour'
),
poster_pairs AS (
    SELECT
        c1.user_id AS poster_a,
        c2.user_id AS poster_b,
        count(*) AS shared_hashes
    FROM recent_content c1
    JOIN recent_content c2
      ON c1.content_hash = c2.content_hash
     AND c1.user_id < c2.user_id
    GROUP BY c1.user_id, c2.user_id
)
SELECT poster_a, poster_b, shared_hashes
FROM poster_pairs
WHERE shared_hashes >= 2;

CREATE INDEX idx_content_poster_a ON content_clusters(poster_a);
CREATE INDEX idx_content_poster_b ON content_clusters(poster_b);
