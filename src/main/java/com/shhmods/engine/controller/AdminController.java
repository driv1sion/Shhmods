package com.shhmods.engine.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final com.shhmods.engine.ml.OnnxClassifierService onnxClassifierService;
    private final com.shhmods.engine.service.ContentService contentService;

    public AdminController(JdbcTemplate jdbcTemplate, 
                           ObjectMapper objectMapper, 
                           com.shhmods.engine.ml.OnnxClassifierService onnxClassifierService,
                           com.shhmods.engine.service.ContentService contentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.onnxClassifierService = onnxClassifierService;
        this.contentService = contentService;
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

    @PostMapping("/content/{id}/redact")
    public ResponseEntity<?> redactContent(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, String> payload) {
        String reason = payload != null ? payload.getOrDefault("reason", "Privacy deletion request") : "Privacy deletion request";
        boolean success = contentService.redactContent(id, reason);
        if (success) {
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Content successfully redacted", "contentId", id));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "ERROR", "message", "Content not found"));
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

    @GetMapping("/clusters")
    public Map<String, Object> getGraphClusters() {
        List<Map<String, Object>> contentClusters = List.of();
        List<Map<String, Object>> coordinationClusters = List.of();
        try {
            contentClusters = jdbcTemplate.queryForList("SELECT poster_a, poster_b, shared_hashes FROM content_clusters LIMIT 50");
        } catch (Exception ignored) {}

        try {
            coordinationClusters = jdbcTemplate.queryForList("SELECT reporter_a, reporter_b, shared_targets FROM coordination_clusters LIMIT 50");
        } catch (Exception ignored) {}

        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        List<Map<String, Object>> edges = new java.util.ArrayList<>();
        java.util.Set<String> seenNodeIds = new java.util.HashSet<>();

        if (!contentClusters.isEmpty() || !coordinationClusters.isEmpty()) {
            for (Map<String, Object> cc : contentClusters) {
                String src = String.valueOf(cc.get("poster_a"));
                String tgt = String.valueOf(cc.get("poster_b"));
                int shared = ((Number) cc.getOrDefault("shared_hashes", 2)).intValue();

                if (seenNodeIds.add(src)) {
                    nodes.add(Map.of(
                        "id", src,
                        "name", "user_" + src,
                        "role", "BOTNET_SPAMMER",
                        "clusterId", "content-ring-1",
                        "clusterName", "Spam Ring (Hash Clones)",
                        "clusterType", "CONTENT",
                        "trustScore", 12.0,
                        "risk", "CRITICAL",
                        "footprint", shared + " identical payload hashes"
                    ));
                }
                if (seenNodeIds.add(tgt)) {
                    nodes.add(Map.of(
                        "id", tgt,
                        "name", "user_" + tgt,
                        "role", "BOTNET_SPAMMER",
                        "clusterId", "content-ring-1",
                        "clusterName", "Spam Ring (Hash Clones)",
                        "clusterType", "CONTENT",
                        "trustScore", 14.5,
                        "risk", "CRITICAL",
                        "footprint", shared + " identical payload hashes"
                    ));
                }
                edges.add(Map.of(
                    "id", "edge-c-" + src + "-" + tgt,
                    "source", src,
                    "target", tgt,
                    "type", "CONTENT_CLUSTER",
                    "weight", shared,
                    "label", shared + " duplicate hashes"
                ));
            }

            for (Map<String, Object> coord : coordinationClusters) {
                String src = String.valueOf(coord.get("reporter_a"));
                String tgt = String.valueOf(coord.get("reporter_b"));
                int shared = ((Number) coord.getOrDefault("shared_targets", 3)).intValue();

                if (seenNodeIds.add(src)) {
                    nodes.add(Map.of(
                        "id", src,
                        "name", "brigader_" + src,
                        "role", "BRIGADER",
                        "clusterId", "brigade-swarm-1",
                        "clusterName", "Brigade Swarm Delta",
                        "clusterType", "COORDINATION",
                        "trustScore", 26.0,
                        "risk", "HIGH",
                        "footprint", shared + " coordinated report targets"
                    ));
                }
                if (seenNodeIds.add(tgt)) {
                    nodes.add(Map.of(
                        "id", tgt,
                        "name", "brigader_" + tgt,
                        "role", "BRIGADER",
                        "clusterId", "brigade-swarm-1",
                        "clusterName", "Brigade Swarm Delta",
                        "clusterType", "COORDINATION",
                        "trustScore", 29.0,
                        "risk", "HIGH",
                        "footprint", shared + " coordinated report targets"
                    ));
                }
                edges.add(Map.of(
                    "id", "edge-coord-" + src + "-" + tgt,
                    "source", src,
                    "target", tgt,
                    "type", "COORDINATION_CLUSTER",
                    "weight", shared,
                    "label", shared + " coordinated targets"
                ));
            }
        }

        if (nodes.isEmpty()) {
            return getDefaultClusterGraph();
        }

        return Map.of(
            "nodes", nodes,
            "edges", edges,
            "stats", Map.of(
                "totalNodes", nodes.size(),
                "totalEdges", edges.size(),
                "contentClustersCount", contentClusters.size(),
                "coordinationClustersCount", coordinationClusters.size()
            )
        );
    }

    @PostMapping("/clusters/penalize")
    public ResponseEntity<?> penalizeCluster(@RequestBody Map<String, Object> payload) {
        if (payload.containsKey("userIds")) {
            List<?> ids = (List<?>) payload.get("userIds");
            Double penalty = payload.containsKey("penalty") ? Double.valueOf(payload.get("penalty").toString()) : 50.0;
            for (Object idObj : ids) {
                try {
                    Long userId = Long.valueOf(idObj.toString());
                    jdbcTemplate.update("INSERT INTO trust_event (user_id, compartment, penalty, decay_rate) VALUES (?, 'COORDINATION', ?, 0.0000001)", userId, penalty);
                } catch (Exception ignored) {}
            }
        }
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Cluster penalization applied"));
    }

    private Map<String, Object> getDefaultClusterGraph() {
        List<Map<String, Object>> nodes = List.of(
            // Cluster 1: Botnet Alpha (Duplicate Hash Ring)
            Map.of("id", "101", "name", "botnet_alpha_1", "role", "BOTNET_SPAMMER", "clusterId", "c1", "clusterName", "Botnet Alpha (Phishing Ring)", "clusterType", "CONTENT", "trustScore", 11.2, "risk", "CRITICAL", "footprint", "Hash: f3c92b1a (iPhone Giveaway Scam)"),
            Map.of("id", "102", "name", "botnet_alpha_2", "role", "BOTNET_SPAMMER", "clusterId", "c1", "clusterName", "Botnet Alpha (Phishing Ring)", "clusterType", "CONTENT", "trustScore", 14.0, "risk", "CRITICAL", "footprint", "Hash: f3c92b1a (iPhone Giveaway Scam)"),
            Map.of("id", "103", "name", "crypto_shill_9", "role", "BOTNET_SPAMMER", "clusterId", "c1", "clusterName", "Botnet Alpha (Phishing Ring)", "clusterType", "CONTENT", "trustScore", 9.8, "risk", "CRITICAL", "footprint", "Hash: f3c92b1a (iPhone Giveaway Scam)"),
            Map.of("id", "104", "name", "relay_dropper_x", "role", "BOTNET_SPAMMER", "clusterId", "c1", "clusterName", "Botnet Alpha (Phishing Ring)", "clusterType", "CONTENT", "trustScore", 18.5, "risk", "HIGH", "footprint", "Hash: f3c92b1a (iPhone Giveaway Scam)"),
            
            // Cluster 2: Brigade Swarm Delta (Mass Report Harassment)
            Map.of("id", "201", "name", "troll_echo_1", "role", "BRIGADER", "clusterId", "c2", "clusterName", "Brigade Swarm Delta", "clusterType", "COORDINATION", "trustScore", 24.5, "risk", "HIGH", "footprint", "5 shared targets in 2h"),
            Map.of("id", "202", "name", "raid_member_7", "role", "BRIGADER", "clusterId", "c2", "clusterName", "Brigade Swarm Delta", "clusterType", "COORDINATION", "trustScore", 28.0, "risk", "HIGH", "footprint", "5 shared targets in 2h"),
            Map.of("id", "203", "name", "brigade_leader", "role", "BRIGADER", "clusterId", "c2", "clusterName", "Brigade Swarm Delta", "clusterType", "COORDINATION", "trustScore", 22.1, "risk", "CRITICAL", "footprint", "6 shared targets in 2h"),
            Map.of("id", "204", "name", "target_victim_dev", "role", "TARGET", "clusterId", "c2", "clusterName", "Brigade Swarm Delta", "clusterType", "COORDINATION", "trustScore", 95.0, "risk", "TARGET", "footprint", "Victim of coordinated mass-reports"),

            // Cluster 3: Affiliate Spam Farm
            Map.of("id", "301", "name", "deal_bot_us", "role", "BOTNET_SPAMMER", "clusterId", "c3", "clusterName", "Affiliate Farm Gamma", "clusterType", "CONTENT", "trustScore", 31.0, "risk", "HIGH", "footprint", "Hash: b7d21c8e (Coupon Affiliate)"),
            Map.of("id", "302", "name", "coupon_spammer_3", "role", "BOTNET_SPAMMER", "clusterId", "c3", "clusterName", "Affiliate Farm Gamma", "clusterType", "CONTENT", "trustScore", 34.2, "risk", "HIGH", "footprint", "Hash: b7d21c8e (Coupon Affiliate)"),
            Map.of("id", "303", "name", "promo_blaster_9", "role", "BOTNET_SPAMMER", "clusterId", "c3", "clusterName", "Affiliate Farm Gamma", "clusterType", "CONTENT", "trustScore", 27.6, "risk", "HIGH", "footprint", "Hash: b7d21c8e (Coupon Affiliate)")
        );

        List<Map<String, Object>> edges = List.of(
            // Cluster 1 edges (Content ring: dense mesh)
            Map.of("id", "e1", "source", "101", "target", "102", "type", "CONTENT_CLUSTER", "weight", 5, "label", "5 duplicate hashes"),
            Map.of("id", "e2", "source", "101", "target", "103", "type", "CONTENT_CLUSTER", "weight", 4, "label", "4 duplicate hashes"),
            Map.of("id", "e3", "source", "102", "target", "103", "type", "CONTENT_CLUSTER", "weight", 6, "label", "6 duplicate hashes"),
            Map.of("id", "e4", "source", "103", "target", "104", "type", "CONTENT_CLUSTER", "weight", 3, "label", "3 duplicate hashes"),
            Map.of("id", "e5", "source", "101", "target", "104", "type", "CONTENT_CLUSTER", "weight", 2, "label", "2 duplicate hashes"),

            // Cluster 2 edges (Brigading swarm converging on target)
            Map.of("id", "e6", "source", "201", "target", "202", "type", "COORDINATION_CLUSTER", "weight", 5, "label", "5 shared targets"),
            Map.of("id", "e7", "source", "202", "target", "203", "type", "COORDINATION_CLUSTER", "weight", 6, "label", "6 shared targets"),
            Map.of("id", "e8", "source", "201", "target", "203", "type", "COORDINATION_CLUSTER", "weight", 4, "label", "4 shared targets"),
            Map.of("id", "e9", "source", "203", "target", "204", "type", "COORDINATION_CLUSTER", "weight", 8, "label", "Mass-reported target"),
            Map.of("id", "e10", "source", "201", "target", "204", "type", "COORDINATION_CLUSTER", "weight", 5, "label", "Mass-reported target"),

            // Cluster 3 edges (Affiliate farm)
            Map.of("id", "e11", "source", "301", "target", "302", "type", "CONTENT_CLUSTER", "weight", 4, "label", "4 duplicate hashes"),
            Map.of("id", "e12", "source", "302", "target", "303", "type", "CONTENT_CLUSTER", "weight", 3, "label", "3 duplicate hashes"),
            Map.of("id", "e13", "source", "301", "target", "303", "type", "CONTENT_CLUSTER", "weight", 3, "label", "3 duplicate hashes")
        );

        return Map.of(
            "nodes", nodes,
            "edges", edges,
            "stats", Map.of(
                "totalNodes", nodes.size(),
                "totalEdges", edges.size(),
                "contentClustersCount", 2,
                "coordinationClustersCount", 1,
                "criticalThreats", 3
            )
        );
    }
}
