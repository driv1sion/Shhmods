package com.shhmods.engine.ml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * WordPiece tokenizer compatible with BERT / DistilBERT models.
 */
public class BertTokenizer {

    private static final Logger logger = LoggerFactory.getLogger(BertTokenizer.class);

    public static final String PAD_TOKEN = "[PAD]";
    public static final String UNK_TOKEN = "[UNK]";
    public static final String CLS_TOKEN = "[CLS]";
    public static final String SEP_TOKEN = "[SEP]";

    private final Map<String, Integer> tokenToId = new HashMap<>(32000);
    private final Map<Integer, String> idToToken = new HashMap<>(32000);

    private final int padId;
    private final int unkId;
    private final int clsId;
    private final int sepId;

    public record TokenizedInput(
        long[] inputIds,
        long[] attentionMask,
        long[] tokenTypeIds,
        List<String> tokens
    ) {}

    public BertTokenizer(InputStream vocabInputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(vocabInputStream, StandardCharsets.UTF_8))) {
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                tokenToId.put(line, index);
                idToToken.put(index, line);
                index++;
            }
        }

        this.padId = tokenToId.getOrDefault(PAD_TOKEN, 0);
        this.unkId = tokenToId.getOrDefault(UNK_TOKEN, 100);
        this.clsId = tokenToId.getOrDefault(CLS_TOKEN, 101);
        this.sepId = tokenToId.getOrDefault(SEP_TOKEN, 102);

        logger.info("Loaded BERT vocabulary with {} tokens. PAD={}, UNK={}, CLS={}, SEP={}",
                tokenToId.size(), padId, unkId, clsId, sepId);
    }

    public TokenizedInput tokenize(String text, int maxSeqLength) {
        if (text == null) {
            text = "";
        }

        // 1. Basic tokenization & whitespace/punct splitting
        List<String> basicTokens = basicTokenize(text);

        // 2. WordPiece tokenization
        List<String> wordPieceTokens = new ArrayList<>();
        for (String token : basicTokens) {
            wordPieceTokens.addAll(wordPiece(token));
        }

        // 3. Truncate to leave room for [CLS] and [SEP]
        int maxTokens = maxSeqLength - 2;
        if (wordPieceTokens.size() > maxTokens) {
            wordPieceTokens = wordPieceTokens.subList(0, maxTokens);
        }

        // 4. Construct final sequence with [CLS] and [SEP]
        List<String> finalTokens = new ArrayList<>(maxSeqLength);
        finalTokens.add(CLS_TOKEN);
        finalTokens.addAll(wordPieceTokens);
        finalTokens.add(SEP_TOKEN);

        long[] inputIds = new long[maxSeqLength];
        long[] attentionMask = new long[maxSeqLength];
        long[] tokenTypeIds = new long[maxSeqLength];

        for (int i = 0; i < maxSeqLength; i++) {
            if (i < finalTokens.size()) {
                String token = finalTokens.get(i);
                inputIds[i] = tokenToId.getOrDefault(token, unkId);
                attentionMask[i] = 1L;
            } else {
                inputIds[i] = padId;
                attentionMask[i] = 0L;
            }
            tokenTypeIds[i] = 0L;
        }

        return new TokenizedInput(inputIds, attentionMask, tokenTypeIds, finalTokens);
    }

    private List<String> basicTokenize(String text) {
        String cleaned = cleanText(text.toLowerCase(Locale.ROOT));
        String[] rawTokens = cleaned.split("\\s+");
        List<String> tokens = new ArrayList<>();

        for (String raw : rawTokens) {
            if (raw.isEmpty()) continue;
            // Split out punctuation characters
            StringBuilder currentWord = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (isPunctuation(c)) {
                    if (!currentWord.isEmpty()) {
                        tokens.add(currentWord.toString());
                        currentWord.setLength(0);
                    }
                    tokens.add(String.valueOf(c));
                } else {
                    currentWord.append(c);
                }
            }
            if (!currentWord.isEmpty()) {
                tokens.add(currentWord.toString());
            }
        }
        return tokens;
    }

    private List<String> wordPiece(String token) {
        int maxChars = 100;
        if (token.length() > maxChars) {
            return Collections.singletonList(UNK_TOKEN);
        }

        List<String> subTokens = new ArrayList<>();
        int start = 0;
        int len = token.length();

        while (start < len) {
            int end = len;
            String curSubstr = null;
            while (start < end) {
                String substr = token.substring(start, end);
                if (start > 0) {
                    substr = "##" + substr;
                }
                if (tokenToId.containsKey(substr)) {
                    curSubstr = substr;
                    break;
                }
                end--;
            }

            if (curSubstr == null) {
                return Collections.singletonList(UNK_TOKEN);
            }
            subTokens.add(curSubstr);
            start = end;
        }

        return subTokens;
    }

    private String cleanText(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0 || c == 0xfffd || isControl(c)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean isControl(char c) {
        if (c == '\t' || c == '\n' || c == '\r') {
            return false;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return Character.isISOControl(c) || block == Character.UnicodeBlock.SPECIALS;
    }

    private boolean isPunctuation(char c) {
        if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64) ||
            (c >= 91 && c <= 96) || (c >= 123 && c <= 126)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.CONNECTOR_PUNCTUATION ||
               type == Character.DASH_PUNCTUATION ||
               type == Character.START_PUNCTUATION ||
               type == Character.END_PUNCTUATION ||
               type == Character.INITIAL_QUOTE_PUNCTUATION ||
               type == Character.FINAL_QUOTE_PUNCTUATION ||
               type == Character.OTHER_PUNCTUATION;
    }

    public int getPadId() {
        return padId;
    }

    public int getUnkId() {
        return unkId;
    }

    public int getClsId() {
        return clsId;
    }

    public int getSepId() {
        return sepId;
    }
}
