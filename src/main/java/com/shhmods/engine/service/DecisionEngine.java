package com.shhmods.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shhmods.engine.dto.ContentRequest;
import com.shhmods.engine.dto.DecisionResult;
import com.shhmods.engine.dto.ModerationDecision;
import com.shhmods.engine.dto.TenantPolicyDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class DecisionEngine {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TextNormalizationService normalizer;

    public DecisionEngine(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, TextNormalizationService normalizer) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
    }

    private Double getTrustScore(Long userId, String compartment) {
        try {
            Double score = jdbcTemplate.queryForObject(
                "SELECT current_trust FROM effective_trust WHERE user_id = ? AND compartment = ?", 
                Double.class, userId, compartment);
            return score != null ? score : 100.0;
        } catch (org.springframework.dao.DataAccessException e) {
            return 100.0;
        }
    }

    private Map<String, TenantPolicyDto> loadPolicies(String tenantId) {
        Map<String, TenantPolicyDto> policyMap = new HashMap<>();
        String sql = """
            SELECT rule_name, override_action, condition_json 
            FROM tenant_policy 
            WHERE (tenant_id = ? OR tenant_id = 'GLOBAL') AND is_active = TRUE
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tenantId);
        for (Map<String, Object> row : rows) {
            String ruleName = (String) row.get("rule_name");
            String action = (String) row.get("override_action");
            String json = row.get("condition_json") != null ? row.get("condition_json").toString() : "{}";
            
            try {
                TenantPolicyDto dto = objectMapper.readValue(json, TenantPolicyDto.class);
                dto.setRuleName(ruleName);
                dto.setOverrideAction(action);
                policyMap.put(ruleName, dto);
            } catch (JsonProcessingException e) {
                // Skip invalid JSON policies
            }
        }
        return policyMap;
    }

    private boolean canBypass(String ruleName, Map<String, TenantPolicyDto> policies, Double spamTrust, Double accountAgeDays) {
        TenantPolicyDto policy = policies.get(ruleName);
        if (policy == null || !"BYPASS".equals(policy.getOverrideAction())) return false;
        
        if (policy.getMinSpamTrust() != null && spamTrust < policy.getMinSpamTrust()) return false;
        if (policy.getMinAccountAgeDays() != null && accountAgeDays < policy.getMinAccountAgeDays()) return false;
        
        return true;
    }

    public DecisionResult evaluate(ContentRequest request, Long userId, String contentHash, boolean isToxic) {
        return evaluate(request, userId, contentHash, new com.shhmods.engine.ml.ToxicityResult(isToxic, isToxic ? 1.0 : 0.0, Map.of(), isToxic ? List.of("toxic") : List.of(), 0L));
    }

    public DecisionResult evaluate(ContentRequest request, Long userId, String contentHash, com.shhmods.engine.ml.ToxicityResult toxicity) {
        Map<String, Object> signals = new HashMap<>();
        boolean isToxic = toxicity != null && toxicity.isToxic();
        signals.put("onnx_toxic", isToxic);
        if (toxicity != null) {
            signals.put("onnx_toxicity_score", toxicity.toxicityScore());
            signals.put("onnx_categories", toxicity.categoryScores());
            signals.put("onnx_flagged_categories", toxicity.flaggedCategories());
            signals.put("onnx_inference_time_ms", toxicity.inferenceTimeMs());
        }

        Double spamTrust = getTrustScore(userId, "SPAM");
        Double coordTrust = getTrustScore(userId, "COORDINATION");
        Double harassTrust = getTrustScore(userId, "HARASSMENT");
        signals.put("spam_trust", spamTrust);
        signals.put("coord_trust", coordTrust);
        signals.put("harassment_trust", harassTrust);

        Double accountAgeDays = 0.0;
        try {
            accountAgeDays = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (now() - account_created_at))/86400 FROM users WHERE user_id = ?", 
                Double.class, userId);
            accountAgeDays = accountAgeDays != null ? accountAgeDays : 0.0;
        } catch (Exception e) {}
        signals.put("account_age_days", accountAgeDays);

        Map<String, TenantPolicyDto> policies = loadPolicies(request.getTenantId());

        if (spamTrust < 20.0 || coordTrust < 20.0 || harassTrust < 20.0) {
            if (!canBypass("TRUST_SCORE", policies, spamTrust, accountAgeDays)) {
                return new DecisionResult(ModerationDecision.BLOCK, "User trust score too low", signals, 1, "");
            }
        }

        String normalizedText = normalizer.normalize(request.getContentText());
        signals.put("normalized_length", normalizedText.length());

        String burstSql = "SELECT COUNT(*) FROM content WHERE user_id = ? AND created_at > NOW() - INTERVAL '1 minute'";
        Integer burstCount = jdbcTemplate.queryForObject(burstSql, Integer.class, userId);
        burstCount = (burstCount == null) ? 0 : burstCount;
        signals.put("burst_1m", burstCount);

        String limitSql = "SELECT burst_limit FROM tenant_config WHERE tenant_id = ? UNION ALL SELECT burst_limit FROM tenant_config WHERE tenant_id = 'GLOBAL' LIMIT 1";
        Integer burstLimit = jdbcTemplate.queryForObject(limitSql, Integer.class, request.getTenantId());
        
        if (burstLimit != null) {
            int effectiveLimit = spamTrust < 50.0 ? Math.max(1, burstLimit / 2) : burstLimit;
            if (burstCount >= effectiveLimit) {
                if (!canBypass("BURST_RATE", policies, spamTrust, accountAgeDays)) {
                    return new DecisionResult(ModerationDecision.THROTTLE, "Burst rate limit exceeded", signals, 1, normalizedText);
                }
            }
        }

        String dupSql = "SELECT COUNT(*) FROM content WHERE content_hash = ? AND created_at > NOW() - INTERVAL '24 hours'";
        Integer dupCount = jdbcTemplate.queryForObject(dupSql, Integer.class, contentHash);
        dupCount = (dupCount == null) ? 0 : dupCount;
        signals.put("duplicate_24h_count", dupCount);

        if (dupCount > 0) {
            if (!canBypass("DUPLICATE_CONTENT", policies, spamTrust, accountAgeDays)) {
                return new DecisionResult(ModerationDecision.BLOCK, "Duplicate content detected", signals, 1, normalizedText)
                    .withTrustPenalty("SPAM", 10.0, 0.000001);
            }
        }

        String clusterSql = "SELECT MAX(shared_hashes) FROM content_clusters WHERE poster_a = ? OR poster_b = ?";
        Integer maxSharedHashes = null;
        try {
            maxSharedHashes = jdbcTemplate.queryForObject(clusterSql, Integer.class, userId, userId);
        } catch (Exception e) {}
        
        maxSharedHashes = (maxSharedHashes == null) ? 0 : maxSharedHashes;
        signals.put("coordination_score", maxSharedHashes);

        if (maxSharedHashes >= 2) {
            if (!canBypass("COORDINATION", policies, spamTrust, accountAgeDays)) {
                return new DecisionResult(ModerationDecision.REVIEW, "Coordinated posting behavior detected", signals, 1, normalizedText)
                    .withTrustPenalty("COORDINATION", 25.0, 0.0000005);
            }
        }

        String matchSql = """
            SELECT EXISTS (
                SELECT 1 FROM banned_word 
                WHERE (tenant_id = ? OR tenant_id = 'GLOBAL')
                  AND word_status = 'ACTIVE'
                  AND (
                      (match_type = 'EXACT_WORD_ONLY' AND ? ~* ('\\y' || keyword || '\\y')) OR
                      (match_type = 'SUBSTRING_ALLOWED' AND (
                          ? ILIKE '%' || keyword || '%' OR 
                          similarity(?, keyword) > (SELECT COALESCE((SELECT trgm_similarity_threshold FROM tenant_config WHERE tenant_id = ?), 0.35))
                      ))
                  )
            )
            """;
        
        Boolean isMatch = jdbcTemplate.queryForObject(matchSql, Boolean.class, 
            request.getTenantId(), normalizedText, normalizedText, normalizedText, request.getTenantId());
            
        signals.put("banned_word_match", isMatch);
        if (Boolean.TRUE.equals(isMatch)) {
            if (!canBypass("BANNED_WORD", policies, spamTrust, accountAgeDays)) {
                return new DecisionResult(ModerationDecision.REVIEW, "Banned keyword or fuzzy match detected", signals, 1, normalizedText)
                    .withTrustPenalty("HARASSMENT", 15.0, 0.000001);
            }
        }

        if (Pattern.compile("https?://[a-zA-Z0-9\\\\.\\\\-]+").matcher(normalizedText).find()) {
            signals.put("contains_url", true);
            if (spamTrust < 80.0) {
                if (!canBypass("URL_FILTER", policies, spamTrust, accountAgeDays)) {
                    return new DecisionResult(ModerationDecision.REVIEW, "Suspicious URL from untrusted user", signals, 1, normalizedText)
                        .withTrustPenalty("SPAM", 5.0, 0.000002);
                }
            }
        }

        if (isToxic) {
            if (!canBypass("AI_TOXICITY", policies, spamTrust, accountAgeDays)) {
                @SuppressWarnings("unchecked")
                List<String> flagged = (List<String>) signals.get("onnx_flagged_categories");
                String reason = (flagged != null && !flagged.isEmpty() && !(flagged.size() == 1 && flagged.contains("toxic")))
                        ? "AI flagged as toxic (" + String.join(", ", flagged) + ")"
                        : "AI flagged as toxic";

                boolean isSevere = flagged != null && (flagged.contains("threat") || flagged.contains("severe_toxic"));
                double penalty = isSevere ? 25.0 : 10.0;
                ModerationDecision decision = isSevere ? ModerationDecision.BLOCK : ModerationDecision.REVIEW;

                return new DecisionResult(decision, reason, signals, 1, normalizedText)
                    .withTrustPenalty("HARASSMENT", penalty, 0.000001);
            }
        }

        return new DecisionResult(ModerationDecision.ALLOW, "Passed all checks", signals, 1, normalizedText);
    }


}
