package com.shhmods.engine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shhmods.engine.dto.ContentRequest;
import com.shhmods.engine.dto.DecisionResult;
import com.shhmods.engine.dto.ModerationDecision;
import com.shhmods.engine.dto.ModerationResponse;
import com.shhmods.engine.ml.OnnxClassifierService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class ContentService {

    private final JdbcTemplate jdbcTemplate;
    private final OnnxClassifierService onnxClassifierService;
    private final DecisionEngine decisionEngine;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ContentService(JdbcTemplate jdbcTemplate, 
                          OnnxClassifierService onnxClassifierService, 
                          DecisionEngine decisionEngine, 
                          ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.onnxClassifierService = onnxClassifierService;
        this.decisionEngine = decisionEngine;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ModerationResponse createContent(ContentRequest request) {
        return createContent(request, null);
    }

    public ModerationResponse createContent(ContentRequest request, String idempotencyKey) {
        // 1. Idempotency Check (Persistent DB Lookup)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                String checkSql = "SELECT response_payload FROM idempotency_record WHERE tenant_id = ? AND idempotency_key = ?";
                List<String> cachedPayloads = jdbcTemplate.query(checkSql, 
                    (rs, rowNum) -> rs.getString("response_payload"), request.getTenantId(), idempotencyKey);
                
                if (!cachedPayloads.isEmpty()) {
                    ModerationResponse cached = objectMapper.readValue(cachedPayloads.get(0), ModerationResponse.class);
                    cached.setCached(true);
                    return cached;
                }
            } catch (Exception ignored) {
                // If query fails, continue with normal processing
            }
        }

        // 2. Ensure User exists (reads/upserts outside long-running inference)
        String email = (request.getEmail() != null && !request.getEmail().isBlank()) 
            ? request.getEmail() 
            : request.getExternalUserId() + "@anon.internal";

        String userSql = """
            INSERT INTO users (tenant_id, external_user_id, username, email)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, external_user_id) DO UPDATE SET username = EXCLUDED.username
            RETURNING user_id
            """;
        
        Long userId = jdbcTemplate.queryForObject(userSql, Long.class, 
            request.getTenantId(), request.getExternalUserId(), request.getUsername(), email);

        // 3. Hash Content
        String contentHash = computeSha256(request.getContentText().toLowerCase().replaceAll("[\\s\\p{Punct}]", ""));

        // 4. AI Classifier - EXECUTED OUTSIDE DB TRANSACTION
        com.shhmods.engine.ml.ToxicityResult toxicityResult = onnxClassifierService.classify(request.getContentText());

        // 5. Decision Engine Evaluation - EXECUTED OUTSIDE DB WRITE TRANSACTION
        DecisionResult result = decisionEngine.evaluate(request, userId, contentHash, toxicityResult);

        // 6. Atomic persistence of Content, Immutable Decision Snapshot with Hash Chain, Trust, and Outbox
        return transactionTemplate.execute(status -> {
            boolean isVisible = (result.getDecision() == ModerationDecision.ALLOW || result.getDecision() == ModerationDecision.ALLOW_WITH_MONITORING);

            Long contentId = null;
            if (result.getDecision() != ModerationDecision.THROTTLE) {
                String contentSql = """
                    INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash, is_visible)
                    VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING content_id
                    """;
                contentId = jdbcTemplate.queryForObject(contentSql, Long.class,
                    request.getTenantId(), userId, request.getContentText(), request.getContentType(), contentHash, isVisible);
            }

            // 6.1 Cryptographic Hash Chaining on moderation_event
            String prevHash = "GENESIS";
            try {
                String prevSql = "SELECT event_hash FROM moderation_event WHERE tenant_id = ? AND event_hash IS NOT NULL ORDER BY created_at DESC, id DESC LIMIT 1";
                List<String> hashes = jdbcTemplate.query(prevSql, (rs, rowNum) -> rs.getString("event_hash"), request.getTenantId());
                if (!hashes.isEmpty() && hashes.get(0) != null) {
                    prevHash = hashes.get(0);
                }
            } catch (Exception ignored) {}

            String signalsJson = "{}";
            try {
                signalsJson = objectMapper.writeValueAsString(result.getSignals());
            } catch (JsonProcessingException ignored) {}

            String hashInput = prevHash + ":" + request.getTenantId() + ":" + userId + ":" + contentHash + ":" 
                + result.getPolicyVersion() + ":" + result.getDecision().name() + ":" + signalsJson;
            String eventHash = computeSha256(hashInput);

            // 6.2 Record to Ledger (moderation_event)
            String ledgerSql = """
                INSERT INTO moderation_event (tenant_id, user_id, content_hash, normalized_text, signals, policy_version, decision, prev_event_hash, event_hash)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                RETURNING id
                """;
                
            Object eventId = jdbcTemplate.queryForObject(ledgerSql, Object.class, 
                request.getTenantId(), userId, contentHash, result.getNormalizedText(), signalsJson, 
                result.getPolicyVersion(), result.getDecision().name(), prevHash, eventHash);

            // 6.3 Record Trust Event if applicable
            if (result.getPenalty() != null) {
                String trustSql = """
                    INSERT INTO trust_event (user_id, compartment, penalty, decay_rate)
                    VALUES (?, ?, ?, ?)
                    """;
                jdbcTemplate.update(trustSql, userId, result.getCompartment(), result.getPenalty(), result.getDecayRate());
            }

            // 6.4 Record Flag if content is flagged for review or blocked
            if (contentId != null && (result.getDecision() == ModerationDecision.REVIEW || result.getDecision() == ModerationDecision.BLOCK)) {
                String flagSql = """
                    INSERT INTO content_flag (content_id, flag_type, reason, status)
                    VALUES (?, ?, ?, 'PENDING')
                    """;
                String flagType = Boolean.TRUE.equals(result.getSignals().get("onnx_toxic")) ? "AI_TOXICITY" : "RULE_MATCH";
                jdbcTemplate.update(flagSql, contentId, flagType, result.getReason());
            }

            // 6.5 Transactional Outbox 
            String outboxSql = """
                INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """;
            
            String outboxPayload = "{}";
            try {
                outboxPayload = objectMapper.writeValueAsString(Map.of(
                    "event_id", eventId != null ? eventId.toString() : "",
                    "decision", result.getDecision().name(),
                    "reason", result.getReason()
                ));
            } catch (JsonProcessingException ignored) {}
            jdbcTemplate.update(outboxSql, "MODERATION_EVENT", eventId != null ? eventId.toString() : "", "DECISION_MADE", outboxPayload);

            // 6.6 Build structured ModerationResponse
            ModerationResponse response = new ModerationResponse(
                contentId,
                result.getDecision(),
                result.getReason(),
                result.getSignals(),
                eventId != null ? eventId.toString() : null,
                false,
                Instant.now().toString()
            );

            // 6.7 Persist Idempotency Record
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                try {
                    String recordSql = """
                        INSERT INTO idempotency_record (tenant_id, idempotency_key, content_id, decision, response_payload)
                        VALUES (?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        """;
                    jdbcTemplate.update(recordSql, request.getTenantId(), idempotencyKey, contentId, 
                        result.getDecision().name(), objectMapper.writeValueAsString(response));
                } catch (Exception ignored) {}
            }

            return response;
        });
    }

    /**
     * Redacts content text for privacy/GDPR requests while preserving the tamper-evident moderation decision ledger.
     */
    public boolean redactContent(Long contentId, String reason) {
        int updated = jdbcTemplate.update(
            "UPDATE content SET content_text = '[REDACTED DUE TO PRIVACY REQUEST]', is_visible = false, is_redacted = true WHERE content_id = ?",
            contentId
        );
        if (updated > 0) {
            jdbcTemplate.update(
                "INSERT INTO audit_log (action_type, action_details, reference_type, reference_id, actor_type) VALUES ('CONTENT_REDACTED', ?, 'CONTENT', ?, 'ADMIN')",
                reason != null ? reason : "Privacy / deletion request", String.valueOf(contentId)
            );
            return true;
        }
        return false;
    }

    private String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
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

