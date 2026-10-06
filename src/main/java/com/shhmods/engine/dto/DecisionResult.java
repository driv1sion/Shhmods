package com.shhmods.engine.dto;

import java.util.Map;

public class DecisionResult {
    private ModerationDecision decision;
    private String reason;
    private Map<String, Object> signals;
    private int policyVersion;
    private String normalizedText;

    public DecisionResult(ModerationDecision decision, String reason, Map<String, Object> signals, int policyVersion, String normalizedText) {
        this.decision = decision;
        this.reason = reason;
        this.signals = signals;
        this.policyVersion = policyVersion;
        this.normalizedText = normalizedText;
    }

    public ModerationDecision getDecision() { return decision; }
    public String getReason() { return reason; }
    public Map<String, Object> getSignals() { return signals; }
    public int getPolicyVersion() { return policyVersion; }
    public String getNormalizedText() { return normalizedText; }
}
