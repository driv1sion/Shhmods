# Shhmods: Moderation-as-a-Service (MaaS) Integration & Data Strategy

This document outlines how Shhmods operates as a backend Moderation-as-a-Service API, how external developers can integrate it into their own communities, and how the system is trained and tested using synthetic data.

## 1. The Moderation-as-a-Service (MaaS) Architecture

Shhmods is not a standalone social network; it is a backend engine designed to provide enterprise-grade moderation to external communities (e.g., Discord servers, in-game chats, forums).

### Multi-Tenancy
The PostgreSQL database utilizes a `tenant_id` on all core tables (`users`, `content`, `banned_word`) along with a dedicated `tenant_config` table for tuning. This allows a single deployment of Shhmods to serve multiple independent communities simultaneously. 
- Community A can have strict rules and ban the word "noob".
- Community B can have relaxed rules where "noob" is allowed.
- Each community can configure its own moderation thresholds (e.g., rate limits, shadow ban threshold) and trust score weighting via `tenant_config`.
- Trust scores are calculated *per tenant*, ensuring a user's behavior in one game doesn't unfairly penalize them in another.

## 2. Integration Flow

Integrating Shhmods into an external application is a simple, API-first process.

### Step 1: Client Submits Content
When a user posts a message in an external application (like Discord), the application's backend sends an HTTP POST request to the Shhmods REST API.

**Endpoint:** `POST /api/v1/moderate`
```json
{
  "tenant_id": "discord_server_gaming_123",
  "external_user_id": "discord_user_8899",
  "username": "Gamer123",
  "content_text": "I'm going to ruin your life"
}
```

### Step 2: Shhmods Processing (Real-time)
1. **DB Insertion:** Shhmods inserts the record into the `content` table.
2. **SQL Triggers Fire:** `BEFORE INSERT` triggers check for burst rate-limiting (thresholds read from `tenant_config`) and execute fuzzy matching against the tenant's `banned_word` list.
3. **ML Classification:** The Java backend (via ONNX Runtime) instantly evaluates the text against a quantized DistilBERT toxicity model.
4. **Trust Score Check:** The system checks if `Gamer123`'s trust score in `discord_server_gaming_123` is below the community's `shadow_ban_threshold` (read from `tenant_config`).

### Step 3: API Response
Shhmods returns an actionable JSON response to the external application:
```json
{
  "action": "BLOCK",
  "confidence": 0.98,
  "reasons": ["toxic_intent", "banned_word_fuzzy_match"],
  "user_trust_score": 12,
  "shadow_banned": true
}
```
The external application (the Discord bot) then simply deletes the message based on the `action: BLOCK` directive.

## 3. Training & Testing Without Real Users (Synthetic Data)

Because a portfolio project doesn't have a million real users generating data, we simulate them. This proves capabilities in Data Engineering, QA Automation, and ML evaluation.

### A. The NLP Content Dataset
We use the **Kaggle Jigsaw Toxic Comment Classification Challenge** dataset.
- This open-source dataset contains hundreds of thousands of real comments manually labeled for various types of toxicity (insult, threat, obscenity).
- **Usage:** A data-seeding script loads 50,000 of these comments into the Shhmods database. This provides realistic, challenging data to test the PostgreSQL `pg_trgm` fuzzy matching, RAG similarity searches, and ONNX classification models.

### B. The User Simulator (Behavioral Data)
To test the Behavioral Anomaly Detection strategies (which catch swatters and botnets), we use a Python-based **User Simulator**.

The simulator generates HTTP traffic against the Shhmods API using distinct "Personas":
1. **Normal Personas (90%):** Post sporadically during waking hours, rarely report others, slowly gain trust points over simulated months.
2. **Troll Personas (5%):** Inject rows from the Jigsaw dataset. Frequently violate rules, triggering standard SQL moderation triggers.
3. **Botnet/Swatter Personas (5%):** Highly coordinated. They attempt to bypass rate limits by posting at exact intervals, or they execute "swarm attacks" by having 50 fake users report the same `content_id` within a 3-second window.

**Outcome:** 
- In **Phase 4**, simple botnets are caught instantly by the pure-SQL variance check.
- Running this simulator long-term populates the database with a rich, multidimensional dataset (typing speed, session length, report variance). 
- In **Phase 6+**, Python ML outbox workers (Isolation Forests) can then be trained on this synthetic data to successfully identify and flag the more sophisticated botnet clusters that add timing jitter to evade the SQL variance checks.
