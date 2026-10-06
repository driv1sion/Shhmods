package com.shhmods.engine.service;

import com.shhmods.engine.dto.ContentRequest;
import com.shhmods.engine.dto.DecisionResult;
import com.shhmods.engine.dto.ModerationDecision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class DecisionEngine {

    private final JdbcTemplate jdbcTemplate;

    public DecisionEngine(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DecisionResult evaluate(ContentRequest request, Long userId, String contentHash, boolean isToxic) {
        Map<String, Object> signals = new HashMap<>();
        signals.put("onnx_toxic", isToxic);

        // 1. Normalize Text
        String normalizedText = normalizeText(request.getContentText());
        signals.put("normalized_length", normalizedText.length());

        // 2. Check Burst Rate (using SQL instead of trigger)
        String burstSql = """
            SELECT COUNT(*) 
            FROM content 
            WHERE user_id = ? AND created_at > NOW() - INTERVAL '1 minute'
            """;
        Integer burstCount = jdbcTemplate.queryForObject(burstSql, Integer.class, userId);
        burstCount = (burstCount == null) ? 0 : burstCount;
        signals.put("burst_1m", burstCount);

        // Fetch limits from config
        String limitSql = """
            SELECT burst_limit FROM tenant_config WHERE tenant_id = ? 
            UNION ALL 
            SELECT burst_limit FROM tenant_config WHERE tenant_id = 'GLOBAL' 
            LIMIT 1
            """;
        Integer burstLimit = jdbcTemplate.queryForObject(limitSql, Integer.class, request.getTenantId());
        if (burstLimit != null && burstCount >= burstLimit) {
            return new DecisionResult(ModerationDecision.THROTTLE, "Burst rate limit exceeded", signals, 1, normalizedText);
        }

        // 3. Check Duplicates (24h rolling window)
        String dupSql = """
            SELECT COUNT(*) FROM content 
            WHERE content_hash = ? AND created_at > NOW() - INTERVAL '24 hours'
            """;
        Integer dupCount = jdbcTemplate.queryForObject(dupSql, Integer.class, contentHash);
        dupCount = (dupCount == null) ? 0 : dupCount;
        signals.put("duplicate_24h_count", dupCount);

        if (dupCount > 0) {
            return new DecisionResult(ModerationDecision.BLOCK, "Duplicate content detected", signals, 1, normalizedText);
        }

        // 3.5 Check Coordinated Content Posting (Graph Detection)
        String clusterSql = """
            SELECT MAX(shared_hashes) FROM content_clusters 
            WHERE poster_a = ? OR poster_b = ?
            """;
        Integer maxSharedHashes = null;
        try {
            maxSharedHashes = jdbcTemplate.queryForObject(clusterSql, Integer.class, userId, userId);
        } catch (org.springframework.dao.DataAccessException e) {
            // Might be empty or view not populated
        }
        maxSharedHashes = (maxSharedHashes == null) ? 0 : maxSharedHashes;
        signals.put("coordination_score", maxSharedHashes);

        if (maxSharedHashes >= 2) {
            return new DecisionResult(ModerationDecision.REVIEW, "Coordinated posting behavior detected", signals, 1, normalizedText);
        }

        // 4. Banned Words Exact/Fuzzy Match
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
            return new DecisionResult(ModerationDecision.REVIEW, "Banned keyword or fuzzy match detected", signals, 1, normalizedText);
        }

        // 5. Basic URL checking (Simulating policy rule)
        if (Pattern.compile("https?://[a-zA-Z0-9\\\\.\\\\-]+").matcher(normalizedText).find()) {
            signals.put("contains_url", true);
            // Just monitor or review based on severity (For now REVIEW)
            return new DecisionResult(ModerationDecision.REVIEW, "Suspicious URL detected", signals, 1, normalizedText);
        }

        // If toxic from AI, trigger review
        if (isToxic) {
            return new DecisionResult(ModerationDecision.REVIEW, "AI flagged as toxic", signals, 1, normalizedText);
        }

        return new DecisionResult(ModerationDecision.ALLOW, "Passed all checks", signals, 1, normalizedText);
    }

    private String normalizeText(String input) {
        if (input == null) return "";
        // Basic ZWSP removal and lowercase (as in V3)
        return input.replaceAll("[\\u200B-\\u200D\\uFEFF]", "").toLowerCase();
    }
}
