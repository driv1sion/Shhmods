package com.shhmods.engine.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BotnetVarianceWorker {

    private static final Logger logger = LoggerFactory.getLogger(BotnetVarianceWorker.class);
    private final JdbcTemplate jdbcTemplate;

    public BotnetVarianceWorker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * SQL Variance Check: A Spring @Scheduled job queries report and users and computes 
     * STDDEV of reporter account ages and trust scores for any target with 5+ reports in a short window. 
     * Anomalously low variance (all reporters created at the same time) triggers a flag.
     */
    @Scheduled(fixedRate = 60000) // Run every minute
    public void detectBotnets() {
        logger.debug("Running SQL Variance Botnet Check...");
        
        // This is a simplified example of the variance check.
        // It looks for content with > 5 reports in the last hour and checks variance of reporter creation times.
        String sql = """
            SELECT r.content_id, COUNT(r.report_id) as report_count, 
                   STDDEV(EXTRACT(EPOCH FROM u.account_created_at)) as account_age_variance
            FROM report r
            JOIN users u ON r.reporter_id = u.user_id
            WHERE r.reported_at > NOW() - INTERVAL '1 hour'
            GROUP BY r.content_id
            HAVING COUNT(r.report_id) >= 5
            """;
            
        List<Map<String, Object>> suspiciousTargets = jdbcTemplate.queryForList(sql);
        
        for (Map<String, Object> target : suspiciousTargets) {
            Number variance = (Number) target.get("account_age_variance");
            if (variance != null && variance.doubleValue() < 60.0) { // < 60 seconds variance = highly suspicious
                Long contentId = (Long) target.get("content_id");
                logger.warn("BOTNET DETECTED for content_id {}. Variance: {}", contentId, variance);
                
                // Flag the content if not already flagged
                try {
                    jdbcTemplate.update("""
                        INSERT INTO content_flag (content_id, flag_type, reason, status) 
                        VALUES (?, 'BOTNET_ATTACK', 'Anomalous reporting variance detected via SQL check', 'PENDING')
                        ON CONFLICT DO NOTHING
                        """, contentId);
                } catch (Exception e) {
                    logger.error("Failed to insert botnet flag", e);
                }
            }
        }
    }
}
