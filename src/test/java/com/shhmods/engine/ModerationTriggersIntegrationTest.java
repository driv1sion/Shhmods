package com.shhmods.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class ModerationTriggersIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE users CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE banned_word CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE outbox_event CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE audit_log CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE tenant_config CASCADE");
        jdbcTemplate.execute("INSERT INTO tenant_config (tenant_id, burst_limit, sustained_limit, trgm_similarity_threshold, shadow_ban_threshold) VALUES ('GLOBAL', 3, 10, 0.1, 10)");
    }

    private Long createUser(String extId) {
        jdbcTemplate.update("INSERT INTO users (tenant_id, external_user_id, username, email) VALUES ('GLOBAL', ?, 'testuser', 'test@test.com')", extId);
        return jdbcTemplate.queryForObject("SELECT user_id FROM users WHERE external_user_id = ?", Long.class, extId);
    }

    @Test
    void shouldTriggerBurstRateLimit() {
        Long userId = createUser("u1");

        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update("INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash) VALUES ('GLOBAL', ?, 'Test content', 'POST', ?)", userId, "hash" + i);
        }

        assertThatThrownBy(() -> 
            jdbcTemplate.update("INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash) VALUES ('GLOBAL', ?, 'Test content', 'POST', ?)", userId, "hash_fail")
        ).hasMessageContaining("Burst rate limit exceeded");
    }

    @Test
    void shouldFlagBannedWord() {
        Long userId = createUser("u2");
        
        jdbcTemplate.update("INSERT INTO banned_word (tenant_id, keyword, match_type) VALUES ('GLOBAL', 'badword', 'EXACT_WORD_ONLY')");
        
        jdbcTemplate.update("INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash) VALUES ('GLOBAL', ?, 'This is a badword post', 'POST', 'hash_banned')", userId);
        
        // Assert it was inserted but flagged (is_visible = FALSE, and outbox event created)
        Boolean isVisible = jdbcTemplate.queryForObject("SELECT is_visible FROM content WHERE content_hash = 'hash_banned'", Boolean.class);
        assertThat(isVisible).isFalse();

        List<Map<String, Object>> outboxEvents = jdbcTemplate.queryForList("SELECT * FROM outbox_event WHERE aggregate_id = 'hash_banned'");
        assertThat(outboxEvents).hasSize(1);
        assertThat(outboxEvents.get(0).get("event_type")).isEqualTo("CONTENT_FLAGGED");
    }

    @Test
    void shouldFlagFuzzyBannedWord() {
        Long userId = createUser("u3");
        
        jdbcTemplate.update("INSERT INTO banned_word (tenant_id, keyword, match_type) VALUES ('GLOBAL', 'bannedword', 'SUBSTRING_ALLOWED')");
        
        jdbcTemplate.update("INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash) VALUES ('GLOBAL', ?, 'b@nnedw0rd', 'POST', 'hash_fuzzy')", userId);
        
        Boolean isVisible = jdbcTemplate.queryForObject("SELECT is_visible FROM content WHERE content_hash = 'hash_fuzzy'", Boolean.class);
        assertThat(isVisible).isFalse();
    }
    
    @Test
    void shouldFlagDuplicateContent() {
        Long userId = createUser("u4");
        
        jdbcTemplate.update("INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash) VALUES ('GLOBAL', ?, 'Duplicate me', 'POST', 'hash_dup')", userId);
        jdbcTemplate.update("INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash) VALUES ('GLOBAL', ?, 'Duplicate me', 'POST', 'hash_dup')", userId);
        
        // Only one should be marked true maybe? Wait, first one is TRUE, second is FALSE
        List<Boolean> visibilities = jdbcTemplate.queryForList("SELECT is_visible FROM content WHERE content_hash = 'hash_dup' ORDER BY content_id", Boolean.class);
        assertThat(visibilities).hasSize(2);
        assertThat(visibilities.get(0)).isTrue();
        assertThat(visibilities.get(1)).isFalse();
    }

    @Test
    void testAuditLogImmutability() {
        jdbcTemplate.update("INSERT INTO audit_log (action_type, reference_type, reference_id) VALUES ('TEST_ACTION', 'TEST', '123')");
        
        assertThatThrownBy(() -> 
            jdbcTemplate.update("UPDATE audit_log SET action_type = 'MODIFIED'")
        ).hasMessageContaining("Audit log is immutable");
        
        assertThatThrownBy(() -> 
            jdbcTemplate.update("DELETE FROM audit_log")
        ).hasMessageContaining("Audit log is immutable");
    }

    @Test
    void testTrustScoreShadowBan() {
        Long userId = createUser("u5");
        
        // Initial insert to trigger something or call the procedure manually.
        // We will just call the procedure manually to test the shadow ban logic.
        Long contentId = jdbcTemplate.queryForObject("INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash) VALUES ('GLOBAL', ?, 'dummy', 'POST', 'hash_ts') RETURNING content_id", Long.class, userId);
        jdbcTemplate.update("INSERT INTO content_flag (content_id, flag_type, reason, status) VALUES (?, 'TEST', 'test', 'RESOLVED')", contentId);
        
        Long flagId = jdbcTemplate.queryForObject("SELECT flag_id FROM content_flag LIMIT 1", Long.class);
        
        jdbcTemplate.update("INSERT INTO violation (flag_id, user_id, violation_type, severity_level) VALUES (?, ?, 'TEST', 100)", flagId, userId);
        
        jdbcTemplate.execute("SELECT recalculate_trust_score(" + userId + ")");
        
        Boolean isVisible = jdbcTemplate.queryForObject("SELECT is_visible FROM users WHERE user_id = ?", Boolean.class, userId);
        assertThat(isVisible).isFalse(); // Should be shadow banned because severity 100 drops score below 10
    }
}
