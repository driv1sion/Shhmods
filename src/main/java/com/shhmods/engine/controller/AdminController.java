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
    private final com.shhmods.engine.ml.OnnxClassifierService onnxClassifierService;

    public AdminController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, com.shhmods.engine.ml.OnnxClassifierService onnxClassifierService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.onnxClassifierService = onnxClassifierService;
    }

    @PostMapping("/classify")
    public ResponseEntity<com.shhmods.engine.ml.ToxicityResult> classifyText(@RequestBody Map<String, String> payload) {
        String text = payload.getOrDefault("text", "");
        return ResponseEntity.ok(onnxClassifierService.classify(text));
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
        String sql = """
            SELECT 
                id::text AS audit_id,
                decision AS action_type,
                signals::text AS reference_type,
                normalized_text AS action_details,
                'DECISION_ENGINE' AS actor_type,
                user_id::text AS reference_id,
                to_char(created_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS action_timestamp
            FROM moderation_event
            ORDER BY created_at DESC
            LIMIT 100
            """;
        return jdbcTemplate.queryForList(sql);
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
            // Update content visibility and flag status
            jdbcTemplate.update("UPDATE content SET is_visible = false WHERE content_id = ?", contentId);
            jdbcTemplate.update("UPDATE content_flag SET status = 'RESOLVED' WHERE content_id = ?", contentId);
            
            // Extract poster_id and penalize in trust_event
            Long posterId = jdbcTemplate.queryForObject("SELECT user_id FROM content WHERE content_id = ?", Long.class, contentId);
            if (posterId != null) {
                jdbcTemplate.update("INSERT INTO trust_event (user_id, compartment, penalty, decay_rate) VALUES (?, 'SPAM', 50.0, 0.0000001)", posterId);
            }
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/dismiss")
    public ResponseEntity<?> dismissFlag(@RequestBody Map<String, Object> payload) {
        if (payload.containsKey("content_id")) {
            Long contentId = Long.valueOf(payload.get("content_id").toString());
            jdbcTemplate.update("UPDATE content SET is_visible = true WHERE content_id = ?", contentId);
            jdbcTemplate.update("UPDATE content_flag SET status = 'RESOLVED' WHERE content_id = ?", contentId);
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
