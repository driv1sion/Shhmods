package com.shhmods.engine.trust;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TrustCalculatorServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private TrustCalculatorService trustCalculatorService;

    @BeforeEach
    void setUp() {
        trustCalculatorService = new TrustCalculatorService(jdbcTemplate);
    }

    @Test
    void testTrustDimension_BetaBinomialCalculations() {
        // High evidence: alpha = 100, beta = 5
        TrustDimension dim = new TrustDimension("SPAM", 100.0, 5.0);
        assertEquals(100.0 / 105.0, dim.getScore(), 0.001);
        assertTrue(dim.getConfidence() > 0.90, "Confidence should be high with >100 observations");
        assertTrue(dim.isTrusted(0.80, 0.70));
        assertTrue(dim.hasSustainedGoodBehavior(50));

        // Low evidence (brand new account): alpha = 1, beta = 0
        TrustDimension newDim = new TrustDimension("SPAM", 1.0, 0.0);
        assertEquals(1.0, newDim.getScore());
        assertEquals(1.0 - (1.0 / Math.sqrt(2.0)), newDim.getConfidence(), 0.001);
        assertTrue(newDim.getConfidence() < 0.35, "Confidence should be under 35% for brand new account");
        assertFalse(newDim.isTrusted(0.80, 0.70), "Should not qualify as highly trusted due to high uncertainty");
        assertFalse(newDim.hasSustainedGoodBehavior(8), "Brand new account lacks sustained good behavior");
    }

    @Test
    void testPrivilegeGating_IdleAbuserCannotBypassUrlReview() {
        // Case A: Abusive user who idled for 6 months.
        // Penalty decayed, so score is high, but alpha is low (no sustained good behavior).
        TrustDimension decayedSpam = new TrustDimension("SPAM", 5.0, 0.5); // alpha = 5 (< 8 required)
        UserTrustProfile idleAbuser = new UserTrustProfile(
            1L, "tenant1",
            decayedSpam,
            new TrustDimension("HARASSMENT", 5.0, 0.0),
            new TrustDimension("COORDINATION", 5.0, 0.0),
            new TrustDimension("GENERAL_ABUSE", 5.0, 0.0),
            Instant.now(), Instant.now()
        );

        assertFalse(idleAbuser.canBypassUrlReview(), "Idle abuser must NOT gain URL privileges without sustained good behavior");

        // Case B: Active veteran user with sustained good behavior (alpha = 15 >= 8)
        TrustDimension activeSpam = new TrustDimension("SPAM", 15.0, 0.5);
        UserTrustProfile activeVeteran = new UserTrustProfile(
            2L, "tenant1",
            activeSpam,
            new TrustDimension("HARASSMENT", 15.0, 0.0),
            new TrustDimension("COORDINATION", 15.0, 0.0),
            new TrustDimension("GENERAL_ABUSE", 15.0, 0.0),
            Instant.now(), Instant.now()
        );

        assertTrue(activeVeteran.canBypassUrlReview(), "Active veteran user should be permitted to bypass URL review");
    }

    @Test
    void testRecordInfraction_IncreasesBetaAndLogsEvent() {
        trustCalculatorService.recordInfraction(10L, "tenant1", "HARASSMENT", 15.0, "Toxic comment");

        verify(jdbcTemplate).update(contains("UPDATE user_trust_snapshot"), eq(15.0), eq(10L));
        verify(jdbcTemplate).update(contains("INSERT INTO trust_event"), eq(10L), eq("HARASSMENT"), eq(15.0), eq(15.0));
    }

    @Test
    void testRecordPositiveObservation_IncrementsAlpha() {
        trustCalculatorService.recordPositiveObservation(10L, "tenant1", 2.0);

        verify(jdbcTemplate).update(contains("UPDATE user_trust_snapshot"), eq(2.0), eq(2.0), eq(10L));
    }

    @Test
    void testCompensateFalsePositive_ReducesBetaAndLogsReversal() {
        trustCalculatorService.compensateFalsePositive(10L, "tenant1", "SPAM", 10.0, "Appeal granted");

        verify(jdbcTemplate).update(contains("UPDATE user_trust_snapshot"), eq(10.0), eq(10L));
        verify(jdbcTemplate).update(contains("INSERT INTO trust_event"), eq(10L), eq("SPAM"), eq(-10.0), eq(10.0));
    }

    @Test
    void testGetProfile_AppliesTimeDecayDynamically() {
        // Setup row mapper to return profile with 10 days old penalty
        Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS);

        when(jdbcTemplate.query(contains("SELECT spam_alpha"), any(RowMapper.class), eq(99L)))
                .thenAnswer(invocation -> {
                    RowMapper<UserTrustProfile> mapper = invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getDouble("decay_rate")).thenReturn(0.0000005);
                    when(rs.getDouble("spam_alpha")).thenReturn(10.0);
                    when(rs.getDouble("spam_beta")).thenReturn(20.0);
                    when(rs.getTimestamp("spam_last_penalty_at")).thenReturn(Timestamp.from(tenDaysAgo));

                    when(rs.getDouble("harassment_alpha")).thenReturn(10.0);
                    when(rs.getDouble("harassment_beta")).thenReturn(0.0);
                    when(rs.getTimestamp("harassment_last_penalty_at")).thenReturn(null);

                    when(rs.getDouble("coordination_alpha")).thenReturn(10.0);
                    when(rs.getDouble("coordination_beta")).thenReturn(0.0);
                    when(rs.getTimestamp("coordination_last_penalty_at")).thenReturn(null);

                    when(rs.getDouble("general_alpha")).thenReturn(10.0);
                    when(rs.getDouble("general_beta")).thenReturn(0.0);
                    when(rs.getTimestamp("general_last_penalty_at")).thenReturn(null);

                    return List.of(mapper.mapRow(rs, 1));
                });

        UserTrustProfile profile = trustCalculatorService.getProfile(99L, "tenant1");

        assertNotNull(profile);
        // Original beta was 20.0; after 10 days (864,000s) with lambda 0.0000005:
        // decay factor = exp(-0.0000005 * 864000) = exp(-0.432) ~ 0.649
        // Decayed beta should be approx 13.0 (< 20.0)
        assertTrue(profile.getSpam().getBeta() < 20.0, "Spam penalty should have decayed over 10 days");
        assertTrue(profile.getSpam().getBeta() > 10.0, "Spam penalty should not have vanished completely");
    }

    @Test
    void testGetProfile_FallbackReturnsDefaultUncertainProfile() {
        when(jdbcTemplate.query(contains("SELECT spam_alpha"), any(RowMapper.class), eq(888L)))
                .thenReturn(List.of());

        UserTrustProfile profile = trustCalculatorService.getProfile(888L, "tenant1");

        assertNotNull(profile);
        assertEquals(888L, profile.getUserId());
        assertEquals("tenant1", profile.getTenantId());
        assertEquals(1.0, profile.getSpam().getAlpha());
        assertEquals(0.0, profile.getSpam().getBeta());
        assertEquals(1.0, profile.getSpam().getScore());
        assertTrue(profile.getSpam().getConfidence() < 0.35);
        assertFalse(profile.canBypassUrlReview());
    }
}
