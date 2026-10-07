package com.shhmods.engine.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AdminController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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

    @PostMapping("/ban")
    public ResponseEntity<?> banUser(@RequestBody Map<String, Object> payload) {
        if (payload.containsKey("content_id")) {
            Long contentId = Long.valueOf(payload.get("content_id").toString());
            // Update decision to BLOCK
            jdbcTemplate.update("UPDATE content SET decision = 'BLOCK', is_visible = false WHERE content_id = ?", contentId);
            
            // Extract poster_id and penalize
            Long posterId = jdbcTemplate.queryForObject("SELECT user_id FROM content WHERE content_id = ?", Long.class, contentId);
            if (posterId != null) {
                jdbcTemplate.update("INSERT INTO trust_ledger (user_id, compartment, delta, reason) VALUES (?, 'SPAM', -50.0, 'Manual Admin Ban')", posterId);
            }
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/dismiss")
    public ResponseEntity<?> dismissFlag(@RequestBody Map<String, Object> payload) {
        if (payload.containsKey("content_id")) {
            Long contentId = Long.valueOf(payload.get("content_id").toString());
            jdbcTemplate.update("UPDATE content SET decision = 'ALLOW', is_visible = true WHERE content_id = ?", contentId);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/policy")
    public ResponseEntity<?> savePolicy(@RequestBody Map<String, Object> payload) {
        try {
            String ruleName = (String) payload.get("rule_name");
            String overrideAction = (String) payload.get("override_action");
            String conditionJson = objectMapper.writeValueAsString(payload.get("condition_json"));
            String tenantId = payload.containsKey("tenant_id") ? (String) payload.get("tenant_id") : "GLOBAL";

            jdbcTemplate.update("INSERT INTO tenant_policy (tenant_id, rule_name, override_action, condition_json, is_active) VALUES (?, ?, ?, ?::jsonb, true) " +
                "ON CONFLICT (tenant_id, rule_name) DO UPDATE SET override_action = EXCLUDED.override_action, condition_json = EXCLUDED.condition_json, is_active = EXCLUDED.is_active",
                tenantId, ruleName, overrideAction, conditionJson);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid policy format");
        }
        return ResponseEntity.ok().build();
    }
}
