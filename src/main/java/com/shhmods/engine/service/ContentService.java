package com.shhmods.engine.service;

import com.shhmods.engine.dto.ContentRequest;
import com.shhmods.engine.ml.OnnxClassifierService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

@Service
public class ContentService {

    private final JdbcTemplate jdbcTemplate;
    private final OnnxClassifierService onnxClassifierService;

    public ContentService(JdbcTemplate jdbcTemplate, OnnxClassifierService onnxClassifierService) {
        this.jdbcTemplate = jdbcTemplate;
        this.onnxClassifierService = onnxClassifierService;
    }

    @Transactional
    public Long createContent(ContentRequest request) {
        // 1. Ensure User exists (or insert if not exists)
        String userSql = """
            INSERT INTO users (tenant_id, external_user_id, username, email)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, external_user_id) DO UPDATE SET username = EXCLUDED.username
            RETURNING user_id
            """;
        
        Long userId = jdbcTemplate.queryForObject(userSql, Long.class, 
            request.getTenantId(), request.getExternalUserId(), request.getUsername(), request.getEmail());

        // 2. Run ONNX Toxicity Check
        if (onnxClassifierService.isToxic(request.getContentText())) {
            // Depending on requirements, we can auto-flag here or drop the request.
            // For now, we'll proceed and let the SQL triggers handle standard rules,
            // or we could flag it manually by inserting a content_flag.
        }

        // 3. Generate Hash
        String hash = generateHash(request.getContentText());

        // 4. Insert Content (Triggers will handle the rest)
        String contentSql = """
            INSERT INTO content (tenant_id, user_id, content_text, content_type, content_hash)
            VALUES (?, ?, ?, ?, ?)
            RETURNING content_id
            """;
            
        return jdbcTemplate.queryForObject(contentSql, Long.class,
            request.getTenantId(), userId, request.getContentText(), request.getContentType(), hash);
    }
    
    private String generateHash(String text) {
        try {
            // Strip whitespaces, punctuation, convert to lowercase as per PRD
            String normalized = text.toLowerCase().replaceAll("[\\\\s\\\\p{Punct}]", "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
