package com.shhmods.engine.controller;

import com.shhmods.engine.dto.ContentRequest;
import com.shhmods.engine.dto.ModerationDecision;
import com.shhmods.engine.dto.ModerationResponse;
import com.shhmods.engine.service.ContentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/content")
public class ContentController {

    private static final Logger logger = LoggerFactory.getLogger(ContentController.class);
    
    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @PostMapping
    public ResponseEntity<?> createContent(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ContentRequest request) {

        try {
            ModerationResponse response = contentService.createContent(request, idempotencyKey);

            if (response.getDecision() == ModerationDecision.THROTTLE) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
            }

            HttpStatus status = response.isCached() ? HttpStatus.OK : HttpStatus.CREATED;
            return ResponseEntity.status(status).body(response);

        } catch (RuntimeException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (msg.contains("Burst rate limit exceeded") || msg.contains("Sustained rate limit exceeded")) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("status", "error", "message", "Rate limit exceeded by decision engine"));
            }
            logger.error("Error while evaluating content", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Internal Server Error"));
        }
    }
}

