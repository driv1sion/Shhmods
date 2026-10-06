package com.shhmods.engine.dto;

import java.util.Map;

public class DecisionResult {
    private ModerationDecision decision;
    private String reason;
    private Map<String, Object> signals;
    private int policyVersion;
    private String normalizedText;
    
    // Trust fields
    private String compartment;
    private Double penalty;
    private Double decayRate;

    public DecisionResult(ModerationDecision decision, String reason, Map<String, Object> signals, int policyVersion, String normalizedText) {
        this.decision = decision;
        this.reason = reason;
        this.signals = signals;
        this.policyVersion = policyVersion;
        this.normalizedText = normalizedText;
    }

    public DecisionResult withTrustPenalty(String compartment, Double penalty, Double decayRate) {
        this.compartment = compartment;
        this.penalty = penalty;
        this.decayRate = decayRate;
        return this;
    }

    public ModerationDecision getDecision() { return decision; }
    public String getReason() { return reason; }
    public Map<String, Object> getSignals() { return signals; }
    public int getPolicyVersion() { return policyVersion; }
    public String getNormalizedText() { return normalizedText; }
    public String getCompartment() { return compartment; }
    public Double getPenalty() { return penalty; }
    public Double getDecayRate() { return decayRate; }
}
