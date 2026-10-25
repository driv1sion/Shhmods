package com.shhmods.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shhmods.engine.dto.ContentRequest;
import com.shhmods.engine.dto.DecisionResult;
import com.shhmods.engine.dto.ModerationDecision;
import com.shhmods.engine.service.DecisionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DecisionEngineTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private com.shhmods.engine.trust.TrustCalculatorService trustCalculatorService;

    private DecisionEngine decisionEngine;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        decisionEngine = new DecisionEngine(jdbcTemplate, objectMapper, new com.shhmods.engine.service.TextNormalizationService(), trustCalculatorService);
    }

    private void mockCleanBase() {
        com.shhmods.engine.trust.UserTrustProfile cleanProfile = new com.shhmods.engine.trust.UserTrustProfile(
            1L, "tenant1",
            new com.shhmods.engine.trust.TrustDimension("SPAM", 20.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("HARASSMENT", 20.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("COORDINATION", 20.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("GENERAL_ABUSE", 20.0, 0.0),
            java.time.Instant.now(), java.time.Instant.now()
        );
        lenient().when(trustCalculatorService.getProfile(anyLong(), anyString())).thenReturn(cleanProfile);
        lenient().when(jdbcTemplate.queryForObject(contains("burst_limit"), eq(Integer.class), any(String.class))).thenReturn(5);
        lenient().when(jdbcTemplate.queryForObject(contains("content_clusters"), eq(Integer.class), eq(1L), eq(1L))).thenReturn(0);
        lenient().when(jdbcTemplate.queryForObject(contains("account_created_at"), eq(Double.class), any(Long.class))).thenReturn(50.0);
        lenient().when(jdbcTemplate.queryForList(contains("tenant_policy"), any(String.class))).thenReturn(Collections.emptyList());
    }

    @Test
    void testAllowCleanContent() {
        ContentRequest req = new ContentRequest();
        req.setTenantId("tenant1");
        req.setContentText("Hello world!");

        mockCleanBase();
        when(jdbcTemplate.queryForObject(contains("INTERVAL '1 minute'"), eq(Integer.class), eq(1L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("INTERVAL '24 hours'"), eq(Integer.class), eq("hash1"))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("EXISTS"), eq(Boolean.class), eq("tenant1"), any(String.class), any(String.class), any(String.class), eq("tenant1"))).thenReturn(false);

        DecisionResult result = decisionEngine.evaluate(req, 1L, "hash1", false);

        assertEquals(ModerationDecision.ALLOW, result.getDecision());
    }

    @Test
    void testBlockOnLowTrustScore() {
        ContentRequest req = new ContentRequest();
        req.setTenantId("tenant1");
        req.setContentText("Hello world!");

        mockCleanBase();
        // Mock low spam trust score (< 25.0) with calibrated confidence
        com.shhmods.engine.trust.UserTrustProfile lowTrust = new com.shhmods.engine.trust.UserTrustProfile(
            1L, "tenant1",
            new com.shhmods.engine.trust.TrustDimension("SPAM", 2.0, 20.0),
            new com.shhmods.engine.trust.TrustDimension("HARASSMENT", 20.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("COORDINATION", 20.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("GENERAL_ABUSE", 20.0, 0.0),
            java.time.Instant.now(), java.time.Instant.now()
        );
        when(trustCalculatorService.getProfile(eq(1L), anyString())).thenReturn(lowTrust);

        DecisionResult result = decisionEngine.evaluate(req, 1L, "hash1", false);

        assertEquals(ModerationDecision.BLOCK, result.getDecision());
        assertEquals("User behavioral trust score too low", result.getReason());
    }

    @Test
    void testThrottleOnBurstRateExceeded() {
        ContentRequest req = new ContentRequest();
        req.setTenantId("tenant1");
        req.setContentText("Spam!");

        mockCleanBase();
        // 6 messages in last minute, limit is 5
        when(jdbcTemplate.queryForObject(contains("INTERVAL '1 minute'"), eq(Integer.class), eq(1L))).thenReturn(6);

        DecisionResult result = decisionEngine.evaluate(req, 1L, "hash2", false);

        assertEquals(ModerationDecision.THROTTLE, result.getDecision());
        assertEquals("Burst rate limit exceeded", result.getReason());
    }

    @Test
    void testBlockOnDuplicateContent() {
        ContentRequest req = new ContentRequest();
        req.setTenantId("tenant1");
        req.setContentText("Buy my crypto");

        mockCleanBase();
        when(jdbcTemplate.queryForObject(contains("INTERVAL '1 minute'"), eq(Integer.class), eq(1L))).thenReturn(0);
        // Duplicate found
        when(jdbcTemplate.queryForObject(contains("INTERVAL '24 hours'"), eq(Integer.class), eq("hash3"))).thenReturn(1);

        DecisionResult result = decisionEngine.evaluate(req, 1L, "hash3", false);

        assertEquals(ModerationDecision.BLOCK, result.getDecision());
        assertEquals("Duplicate content detected", result.getReason());
    }

    @Test
    void testReviewOnCoordinatedPosting() {
        ContentRequest req = new ContentRequest();
        req.setTenantId("tenant1");
        req.setContentText("Let's brigade them");

        mockCleanBase();
        when(jdbcTemplate.queryForObject(contains("INTERVAL '1 minute'"), eq(Integer.class), eq(1L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("INTERVAL '24 hours'"), eq(Integer.class), eq("hash_coord"))).thenReturn(0);
        
        // Coordination detected
        when(jdbcTemplate.queryForObject(contains("content_clusters"), eq(Integer.class), eq(1L), eq(1L))).thenReturn(3);

        DecisionResult result = decisionEngine.evaluate(req, 1L, "hash_coord", false);

        assertEquals(ModerationDecision.REVIEW, result.getDecision());
        assertEquals("Coordinated posting behavior detected", result.getReason());
    }

    @Test
    void testReviewOnBannedWord() {
        ContentRequest req = new ContentRequest();
        req.setTenantId("tenant1");
        req.setContentText("You are badword");

        mockCleanBase();
        when(jdbcTemplate.queryForObject(contains("INTERVAL '1 minute'"), eq(Integer.class), eq(1L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("INTERVAL '24 hours'"), eq(Integer.class), eq("hash4"))).thenReturn(0);
        
        // Banned word match
        when(jdbcTemplate.queryForObject(contains("EXISTS"), eq(Boolean.class), eq("tenant1"), eq("you are badword"), eq("you are badword"), eq("you are badword"), eq("tenant1"))).thenReturn(true);

        DecisionResult result = decisionEngine.evaluate(req, 1L, "hash4", false);

        assertEquals(ModerationDecision.REVIEW, result.getDecision());
        assertEquals("Banned keyword or fuzzy match detected", result.getReason());
    }
    
    @Test
    void testReviewOnToxicAI() {
        ContentRequest req = new ContentRequest();
        req.setTenantId("tenant1");
        req.setContentText("Something toxic");

        mockCleanBase();
        when(jdbcTemplate.queryForObject(contains("INTERVAL '1 minute'"), eq(Integer.class), eq(1L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("INTERVAL '24 hours'"), eq(Integer.class), eq("hash5"))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("EXISTS"), eq(Boolean.class), eq("tenant1"), eq("something toxic"), eq("something toxic"), eq("something toxic"), eq("tenant1"))).thenReturn(false);

        // isToxic = true
        DecisionResult result = decisionEngine.evaluate(req, 1L, "hash5", true);

        assertEquals(ModerationDecision.REVIEW, result.getDecision());
        assertEquals("AI flagged as toxic", result.getReason());
    }

    @Test
    void testBypassUrlFilterForTrustedOlderUser() {
        ContentRequest req = new ContentRequest();
        req.setTenantId("tenant1");
        req.setContentText("Check this out http://example.com");

        mockCleanBase();
        when(jdbcTemplate.queryForObject(contains("INTERVAL '1 minute'"), eq(Integer.class), eq(1L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("INTERVAL '24 hours'"), eq(Integer.class), eq("hash_url"))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("EXISTS"), eq(Boolean.class), eq("tenant1"), any(String.class), any(String.class), any(String.class), eq("tenant1"))).thenReturn(false);

        // The user has a slightly low spam trust (70% < 80%) which triggers URL review gating without policy
        com.shhmods.engine.trust.UserTrustProfile urlUser = new com.shhmods.engine.trust.UserTrustProfile(
            1L, "tenant1",
            new com.shhmods.engine.trust.TrustDimension("SPAM", 7.0, 3.0),
            new com.shhmods.engine.trust.TrustDimension("HARASSMENT", 20.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("COORDINATION", 20.0, 0.0),
            new com.shhmods.engine.trust.TrustDimension("GENERAL_ABUSE", 20.0, 0.0),
            java.time.Instant.now(), java.time.Instant.now()
        );
        when(trustCalculatorService.getProfile(eq(1L), anyString())).thenReturn(urlUser);

        // But we inject a policy to bypass it if account age > 30 and spam trust > 50
        Map<String, Object> policyRow = new HashMap<>();
        policyRow.put("rule_name", "URL_FILTER");
        policyRow.put("override_action", "BYPASS");
        policyRow.put("condition_json", "{\"min_spam_trust\": 50, \"min_account_age_days\": 30}");
        when(jdbcTemplate.queryForList(contains("tenant_policy"), eq("tenant1"))).thenReturn(List.of(policyRow));

        DecisionResult result = decisionEngine.evaluate(req, 1L, "hash_url", false);

        // Policy allows the bypass
        assertEquals(ModerationDecision.ALLOW, result.getDecision());
    }

    @Test
    void testBlockOnSevereAiThreat() {
        ContentRequest req = new ContentRequest();
        req.setTenantId("tenant1");
        req.setContentText("I will kill you");

        mockCleanBase();
        when(jdbcTemplate.queryForObject(contains("INTERVAL '1 minute'"), eq(Integer.class), eq(1L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("INTERVAL '24 hours'"), eq(Integer.class), eq("hash_threat"))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("EXISTS"), eq(Boolean.class), eq("tenant1"), any(String.class), any(String.class), any(String.class), eq("tenant1"))).thenReturn(false);

        com.shhmods.engine.ml.ToxicityResult toxicity = new com.shhmods.engine.ml.ToxicityResult(
                true,
                0.95,
                Map.of("threat", 0.95, "toxic", 0.98),
                List.of("threat", "toxic"),
                14L
        );

        DecisionResult result = decisionEngine.evaluate(req, 1L, "hash_threat", toxicity);

        // Severe threats result in BLOCK
        assertEquals(ModerationDecision.BLOCK, result.getDecision());
        assertEquals("HARASSMENT", result.getCompartment());
        assertEquals(25.0, result.getPenalty());
        assertTrue(result.getReason().contains("threat"));
    }
}
