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
        Long contentId = Long.valueOf(payload.get("content_id").toString());
        Long reporterId = Long.valueOf(payload.get("reporter_id").toString());
        String reason = payload.get("reason").toString();

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
