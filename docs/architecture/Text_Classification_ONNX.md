# Local Multi-Label Transformer Classification Engine

Shhmods integrates local transformer inference directly into the moderation decision pipeline using the **Microsoft ONNX Runtime** in Java. 

Unlike external moderation APIs (OpenAI, Perspective API) that introduce subscription fees, network hops, rate limits, and third-party data privacy concerns, Shhmods executes supervised inference entirely on-premise within the containerized backend.

---

## 1. Pre-Trained Supervised Transformer Architecture

Content moderation models do not require user-specific training data to understand abusive language, hate speech, threats, or harassment. Shhmods utilizes a pre-trained **`unitary/toxic-bert`** transformer model (INT8 quantized, ~106MB), trained in a supervised multi-label setting on millions of toxic comments from Wikipedia and Civil Comments.

Because the underlying representation encodes internet toxicity patterns, the model generalizes effectively out of the box across common community forum and chat text without requiring custom fine-tuning.

### Multi-Label Toxicity Head

The model produces multi-label classification probabilities across six independent dimensions:

| Category | Description | Moderation Action | Default Severity |
| :--- | :--- | :--- | :--- |
| **`toxic`** | General abusive / aggressive language | `REVIEW` | -10 Trust Penalty |
| **`severe_toxic`** | Extreme toxicity, violent vitriol | `BLOCK` | -25 Trust Penalty |
| **`obscene`** | Severe vulgarity or explicit profanity | `REVIEW` | -10 Trust Penalty |
| **`threat`** | Physical threats, violent intent | `BLOCK` | -25 Trust Penalty |
| **`insult`** | Target-directed harassment, denigration | `REVIEW` | -10 Trust Penalty |
| **`identity_hate`** | Hate speech, discriminatory attacks | `BLOCK` | -25 Trust Penalty |

---

## 2. Tokenization Pipeline (`BertTokenizer`)

The model requires WordPiece-tokenized tensor sequences. To eliminate heavy external Python sidecars, Shhmods incorporates a native Java BERT tokenizer:

1. **Text Normalization**: Strips non-printing unicode characters, normalizes whitespace, and converts to lowercase.
2. **Punctuation Isolation**: Splits punctuation marks into discrete tokens following standard BERT tokenization rules.
3. **WordPiece Greedy Subword Match**: Iteratively extracts longest matching subwords from the bundled 30,522-token WordPiece vocabulary (`vocab.txt`), prefixing trailing fragments with `##`. Unrecognized subwords fall back cleanly to `[UNK]`.
4. **Tensor Framing**: Appends sequence framing tokens `[CLS]` (ID 101) and `[SEP]` (ID 102), and generates zero-padded sequences with an explicit `attention_mask` tensor (shape `[1, 128]`).

---

## 3. Inference Engine (`OnnxClassifierService`)

The inference service initializes a persistent `OrtEnvironment` and `OrtSession` loaded from `models/model.onnx`:

- **Execution Mode**: Multi-threaded intra-op native execution using optimized C++ JNI bindings.
- **Quantization**: INT8 quantization reduces memory footprint to ~106MB while speeding up CPU tensor arithmetic by ~4x compared to FP32 models.
- **Latency**: Single-request inference typically takes between 10ms and 20ms on modern multicore CPUs.

```java
ToxicityResult result = onnxClassifierService.classify(content);
// Returns:
// result.isToxic()             -> true / false
// result.toxicityScore()       -> 0.9845
// result.categoryScores()      -> {toxic: 0.9845, threat: 0.0012, insult: 0.9412, ...}
// result.flaggedCategories()   -> ["toxic", "insult"]
// result.inferenceTimeMs()     -> 12
```

---

## 4. Decision Engine & Immutable Ledger Integration

When a chat message arrives via `POST /api/v1/content`:

```mermaid
flowchart LR
    Content[Incoming Chat] --> Normalizer[Text Normalizer]
    Normalizer --> ONNX[ONNX Transformer Engine]
    ONNX --> Decision[Decision Engine]
    Decision --> Policy{Tenant Policy Override?}
    Policy -->|Bypass Allowed| Allow[ALLOW]
    Policy -->|Standard / Severe| Action[REVIEW or BLOCK]
    Action --> Ledger[Immutable Decision Ledger (JSONB)]
    Action --> Trust[Trust Event (Harassment Compartment)]
```

1. **Signal Ingestion**: `DecisionEngine` records the full ONNX breakdown in `signals`:
   - `onnx_toxic`: Boolean flag.
   - `onnx_toxicity_score`: Primary toxicity probability.
   - `onnx_categories`: Full JSON map of all 6 category probabilities.
   - `onnx_flagged_categories`: List of categories crossing the 0.50 threshold.
   - `onnx_inference_time_ms`: Execution latency.
2. **Progressive Friction**:
   - **General Toxicity / Insult**: Content is flagged for moderator review (`REVIEW`), placed in `content_flag` queue, and deducts 10 points from the user's `HARASSMENT` compartment.
   - **Severe Toxicity / Violent Threats**: Content is immediately hidden from users (`BLOCK`, `is_visible = false`), placed in the review queue, and deducts 25 points from the `HARASSMENT` compartment.
3. **Tenant Policy Bypasses**: Communities can specify bypass policies (`rule_name: "AI_TOXICITY"`) allowing trusted users with high historical standing or older account ages to post without automated blocking.

---

## 5. Testing & Verification

### Interactive UI Playground
Open the Admin Dashboard at `http://localhost` and click the **ONNX Classifier** tab to test phrases live:
- Try preset tests (*Severe Toxicity*, *Physical Threat*, *Clean Message*).
- Observe instant multi-label probability gauges and inference latency.

### Dedicated CLI Testing
Run the simulator in ONNX benchmark mode:
```bash
python3 scripts/simulator.py --onnx
```

### Direct REST API
```bash
curl -X POST http://localhost:8080/api/v1/admin/classify \
  -H "Content-Type: application/json" \
  -d '{"text": "You are completely useless, nobody wants you on this team, you idiot."}'
```
Response:
```json
{
  "isToxic": true,
  "toxicityScore": 0.9787,
  "categoryScores": {
    "toxic": 0.9787,
    "severe_toxic": 0.0206,
    "obscene": 0.5640,
    "threat": 0.0010,
    "insult": 0.9284,
    "identity_hate": 0.0069
  },
  "flaggedCategories": ["toxic", "obscene", "insult"],
  "inferenceTimeMs": 14
}
```
