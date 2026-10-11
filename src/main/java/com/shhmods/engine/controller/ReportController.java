package com.shhmods.engine.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/report")
public class ReportController {

    private final JdbcTemplate jdbcTemplate;

    public ReportController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping
    public Map<String, String> reportContent(@RequestBody Map<String, Object> payload) {
        Object rawContentId = payload.containsKey("content_id") ? payload.get("content_id") : payload.get("contentId");
        Object rawReporter = payload.containsKey("reporter_id") ? payload.get("reporter_id") : payload.get("reporterId");
        Object rawReason = payload.containsKey("reason") ? payload.get("reason") : payload.get("reportReason");
        String tenantId = payload.getOrDefault("tenant_id", payload.getOrDefault("tenantId", "GLOBAL")).toString();

        if (rawContentId == null || rawReporter == null) {
            return Map.of("status", "error", "message", "content_id and reporter_id are required");
        }

        Long contentId = Long.valueOf(rawContentId.toString());
        String reason = rawReason != null ? rawReason.toString() : "Reported content";

        Long reporterId;
        try {
            reporterId = Long.valueOf(rawReporter.toString());
        } catch (NumberFormatException e) {
            // It's an external user id (e.g. UUID string from simulator). Ensure user exists in users table:
            String userSql = """
                INSERT INTO users (tenant_id, external_user_id, username, email)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, external_user_id) DO UPDATE SET username = EXCLUDED.username
                RETURNING user_id
                """;
            reporterId = jdbcTemplate.queryForObject(userSql, Long.class,
                tenantId, rawReporter.toString(), "reporter_" + rawReporter.toString().substring(0, Math.min(6, rawReporter.toString().length())), rawReporter.toString() + "@test.com");
        }

        jdbcTemplate.update("INSERT INTO report (content_id, reporter_id, report_reason) VALUES (?, ?, ?)",
                contentId, reporterId, reason);

        // Check for 3+ reports in last 7 days logic (FR4)
        String countSql = "SELECT COUNT(*) FROM report WHERE content_id = ? AND reported_at > NOW() - INTERVAL '7 days'";
        Integer count = jdbcTemplate.queryForObject(countSql, Integer.class, contentId);

        if (count != null && count >= 3) {
            String insertFlagSql = """
                INSERT INTO content_flag (content_id, flag_type, reason, report_count) 
                SELECT ?, 'USER_REPORTS', 'Content flagged by multiple users', ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM content_flag 
                    WHERE content_id = ? AND flag_type = 'USER_REPORTS' AND status != 'RESOLVED'
                )
                """;
            jdbcTemplate.update(insertFlagSql, contentId, count, contentId);
        }

        return Map.of("status", "success");
    }
}
