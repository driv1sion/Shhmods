package com.shhmods.engine.dto;

import jakarta.validation.constraints.NotBlank;

public class ContentRequest {
    
    @NotBlank
    private String tenantId;
    
    @NotBlank
    private String externalUserId;
    
    @NotBlank
    private String username;
    
    @NotBlank
    private String email;
    
    @NotBlank
    private String contentText;
    
    @NotBlank
    private String contentType;

    // Getters and Setters
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    
    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }
    
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
}
