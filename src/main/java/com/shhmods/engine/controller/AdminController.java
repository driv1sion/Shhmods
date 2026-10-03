package com.shhmods.engine.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final JdbcTemplate jdbcTemplate;

    public AdminController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/flags")
    public List<Map<String, Object>> getFlags() {
        return jdbcTemplate.queryForList("SELECT * FROM vw_flagged_content");
    }

    @GetMapping("/leaderboard")
    public List<Map<String, Object>> getLeaderboard() {
        return jdbcTemplate.queryForList("SELECT * FROM vw_top_users");
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> getAuditLog() {
        return jdbcTemplate.queryForList("SELECT * FROM vw_audit_log LIMIT 100");
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        List<Map<String, Object>> configs = jdbcTemplate.queryForList("SELECT * FROM tenant_config WHERE tenant_id = 'GLOBAL'");
        return configs.isEmpty() ? Map.of() : configs.get(0);
    }
}
