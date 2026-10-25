package com.shhmods.engine.trust;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class TrustCalculatorService {

    private static final Logger logger = LoggerFactory.getLogger(TrustCalculatorService.class);

    private final JdbcTemplate jdbcTemplate;

    public TrustCalculatorService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Retrieves the user's multidimensional trust profile, calculating asymmetric time decay on penalties dynamically.
     */
    public UserTrustProfile getProfile(Long userId, String tenantId) {
        ensureSnapshotExists(userId, tenantId);

        String sql = """
            SELECT spam_alpha, spam_beta, spam_last_penalty_at,
                   harassment_alpha, harassment_beta, harassment_last_penalty_at,
                   coordination_alpha, coordination_beta, coordination_last_penalty_at,
                   general_alpha, general_beta, general_last_penalty_at,
                   decay_rate, last_active_at, updated_at
            FROM user_trust_snapshot
            WHERE user_id = ?
            """;

        List<UserTrustProfile> profiles = jdbcTemplate.query(sql, (rs, rowNum) -> mapProfileRow(rs, userId, tenantId), userId);
        if (!profiles.isEmpty()) {
            return profiles.get(0);
        }

        // Fallback default profile if table query fails
        return createDefaultProfile(userId, tenantId);
    }

    /**
     * Records an infraction penalty in the specific behavioral compartment and logs to trust_event.
     */
    public void recordInfraction(Long userId, String tenantId, String dimension, double penaltyWeight, String reason) {
        String columnPrefix = dimension.toLowerCase();
        if ("coordination".equals(columnPrefix)) {
            columnPrefix = "coordination";
        } else if ("harassment".equals(columnPrefix)) {
            columnPrefix = "harassment";
        } else if ("spam".equals(columnPrefix)) {
            columnPrefix = "spam";
        } else {
            columnPrefix = "general";
        }

        String updateSql = String.format("""
            UPDATE user_trust_snapshot
            SET %s_beta = %s_beta + ?,
                %s_last_penalty_at = NOW(),
                last_active_at = NOW(),
                updated_at = NOW()
            WHERE user_id = ?
            """, columnPrefix, columnPrefix, columnPrefix);

        jdbcTemplate.update(updateSql, penaltyWeight, userId);

        String eventSql = """
            INSERT INTO trust_event (user_id, compartment, penalty, decay_rate, event_type, evidence_weight)
            VALUES (?, ?, ?, 0.0000005, 'INFRACTION', ?)
            """;
        jdbcTemplate.update(eventSql, userId, dimension.toUpperCase(), penaltyWeight, penaltyWeight);
    }

    /**
     * Rewards sustained positive participation (increments alpha), which is required to unlock sensitive privileges.
     */
    public void recordPositiveObservation(Long userId, String tenantId, double rewardWeight) {
        String updateSql = """
            UPDATE user_trust_snapshot
            SET spam_alpha = spam_alpha + ?,
                general_alpha = general_alpha + ?,
                last_active_at = NOW(),
                updated_at = NOW()
            WHERE user_id = ?
            """;
        jdbcTemplate.update(updateSql, rewardWeight, rewardWeight, userId);
    }

    /**
     * Reverses a wrongful penalty on successful user appeal without erasing the audit record.
     */
    public void compensateFalsePositive(Long userId, String tenantId, String dimension, double rollbackWeight, String reason) {
        String columnPrefix = dimension.toLowerCase();
        if (!List.of("spam", "harassment", "coordination", "general").contains(columnPrefix)) {
            columnPrefix = "general";
        }

        String updateSql = String.format("""
            UPDATE user_trust_snapshot
            SET %s_beta = GREATEST(0.0, %s_beta - ?),
                updated_at = NOW()
            WHERE user_id = ?
            """, columnPrefix, columnPrefix);

        jdbcTemplate.update(updateSql, rollbackWeight, userId);

        String eventSql = """
            INSERT INTO trust_event (user_id, compartment, penalty, decay_rate, event_type, evidence_weight)
            VALUES (?, ?, ?, 0.0, 'REVERSAL', ?)
            """;
        jdbcTemplate.update(eventSql, userId, dimension.toUpperCase(), -rollbackWeight, rollbackWeight);
    }

    private void ensureSnapshotExists(Long userId, String tenantId) {
        try {
            String insertSql = """
                INSERT INTO user_trust_snapshot (user_id, tenant_id, spam_alpha, spam_beta, harassment_alpha, harassment_beta, coordination_alpha, coordination_beta, general_alpha, general_beta, decay_rate)
                VALUES (?, ?, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0000005)
                ON CONFLICT (user_id) DO NOTHING
                """;
            jdbcTemplate.update(insertSql, userId, tenantId);
        } catch (DataAccessException e) {
            logger.warn("Could not ensure snapshot exists for user {}: {}", userId, e.getMessage());
        }
    }

    private UserTrustProfile mapProfileRow(ResultSet rs, Long userId, String tenantId) throws SQLException {
        double decayRate = rs.getDouble("decay_rate");
        Instant now = Instant.now();

        // Spam
        double spamAlpha = rs.getDouble("spam_alpha");
        double spamBeta = applyDecay(rs.getDouble("spam_beta"), rs.getTimestamp("spam_last_penalty_at"), now, decayRate);

        // Harassment
        double harassAlpha = rs.getDouble("harassment_alpha");
        double harassBeta = applyDecay(rs.getDouble("harassment_beta"), rs.getTimestamp("harassment_last_penalty_at"), now, decayRate);

        // Coordination
        double coordAlpha = rs.getDouble("coordination_alpha");
        double coordBeta = applyDecay(rs.getDouble("coordination_beta"), rs.getTimestamp("coordination_last_penalty_at"), now, decayRate);

        // General
        double generalAlpha = rs.getDouble("general_alpha");
        double generalBeta = applyDecay(rs.getDouble("general_beta"), rs.getTimestamp("general_last_penalty_at"), now, decayRate);

        Timestamp lastActiveTs = rs.getTimestamp("last_active_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");

        return new UserTrustProfile(
            userId,
            tenantId,
            new TrustDimension("SPAM", spamAlpha, spamBeta),
            new TrustDimension("HARASSMENT", harassAlpha, harassBeta),
            new TrustDimension("COORDINATION", coordAlpha, coordBeta),
            new TrustDimension("GENERAL_ABUSE", generalAlpha, generalBeta),
            lastActiveTs != null ? lastActiveTs.toInstant() : now,
            updatedTs != null ? updatedTs.toInstant() : now
        );
    }

    private double applyDecay(double beta, Timestamp lastPenaltyAt, Instant now, double decayRate) {
        if (beta <= 0.0 || lastPenaltyAt == null) {
            return 0.0;
        }
        long deltaSeconds = Math.max(0, now.getEpochSecond() - lastPenaltyAt.toInstant().getEpochSecond());
        return beta * Math.exp(-decayRate * deltaSeconds);
    }

    private UserTrustProfile createDefaultProfile(Long userId, String tenantId) {
        Instant now = Instant.now();
        return new UserTrustProfile(
            userId,
            tenantId,
            new TrustDimension("SPAM", 1.0, 0.0),
            new TrustDimension("HARASSMENT", 1.0, 0.0),
            new TrustDimension("COORDINATION", 1.0, 0.0),
            new TrustDimension("GENERAL_ABUSE", 1.0, 0.0),
            now,
            now
        );
    }
}
