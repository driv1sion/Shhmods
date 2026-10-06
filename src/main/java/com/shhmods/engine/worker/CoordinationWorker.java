package com.shhmods.engine.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CoordinationWorker {

    private static final Logger logger = LoggerFactory.getLogger(CoordinationWorker.class);
    private final JdbcTemplate jdbcTemplate;

    public CoordinationWorker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Run every 5 minutes
    @Scheduled(fixedRate = 300000)
    public void refreshCoordinationClusters() {
        logger.info("Refreshing coordination_clusters and content_clusters materialized views...");
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW coordination_clusters");
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW content_clusters");
            logger.info("Successfully refreshed coordination_clusters and content_clusters.");
        } catch (Exception e) {
            logger.error("Failed to refresh materialized views", e);
        }
    }
}
