package com.shhmods.engine.controller;

import com.shhmods.engine.dto.ContentRequest;
import com.shhmods.engine.service.ContentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1/content")
public class ContentController {

    private static final Logger logger = LoggerFactory.getLogger(ContentController.class);
    
    private final ContentService contentService;
    // In-memory cache for idempotency keys (in prod use Redis/Memcached)
    private final Map<String, Long> idempotencyCache = new ConcurrentHashMap<>();

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @PostMapping
    public ResponseEntity<?> createContent(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContentRequest request) {

        if (idempotencyKey != null && idempotencyCache.containsKey(idempotencyKey)) {
            logger.info("Idempotent request received for key: {}", idempotencyKey);
            return ResponseEntity.ok().body(Map.of("status", "success", "content_id", idempotencyCache.get(idempotencyKey), "cached", true));
        }

        try {
            Long contentId = contentService.createContent(request);
            
            if (idempotencyKey != null) {
                idempotencyCache.put(idempotencyKey, contentId);
            }
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("status", "success", "content_id", contentId));
                    
        } catch (RuntimeException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (msg.contains("Burst rate limit exceeded") || msg.contains("Sustained rate limit exceeded")) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("status", "error", "message", "Rate limit exceeded by decision engine"));
            }
            logger.error("Error while creating content", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Internal Server Error"));
        }
    }
}
