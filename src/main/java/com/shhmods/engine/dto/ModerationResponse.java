package com.shhmods.engine.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModerationResponse {

    private Long contentId;
    private ModerationDecision decision;
    private String reason;
    private Map<String, Object> signals;
    private String eventId;
    private boolean cached;
    private String timestamp;

    public ModerationResponse() {}

    public ModerationResponse(Long contentId, ModerationDecision decision, String reason,
                              Map<String, Object> signals, String eventId, boolean cached, String timestamp) {
        this.contentId = contentId;
        this.decision = decision;
        this.reason = reason;
        this.signals = signals;
        this.eventId = eventId;
        this.cached = cached;
        this.timestamp = timestamp;
    }

    public Long getContentId() { return contentId; }
    public void setContentId(Long contentId) { this.contentId = contentId; }

    public ModerationDecision getDecision() { return decision; }
    public void setDecision(ModerationDecision decision) { this.decision = decision; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Map<String, Object> getSignals() { return signals; }
    public void setSignals(Map<String, Object> signals) { this.signals = signals; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public boolean isCached() { return cached; }
    public void setCached(boolean cached) { this.cached = cached; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
