package com.shhmods.engine.service;

import org.springframework.stereotype.Service;
import java.text.Normalizer;
import java.util.Map;

@Service
public class TextNormalizationService {
    
    // Simple homoglyph/leetspeak mapping
    private static final Map<Character, Character> HOMOGLYPHS = Map.ofEntries(
        Map.entry('@', 'a'), Map.entry('4', 'a'),
        Map.entry('8', 'b'), Map.entry('3', 'e'),
        Map.entry('1', 'i'), Map.entry('!', 'i'),
        Map.entry('0', 'o'), Map.entry('$', 's'),
        Map.entry('5', 's'), Map.entry('7', 't')
    );

    public String normalize(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return "";
        }
        
        // 1. Remove zero-width characters and invisible control characters
        String cleaned = rawText.replaceAll("[\\u200B-\\u200D\\uFEFF\\p{C}]", "");
        
        // 2. Unicode normalization (Decompose accents/diacritics and remove them)
        cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        cleaned = cleaned.replaceAll("\\p{M}", "");
        
        // 3. Convert to lower case
        cleaned = cleaned.toLowerCase();
        
        // 4. Homoglyph translation
        StringBuilder sb = new StringBuilder();
        for (char c : cleaned.toCharArray()) {
            sb.append(HOMOGLYPHS.getOrDefault(c, c));
        }
        
        // 5. Deduplicate repeated characters (e.g., "spaaaaaam" -> "spam")
        // Replaces 3 or more identical characters with a single one to preserve normal double letters
        return sb.toString().replaceAll("(.)\\1{2,}", "$1");
    }
}
