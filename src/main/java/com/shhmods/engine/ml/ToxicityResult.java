package com.shhmods.engine.ml;

import java.util.List;
import java.util.Map;

/**
 * Result of zero-shot text toxicity classification.
 */
public record ToxicityResult(
    boolean isToxic,
    double toxicityScore,
    Map<String, Double> categoryScores,
    List<String> flaggedCategories,
    long inferenceTimeMs
) {
    public static ToxicityResult safe() {
        return new ToxicityResult(false, 0.0, Map.of(), List.of(), 0L);
    }
}
