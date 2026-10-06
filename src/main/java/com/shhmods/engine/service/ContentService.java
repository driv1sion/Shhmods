package com.shhmods.engine.service;

import com.shhmods.engine.dto.ContentRequest;
import com.shhmods.engine.dto.DecisionResult;
import com.shhmods.engine.dto.ModerationDecision;
import com.shhmods.engine.ml.OnnxClassifierService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

@Service
public class ContentService {

    private final JdbcTemplate jdbcTemplate;
    private final OnnxClassifierService onnxClassifierService;
    private final DecisionEngine decisionEngine;
    private final ObjectMapper objectMapper;

    public ContentService(JdbcTemplate jdbcTemplate, OnnxClassifierService onnxClassifierService, DecisionEngine decisionEngine, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.onnxClassifierService = onnxClassifierService;
        this.decisionEngine = decisionEngine;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long createContent(ContentRequest request) {
        // 1. Ensure User exists
        String userSql = """
            INSERT INTO users (tenant_id, external_user_id, username, email)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, external_user_id) DO UPDATE SET username = EXCLUDED.username
            RETURNING user_id
            """;
        
        Long userId = jdbcTemplate.queryForObject(userSql, Long.class, 
            request.getTenantId(), request.getExternalUserId(), request.getUsername(), request.getEmail());

        // 2. Hash Content
        String hash = generateHash(request.getContentText());

        // 3. AI Classifier
        boolean isToxic = onnxClassifierService.isToxic(request.getContentText());

        // 4. Decision Engine Evaluation
        DecisionResult result = decisionEngine.evaluate(request, userId, hash, isToxic);

        // 5. If THROTTLE or BLOCK, we might not even insert content, or we insert it as hidden.
        // As per the roadmap, "observe -> throttle -> hide -> suspend". We still store it if it's REVIEW or ALLOW,
        // and we can store it as hidden if BLOCK.
        boolean isVisible = (result.getDecision() == ModerationDecision.ALLOW || result.getDecision() == ModerationDecision.ALLOW_WITH_MONITORING);

        String contentSql = """
            INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash, is_visible)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING content_id
            """;
            
        Long contentId = null;
        if (result.getDecision() != ModerationDecision.THROTTLE) {
            contentId = jdbcTemplate.queryForObject(contentSql, Long.class,
                request.getTenantId(), userId, request.getContentText(), request.getContentType(), hash, isVisible);
        }

        // 6. Record to Ledger (moderation_event)
        String signalsJson = "{}";
        try {
            signalsJson = objectMapper.writeValueAsString(result.getSignals());
        } catch (JsonProcessingException e) {
            // Ignore for now
        }

        String ledgerSql = """
            INSERT INTO moderation_event (tenant_id, user_id, content_hash, normalized_text, signals, policy_version, decision)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            RETURNING id
            """;
            
        Object eventId = jdbcTemplate.queryForObject(ledgerSql, Object.class, 
            request.getTenantId(), userId, hash, result.getNormalizedText(), signalsJson, result.getPolicyVersion(), result.getDecision().name());

        // 6.5 Record Trust Event if applicable
        if (result.getPenalty() != null) {
            String trustSql = """
                INSERT INTO trust_event (user_id, compartment, penalty, decay_rate)
                VALUES (?, ?, ?, ?)
                """;
            jdbcTemplate.update(trustSql, userId, result.getCompartment(), result.getPenalty(), result.getDecayRate());
        }

        // 7. Transactional Outbox 
        String outboxSql = """
            INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
            VALUES (?, ?, ?, ?::jsonb)
            """;
        
        String outboxPayload = "{}";
        try {
            outboxPayload = objectMapper.writeValueAsString(Map.of(
                "event_id", eventId.toString(),
                "decision", result.getDecision().name(),
                "reason", result.getReason()
            ));
        } catch (JsonProcessingException e) {
            // Ignore for now
        }
        jdbcTemplate.update(outboxSql, "MODERATION_EVENT", eventId.toString(), "DECISION_MADE", outboxPayload);

        // If throttled, throw specific exception or handle in controller
        if (result.getDecision() == ModerationDecision.THROTTLE) {
            throw new RuntimeException("Burst rate limit exceeded"); // Handled by controller for 429
        }

        return contentId;
    }
    
    private String generateHash(String text) {
        try {
            String normalized = text.toLowerCase().replaceAll("[\\\\s\\\\p{Punct}]", "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
