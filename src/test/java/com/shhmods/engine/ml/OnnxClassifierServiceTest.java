package com.shhmods.engine.ml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

public class OnnxClassifierServiceTest {

    private OnnxClassifierService classifierService;

    @BeforeEach
    void setUp() {
        ClassPathResource modelResource = new ClassPathResource("models/model.onnx");
        ClassPathResource vocabResource = new ClassPathResource("models/vocab.txt");
        classifierService = new OnnxClassifierService(modelResource, vocabResource, 0.50);
    }

    @Test
    void testInitializationSuccessful() {
        assertTrue(classifierService.isInitialized(), "ONNX Classifier should be successfully initialized with model and vocab");
    }

    @Test
    void testNonToxicTextClassification() {
        String cleanMessage = "Hello everyone, hope you are having a wonderful and productive day!";
        ToxicityResult result = classifierService.classify(cleanMessage);

        assertNotNull(result);
        assertFalse(result.isToxic(), "Clean friendly message should NOT be flagged as toxic");
        assertTrue(result.toxicityScore() < 0.20, "Toxicity score should be very low for clean text. Actual: " + result.toxicityScore());
        assertTrue(result.flaggedCategories().isEmpty(), "No categories should be flagged for clean text");
    }

    @Test
    void testToxicTextClassification() {
        String toxicMessage = "I hate you, you are a disgusting idiot and a piece of trash. Die!";
        ToxicityResult result = classifierService.classify(toxicMessage);

        assertNotNull(result);
        assertTrue(result.isToxic(), "Abusive text should be flagged as toxic");
        assertTrue(result.toxicityScore() > 0.70, "Toxicity score should be high. Actual: " + result.toxicityScore());
        assertFalse(result.flaggedCategories().isEmpty(), "Flagged categories should not be empty");
        assertTrue(result.flaggedCategories().contains("toxic") || result.flaggedCategories().contains("insult"),
                "Should identify toxic or insult category: " + result.flaggedCategories());
    }

    @Test
    void testThreatClassification() {
        String threatMessage = "I will track you down and kill you and murder your whole family";
        ToxicityResult result = classifierService.classify(threatMessage);

        assertNotNull(result);
        assertTrue(result.isToxic(), "Violent threat text must be flagged");
        assertTrue(result.categoryScores().containsKey("threat"), "Should have threat category score");
        assertTrue(result.categoryScores().get("threat") > 0.50, "Threat category score should exceed threshold. Actual: " + result.categoryScores().get("threat"));
    }
}
