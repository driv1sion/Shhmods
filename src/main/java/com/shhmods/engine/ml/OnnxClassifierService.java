package com.shhmods.engine.ml;

import ai.onnxruntime.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * High-performance Zero-Shot Text Classification service powered by ONNX Runtime
 * and a pre-trained Transformer model (DistilBERT / Toxic-BERT).
 */
@Service
public class OnnxClassifierService {

    private static final Logger logger = LoggerFactory.getLogger(OnnxClassifierService.class);

    private static final int MAX_SEQUENCE_LENGTH = 128;
    private static final double DEFAULT_TOXICITY_THRESHOLD = 0.50;

    private static final String[] CATEGORY_LABELS = {
        "toxic",
        "severe_toxic",
        "obscene",
        "threat",
        "insult",
        "identity_hate"
    };

    private OrtEnvironment env;
    private OrtSession session;
    private BertTokenizer tokenizer;
    private boolean isInitialized = false;

    private final double toxicityThreshold;

    public OnnxClassifierService(
            @Value("${shhmods.ml.model-path:classpath:models/model.onnx}") Resource modelResource,
            @Value("${shhmods.ml.vocab-path:classpath:models/vocab.txt}") Resource vocabResource,
            @Value("${shhmods.ml.toxicity-threshold:0.50}") double toxicityThreshold) {

        this.toxicityThreshold = toxicityThreshold;
        initializeModel(modelResource, vocabResource);
    }

    private synchronized void initializeModel(Resource modelResource, Resource vocabResource) {
        try {
            logger.info("Initializing ONNX Runtime Environment for Text Classification...");

            // 1. Initialize Tokenizer
            if (!vocabResource.exists()) {
                logger.warn("Tokenizer vocabulary resource not found: {}. ONNX classifier will be disabled.", vocabResource);
                return;
            }
            try (InputStream vocabStream = vocabResource.getInputStream()) {
                this.tokenizer = new BertTokenizer(vocabStream);
            }

            // 2. Initialize ONNX Environment & Session
            if (!modelResource.exists()) {
                logger.warn("ONNX model resource not found: {}. ONNX classifier will be disabled.", modelResource);
                return;
            }

            this.env = OrtEnvironment.getEnvironment("shhmods-ml");
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);

            // Copy resource to a temporary file if needed, or load bytes
            byte[] modelBytes;
            try (InputStream modelStream = modelResource.getInputStream()) {
                modelBytes = modelStream.readAllBytes();
            }

            this.session = env.createSession(modelBytes, opts);
            this.isInitialized = true;

            logger.info("ONNX Text Classifier initialized successfully! Inputs: {}, Outputs: {}, Threshold: {}",
                    session.getInputNames(), session.getOutputNames(), toxicityThreshold);

        } catch (Throwable t) {
            logger.error("Failed to initialize ONNX Classifier: {}. Falling back to passive mode.", t.getMessage(), t);
            this.isInitialized = false;
        }
    }

    /**
     * Classifies text for toxicity, harassment, obscenity, and threats.
     */
    public ToxicityResult classify(String text) {
        if (!isInitialized || text == null || text.isBlank()) {
            return ToxicityResult.safe();
        }

        long startTime = System.currentTimeMillis();

        try {
            BertTokenizer.TokenizedInput tokenized = tokenizer.tokenize(text, MAX_SEQUENCE_LENGTH);

            long[][] inputIdsArray = new long[1][MAX_SEQUENCE_LENGTH];
            long[][] attentionMaskArray = new long[1][MAX_SEQUENCE_LENGTH];
            long[][] tokenTypeIdsArray = new long[1][MAX_SEQUENCE_LENGTH];

            inputIdsArray[0] = tokenized.inputIds();
            attentionMaskArray[0] = tokenized.attentionMask();
            tokenTypeIdsArray[0] = tokenized.tokenTypeIds();

            Map<String, OnnxTensor> inputMap = new HashMap<>();
            Set<String> requiredInputs = session.getInputNames();

            if (requiredInputs.contains("input_ids")) {
                inputMap.put("input_ids", OnnxTensor.createTensor(env, inputIdsArray));
            }
            if (requiredInputs.contains("attention_mask")) {
                inputMap.put("attention_mask", OnnxTensor.createTensor(env, attentionMaskArray));
            }
            if (requiredInputs.contains("token_type_ids")) {
                inputMap.put("token_type_ids", OnnxTensor.createTensor(env, tokenTypeIdsArray));
            }

            try (OrtSession.Result result = session.run(inputMap)) {
                // Fetch the logits from the first output
                OnnxValue outputValue = result.get(0);
                float[][] logits = (float[][]) outputValue.getValue();

                Map<String, Double> categoryScores = new LinkedHashMap<>();
                List<String> flaggedCategories = new ArrayList<>();
                double primaryToxicScore = 0.0;

                if (logits != null && logits.length > 0 && logits[0].length > 0) {
                    float[] scores = logits[0];
                    for (int i = 0; i < scores.length && i < CATEGORY_LABELS.length; i++) {
                        double prob = sigmoid(scores[i]);
                        String category = CATEGORY_LABELS[i];
                        categoryScores.put(category, prob);

                        if (i == 0) {
                            primaryToxicScore = prob;
                        }

                        if (prob >= toxicityThreshold) {
                            flaggedCategories.add(category);
                        }
                    }
                }

                // If any category is flagged, or primary toxicity exceeds threshold
                boolean isToxic = !flaggedCategories.isEmpty() || primaryToxicScore >= toxicityThreshold;
                long duration = System.currentTimeMillis() - startTime;

                logger.debug("ONNX classification: text='{}' isToxic={} score={} flagged={} time={}ms",
                        text, isToxic, primaryToxicScore, flaggedCategories, duration);

                return new ToxicityResult(isToxic, primaryToxicScore, categoryScores, flaggedCategories, duration);

            } finally {
                // Free native tensor allocations
                for (OnnxTensor tensor : inputMap.values()) {
                    tensor.close();
                }
            }

        } catch (Exception e) {
            logger.error("Error during ONNX inference for text: {}", text, e);
            return ToxicityResult.safe();
        }
    }

    public boolean isToxic(String text) {
        return classify(text).isToxic();
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    private double sigmoid(float val) {
        return 1.0 / (1.0 + Math.exp(-val));
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (session != null) {
                session.close();
            }
            if (env != null) {
                env.close();
            }
            logger.info("ONNX Runtime resources closed successfully.");
        } catch (Exception e) {
            logger.warn("Error closing ONNX resources", e);
        }
    }
}
