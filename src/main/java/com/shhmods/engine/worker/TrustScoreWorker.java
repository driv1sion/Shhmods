package com.shhmods.engine.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrustScoreWorker {

    private static final Logger logger = LoggerFactory.getLogger(TrustScoreWorker.class);
    private final JdbcTemplate jdbcTemplate;

    public TrustScoreWorker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Trust score decay job: +1 point per week of good behavior.
     */
    @Scheduled(cron = "0 0 0 * * SUN") // Run every Sunday at midnight
    public void applyTrustScoreDecay() {
        logger.info("Running Weekly Trust Score Decay Job...");
        
        // Increase trust score by 1 for users who haven't had a violation recently, cap at 100
        int updated = jdbcTemplate.update("""
            UPDATE trust_score 
            SET trust_score = LEAST(trust_score + 1.0, 100.0), last_updated = NOW()
            WHERE trust_score < 100.0
            """);
            
        logger.info("Applied trust score decay to {} users", updated);
    }
}
