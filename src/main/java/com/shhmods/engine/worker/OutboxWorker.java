package com.shhmods.engine.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Component
public class OutboxWorker {

    private static final Logger logger = LoggerFactory.getLogger(OutboxWorker.class);
    private final JdbcTemplate jdbcTemplate;

    public OutboxWorker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processOutboxEvents() {
        // Fetch up to 50 unprocessed events atomically using SKIP LOCKED
        String sql = """
            SELECT event_id, event_type, payload, aggregate_id
            FROM outbox_event 
            WHERE processed_at IS NULL 
            ORDER BY created_at ASC 
            LIMIT 50 
            FOR UPDATE SKIP LOCKED
            """;

        List<Map<String, Object>> events = jdbcTemplate.queryForList(sql);

        if (events.isEmpty()) {
            return;
        }

        logger.info("Processing {} outbox events...", events.size());

        for (Map<String, Object> event : events) {
            Long eventId = (Long) event.get("event_id");
            String eventType = (String) event.get("event_type");
            String payload = (String) event.get("payload");
            
            try {
                processEvent(eventType, payload);
                
                // Mark as processed
                jdbcTemplate.update("UPDATE outbox_event SET processed_at = NOW() WHERE event_id = ?", eventId);
            } catch (Exception e) {
                logger.error("Failed to process event_id: {}", eventId, e);
                // Optionally handle retries or dead-letter queue here
            }
        }
    }

    private void processEvent(String eventType, String payload) {
        // Here we would integrate with the ML sidecar, execute ML inference, 
        // generate vector embeddings for flagged content, 
        // or send external webhook notifications.
        logger.info("Handled event type {}: {}", eventType, payload);
        
        if ("CONTENT_FLAGGED".equals(eventType)) {
            // E.g., send push notification to admin dashboard or invoke vector embedding
        }
    }
}
