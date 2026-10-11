# Platform Overview

Welcome to **Shhmods**, a deterministic, highly-scalable moderation engine designed to protect digital communities under adversarial stress. 

Traditional moderation systems struggle because they rely on binary logic (e.g., banning a user permanently for a single offense) or black-box AI that cannot explain its decisions. Shhmods solves this by combining the **explainability of a rules engine** with the **nuance of human forgiveness** and the **scale of graph analytics**.

## Core Philosophy
Shhmods operates on three fundamental principles:
1. **Context Over Content:** A suspicious URL posted by a 5-year trusted veteran is treated differently than the exact same URL posted by a 5-minute-old account.
2. **Explainability:** Every action the system takes is recorded in an immutable ledger with exact signal parameters. There are no "black box" bans.
3. **Mathematical Forgiveness:** People make mistakes. Trust is lost instantly but regained slowly over time through continuous exponential decay algorithms.

## The Orchestration Layer
At its core, Shhmods is an Orchestration Engine built in Spring Boot and powered by PostgreSQL.

1. **Text Normalization & Zero-Shot AI:** The raw text is normalized (homoglyphs, invisible characters, deduplication) and passed through a local pre-trained **ONNX Transformer** (`unitary/toxic-bert`) to compute multi-label toxicity and threat probabilities in ~12ms.
2. **Signal Gathering:** The engine fetches the user's current Trust Scores across multiple compartments (Spam, Harassment, Coordination), their account age, and their recent burst metrics.
3. **Graph Evaluation:** The engine queries real-time materialized views to see if the user is acting as part of a coordinated swarm (Botnets / Brigading).
4. **Policy Overrides:** The engine checks if the community (Tenant) has instituted custom bypass rules for their users.
5. **The Verdict:** The engine reaches a decision (ALLOW, REVIEW, THROTTLE, or BLOCK).
6. **The Ledger:** In a single, atomic database transaction, the content is saved, the trust penalties are applied, and the exact reasoning and ML signals are carved into an immutable `moderation_event` ledger.

## Next Steps
To understand how Shhmods makes these decisions, proceed to:
- [Zero-Shot ONNX Text Classification](Text_Classification_ONNX.md)
- [Trust and Decay Model](Trust_and_Decay_Model.md)
- [Graph Coordination Engine](Graph_Detection_Engine.md)
- [Tenant Policy Overrides](Policy_Overrides.md)
