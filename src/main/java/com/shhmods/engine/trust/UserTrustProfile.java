package com.shhmods.engine.trust;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserTrustProfile {

    private Long userId;
    private String tenantId;
    private TrustDimension spam;
    private TrustDimension harassment;
    private TrustDimension coordination;
    private TrustDimension generalAbuse;
    private Instant lastActiveAt;
    private Instant updatedAt;

    public UserTrustProfile() {}

    public UserTrustProfile(Long userId, String tenantId, 
                            TrustDimension spam, 
                            TrustDimension harassment, 
                            TrustDimension coordination, 
                            TrustDimension generalAbuse,
                            Instant lastActiveAt, 
                            Instant updatedAt) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.spam = spam;
        this.harassment = harassment;
        this.coordination = coordination;
        this.generalAbuse = generalAbuse;
        this.lastActiveAt = lastActiveAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Checks if user has high trust and sustained good behavior to bypass URL review.
     * Prevents idle abusers from automatically recovering sensitive privileges.
     */
    public boolean canBypassUrlReview() {
        return spam.getScore() >= 0.80 && spam.getConfidence() >= 0.55 && spam.hasSustainedGoodBehavior(8);
    }

    /**
     * Checks if user is an established positive contributor eligible for progressive friction rather than hard blocks.
     */
    public boolean isEstablishedPositiveContributor() {
        return harassment.getScore() >= 0.75 && harassment.getConfidence() >= 0.60 && harassment.hasSustainedGoodBehavior(10);
    }

    /**
     * Checks if user has active, un-decayed coordination penalties.
     */
    public boolean hasCoordinationRisk() {
        return coordination.getBeta() >= 3.0 || coordination.getScore() < 0.65;
    }

    public Map<String, Object> toSignalsMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("trust_spam_score", Math.round(spam.getScore() * 1000.0) / 1000.0);
        map.put("trust_spam_confidence", Math.round(spam.getConfidence() * 1000.0) / 1000.0);
        map.put("trust_harassment_score", Math.round(harassment.getScore() * 1000.0) / 1000.0);
        map.put("trust_harassment_confidence", Math.round(harassment.getConfidence() * 1000.0) / 1000.0);
        map.put("trust_coordination_score", Math.round(coordination.getScore() * 1000.0) / 1000.0);
        map.put("trust_coordination_confidence", Math.round(coordination.getConfidence() * 1000.0) / 1000.0);
        map.put("trust_general_score", Math.round(generalAbuse.getScore() * 1000.0) / 1000.0);
        map.put("trust_general_confidence", Math.round(generalAbuse.getConfidence() * 1000.0) / 1000.0);
        return map;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public TrustDimension getSpam() { return spam; }
    public void setSpam(TrustDimension spam) { this.spam = spam; }

    public TrustDimension getHarassment() { return harassment; }
    public void setHarassment(TrustDimension harassment) { this.harassment = harassment; }

    public TrustDimension getCoordination() { return coordination; }
    public void setCoordination(TrustDimension coordination) { this.coordination = coordination; }

    public TrustDimension getGeneralAbuse() { return generalAbuse; }
    public void setGeneralAbuse(TrustDimension generalAbuse) { this.generalAbuse = generalAbuse; }

    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
