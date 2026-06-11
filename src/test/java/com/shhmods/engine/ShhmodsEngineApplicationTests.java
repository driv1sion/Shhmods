package com.shhmods.engine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Testcontainers
class ShhmodsEngineApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            "postgres:15-alpine"
    );

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

    @Test
    void contextLoads() {
        // Just checking if application context loads and Flyway migrations are applied without error.
        assertThat(postgres.isRunning()).isTrue();
    }

    @Test
    void verifyExtensionsCreated() {
        Integer pgTrgmCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
        assertThat(pgTrgmCount).isEqualTo(1);

        Integer fuzzyCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'fuzzystrmatch'", Integer.class);
        assertThat(fuzzyCount).isEqualTo(1);
    }

    @Test
    void verifyRolesCreated() {
        Integer appUserCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_roles WHERE rolname = 'app_user'", Integer.class);
        assertThat(appUserCount).isEqualTo(1);

        Integer adminCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_roles WHERE rolname = 'admin'", Integer.class);
        assertThat(adminCount).isEqualTo(1);
    }

    @Test
    void verifyTablesCreated() {
        Integer usersTableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'users'", Integer.class);
        assertThat(usersTableCount).isEqualTo(1);

        Integer contentTableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'content'", Integer.class);
        assertThat(contentTableCount).isEqualTo(1);
    }
}
