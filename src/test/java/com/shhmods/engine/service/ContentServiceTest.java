package com.shhmods.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shhmods.engine.dto.ContentRequest;
import com.shhmods.engine.dto.DecisionResult;
import com.shhmods.engine.dto.ModerationDecision;
import com.shhmods.engine.dto.ModerationResponse;
import com.shhmods.engine.ml.OnnxClassifierService;
import com.shhmods.engine.ml.ToxicityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ContentServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private OnnxClassifierService onnxClassifierService;

    @Mock
    private DecisionEngine decisionEngine;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private com.shhmods.engine.trust.TrustCalculatorService trustCalculatorService;

    private ObjectMapper objectMapper;
    private ContentService contentService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        contentService = new ContentService(jdbcTemplate, onnxClassifierService, decisionEngine, objectMapper, transactionManager, trustCalculatorService);
    }

    @Test
    void testIdempotencyCacheHitReturnsCachedResponse() {
        ContentRequest request = new ContentRequest();
        request.setTenantId("test_tenant");
        request.setExternalUserId("user_123");
        request.setUsername("alice");
        request.setContentText("Hello world!");
        request.setContentType("CHAT");

        String cachedJson = """
            {
              "contentId": 42,
              "decision": "ALLOW",
              "reason": "Passed all checks",
              "signals": {},
              "eventId": "evt-1234",
              "cached": false
            }
            """;

        when(jdbcTemplate.query(contains("idempotency_record"), any(RowMapper.class), eq("test_tenant"), eq("key-abc")))
                .thenReturn(List.of(cachedJson));

        ModerationResponse response = contentService.createContent(request, "key-abc");

        assertNotNull(response);
        assertTrue(response.isCached());
        assertEquals(42L, response.getContentId());
        assertEquals(ModerationDecision.ALLOW, response.getDecision());

        // Verify that neither ONNX nor DecisionEngine were called on cache hit
        verifyNoInteractions(onnxClassifierService);
        verifyNoInteractions(decisionEngine);
    }

    @Test
    void testFreshSubmission_ChainsHashesAndPersistsIdempotency() {
        ContentRequest request = new ContentRequest();
        request.setTenantId("test_tenant");
        request.setExternalUserId("user_123");
        request.setUsername("alice");
        request.setContentText("Hello world!");
        request.setContentType("CHAT");

        // 1. Idempotency miss
        when(jdbcTemplate.query(contains("idempotency_record"), any(RowMapper.class), eq("test_tenant"), eq("key-new")))
                .thenReturn(List.of());

        // 2. User exists query
        when(jdbcTemplate.queryForObject(contains("users"), eq(Long.class), eq("test_tenant"), eq("user_123"), eq("alice"), anyString()))
                .thenReturn(101L);

        // 3. ML Classifier
        when(onnxClassifierService.classify(anyString())).thenReturn(ToxicityResult.safe());

        // 4. Decision Engine
        DecisionResult mockResult = new DecisionResult(ModerationDecision.ALLOW, "Passed all checks", Map.of("burst_1m", 0), 1, "hello world");
        when(decisionEngine.evaluate(eq(request), eq(101L), anyString(), any(ToxicityResult.class))).thenReturn(mockResult);

        // 5. Content Insert
        when(jdbcTemplate.queryForObject(contains("INSERT INTO content"), eq(Long.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(999L);

        // 6. Previous hash query returns genesis or earlier hash
        when(jdbcTemplate.query(contains("SELECT event_hash FROM moderation_event"), any(RowMapper.class), eq("test_tenant")))
                .thenReturn(List.of("PREV_HASH_1234567890"));

        // 7. Ledger insert
        when(jdbcTemplate.queryForObject(contains("INSERT INTO moderation_event"), eq(Object.class),
                eq("test_tenant"), eq(101L), anyString(), anyString(), anyString(), eq(1), eq("ALLOW"),
                eq("PREV_HASH_1234567890"), anyString()))
                .thenReturn("evt-uuid-555");

        ModerationResponse response = contentService.createContent(request, "key-new");

        assertNotNull(response);
        assertFalse(response.isCached());
        assertEquals(999L, response.getContentId());
        assertEquals(ModerationDecision.ALLOW, response.getDecision());
        assertEquals("evt-uuid-555", response.getEventId());

        // Verify idempotency record written to database
        verify(jdbcTemplate).update(contains("INSERT INTO idempotency_record"), eq("test_tenant"), eq("key-new"), eq(999L), eq("ALLOW"), anyString());
    }

    @Test
    void testRedactContent_RedactsTextAndAudits() {
        when(jdbcTemplate.update(contains("UPDATE content SET content_text = '[REDACTED DUE TO PRIVACY REQUEST]'"), eq(555L)))
                .thenReturn(1);

        boolean result = contentService.redactContent(555L, "User GDPR request");

        assertTrue(result);
        verify(jdbcTemplate).update(contains("INSERT INTO audit_log"), eq("User GDPR request"), eq("555"));
    }

    @Test
    void testContentCreation_WithPenalty_RecordsInfraction() {
        ContentRequest request = new ContentRequest();
        request.setTenantId("test_tenant");
        request.setExternalUserId("bad_user");
        request.setUsername("spammer");
        request.setContentText("Buy cheap crypto now http://spam.xyz");
        request.setContentType("POST");

        when(jdbcTemplate.query(contains("idempotency_record"), any(RowMapper.class), anyString(), anyString()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("users"), eq(Long.class), any(), any(), any(), any()))
                .thenReturn(202L);
        when(onnxClassifierService.classify(anyString())).thenReturn(ToxicityResult.safe());

        DecisionResult penalizedResult = new DecisionResult(ModerationDecision.REVIEW, "Suspicious URL", Map.of(), 1, "buy cheap crypto")
                .withTrustPenalty("SPAM", 15.0, 0.000001);
        when(decisionEngine.evaluate(eq(request), eq(202L), anyString(), any(ToxicityResult.class))).thenReturn(penalizedResult);

        when(jdbcTemplate.queryForObject(contains("INSERT INTO content"), eq(Long.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(1001L);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO moderation_event"), eq(Object.class), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("evt-review-1");

        ModerationResponse response = contentService.createContent(request, "key-penalize");

        assertNotNull(response);
        assertEquals(ModerationDecision.REVIEW, response.getDecision());

        // Verify infraction penalty recorded via TrustCalculatorService
        verify(trustCalculatorService).recordInfraction(eq(202L), eq("test_tenant"), eq("SPAM"), eq(15.0), eq("Suspicious URL"));
        verify(trustCalculatorService, never()).recordPositiveObservation(anyLong(), anyString(), anyDouble());
    }

    @Test
    void testContentCreation_WithPositiveObservation_RecordsReward() {
        ContentRequest request = new ContentRequest();
        request.setTenantId("test_tenant");
        request.setExternalUserId("good_user");
        request.setUsername("bob");
        request.setContentText("Constructive feedback on feature RFC");
        request.setContentType("POST");

        when(jdbcTemplate.query(contains("idempotency_record"), any(RowMapper.class), anyString(), anyString()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("users"), eq(Long.class), any(), any(), any(), any()))
                .thenReturn(303L);
        when(onnxClassifierService.classify(anyString())).thenReturn(ToxicityResult.safe());

        DecisionResult cleanResult = new DecisionResult(ModerationDecision.ALLOW, "Passed all checks", Map.of(), 1, "constructive feedback")
                .withPositiveObservation(1.0);
        when(decisionEngine.evaluate(eq(request), eq(303L), anyString(), any(ToxicityResult.class))).thenReturn(cleanResult);

        when(jdbcTemplate.queryForObject(contains("INSERT INTO content"), eq(Long.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(1002L);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO moderation_event"), eq(Object.class), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("evt-allow-2");

        ModerationResponse response = contentService.createContent(request, "key-reward");

        assertNotNull(response);
        assertEquals(ModerationDecision.ALLOW, response.getDecision());

        // Verify positive reward recorded via TrustCalculatorService
        verify(trustCalculatorService).recordPositiveObservation(eq(303L), eq("test_tenant"), eq(1.0));
        verify(trustCalculatorService, never()).recordInfraction(anyLong(), anyString(), anyString(), anyDouble(), anyString());
    }

    @Test
    void testGetUserTrustProfile_DelegatesToTrustCalculatorService() {
        com.shhmods.engine.trust.UserTrustProfile expected = new com.shhmods.engine.trust.UserTrustProfile(
            404L, "test_tenant",
            new com.shhmods.engine.trust.TrustDimension("SPAM", 10.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("HARASSMENT", 10.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("COORDINATION", 10.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("GENERAL_ABUSE", 10.0, 0.0),
            java.time.Instant.now(), java.time.Instant.now()
        );

        when(trustCalculatorService.getProfile(404L, "test_tenant")).thenReturn(expected);

        com.shhmods.engine.trust.UserTrustProfile result = contentService.getUserTrustProfile(404L, "test_tenant");

        assertSame(expected, result);
        verify(trustCalculatorService).getProfile(404L, "test_tenant");
    }
}
