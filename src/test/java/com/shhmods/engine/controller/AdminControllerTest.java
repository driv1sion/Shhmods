package com.shhmods.engine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shhmods.engine.ml.OnnxClassifierService;
import com.shhmods.engine.service.ContentService;
import com.shhmods.engine.trust.TrustDimension;
import com.shhmods.engine.trust.UserTrustProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AdminControllerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private OnnxClassifierService onnxClassifierService;

    @Mock
    private ContentService contentService;

    private ObjectMapper objectMapper;
    private AdminController adminController;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adminController = new AdminController(jdbcTemplate, objectMapper, onnxClassifierService, contentService);
    }

    @Test
    void testGetUserTrust_ReturnsProfile() {
        UserTrustProfile profile = new UserTrustProfile(
            42L, "tenant_a",
            new TrustDimension("SPAM", 12.0, 1.0),
            new TrustDimension("HARASSMENT", 15.0, 0.0),
            new TrustDimension("COORDINATION", 8.0, 0.0),
            new TrustDimension("GENERAL_ABUSE", 10.0, 0.0),
            Instant.now(), Instant.now()
        );

        when(contentService.getUserTrustProfile(42L, "tenant_a")).thenReturn(profile);

        ResponseEntity<?> response = adminController.getUserTrust(42L, "tenant_a");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(profile, response.getBody());
        verify(contentService).getUserTrustProfile(42L, "tenant_a");
    }

    @Test
    void testGetTrustSummary_QueriesView() {
        Map<String, Object> row = Map.of(
            "user_id", 42L,
            "username", "test_user",
            "spam_score", 0.92,
            "spam_confidence", 0.72
        );
        when(jdbcTemplate.queryForList(contains("vw_user_trust_summary"))).thenReturn(List.of(row));

        List<Map<String, Object>> summary = adminController.getTrustSummary();

        assertNotNull(summary);
        assertEquals(1, summary.size());
        assertEquals(42L, summary.get(0).get("user_id"));
        verify(jdbcTemplate).queryForList(contains("vw_user_trust_summary"));
    }

    @Test
    void testRedactContent_Success() {
        when(contentService.redactContent(eq(100L), eq("GDPR erasure"))).thenReturn(true);

        ResponseEntity<?> response = adminController.redactContent(100L, Map.of("reason", "GDPR erasure"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("SUCCESS"));
        verify(contentService).redactContent(100L, "GDPR erasure");
    }

    @Test
    void testRedactContent_NotFound() {
        when(contentService.redactContent(eq(999L), anyString())).thenReturn(false);

        ResponseEntity<?> response = adminController.redactContent(999L, Map.of("reason", "Missing"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
