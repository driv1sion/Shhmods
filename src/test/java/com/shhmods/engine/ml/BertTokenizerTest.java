package com.shhmods.engine.ml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BertTokenizerTest {

    private BertTokenizer tokenizer;

    @BeforeEach
    void setUp() throws Exception {
        ClassPathResource vocabResource = new ClassPathResource("models/vocab.txt");
        try (InputStream stream = vocabResource.getInputStream()) {
            tokenizer = new BertTokenizer(stream);
        }
    }

    @Test
    void testBasicAndWordPieceTokenization() {
        BertTokenizer.TokenizedInput result = tokenizer.tokenize("Hello, world!", 16);

        assertNotNull(result);
        assertEquals(16, result.inputIds().length);
        assertEquals(16, result.attentionMask().length);
        assertEquals(16, result.tokenTypeIds().length);

        // Special tokens: [CLS] at pos 0, [SEP] after tokens, then [PAD]
        assertEquals(tokenizer.getClsId(), result.inputIds()[0]);

        List<String> tokens = result.tokens();
        assertTrue(tokens.contains("[CLS]"));
        assertTrue(tokens.contains("hello"));
        assertTrue(tokens.contains(","));
        assertTrue(tokens.contains("world"));
        assertTrue(tokens.contains("!"));
        assertTrue(tokens.contains("[SEP]"));

        // Attention mask should be 1 for real tokens and 0 for padding
        for (int i = 0; i < tokens.size(); i++) {
            assertEquals(1L, result.attentionMask()[i]);
        }
        for (int i = tokens.size(); i < 16; i++) {
            assertEquals(0L, result.attentionMask()[i]);
            assertEquals(tokenizer.getPadId(), result.inputIds()[i]);
        }
    }

    @Test
    void testUnknownWordHandling() {
        // Obscure wordpiece test
        BertTokenizer.TokenizedInput result = tokenizer.tokenize("unbelievable spammer123456", 16);
        assertNotNull(result);
        assertTrue(result.tokens().size() > 2);
    }
}
