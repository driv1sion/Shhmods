package com.shhmods.engine.trust;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrustDimension {

    private String name;
    private double score;        // Expected trust: alpha / (alpha + beta)
    private double confidence;   // Information certainty: 1 - 1/sqrt(alpha + beta + 1)
    private double alpha;        // Positive evidence / prior
    private double beta;         // Decayed infraction weight
    private int observations;    // Total effective observations

    public TrustDimension() {}

    public TrustDimension(String name, double alpha, double beta) {
        this.name = name;
        this.alpha = Math.max(0.001, alpha);
        this.beta = Math.max(0.0, beta);
        this.score = this.alpha / (this.alpha + this.beta);
        double total = this.alpha + this.beta;
        this.confidence = Math.max(0.0, Math.min(1.0, 1.0 - (1.0 / Math.sqrt(total + 1.0))));
        this.observations = (int) Math.round(total);
    }

    public boolean isTrusted(double minScore, double minConfidence) {
        return this.score >= minScore && this.confidence >= minConfidence;
    }

    public boolean hasSustainedGoodBehavior(int minPositiveInteractions) {
        return this.alpha >= minPositiveInteractions;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public double getAlpha() { return alpha; }
    public void setAlpha(double alpha) { this.alpha = alpha; }

    public double getBeta() { return beta; }
    public void setBeta(double beta) { this.beta = beta; }

    public int getObservations() { return observations; }
    public void setObservations(int observations) { this.observations = observations; }
}
