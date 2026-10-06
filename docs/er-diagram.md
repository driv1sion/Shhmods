# **ENTITY – RELATIONSHIP (ER) DIAGRAM (Orchestration Architecture)**

## 1) ENTITIES AND THEIR ATTRIBUTES

### **1. USER (Strong Entity)**
**Description:**  
Represents a registered user who can create content and submit reports; scoped to a specific tenant.
**Attributes:**
- user_id (Primary Key) 
- tenant_id (Foreign Key)
- external_user_id 
- username, email, status, is_visible, account_created_at

### **2. CONTENT (Weak Entity)**
**Description:**  
Represents textual content (posts/comments/reviews) created by users.
**Attributes:**
- content_id (Primary Key)
- tenant_id, user_id (Foreign Keys)
- content_text, content_type, content_hash
- is_visible, created_at

### **3. REPORT & CONTENT_FLAG (Weak Entities)**
**Description:**  
Reports submitted by users, and system-generated flags aggregating multiple reports.
**Attributes (Report):** report_id, content_id, reporter_id, report_reason, status, reported_at, reporter_ip_hash
**Attributes (Content_Flag):** flag_id, content_id, flag_type, reason, status, report_count, flagged_at

### **4. TRUST_EVENT (Weak Entity) & EFFECTIVE_TRUST (View)**
**Description:**  
Replaces the old static trust_score table. `trust_event` is an append-only ledger of penalties. `effective_trust` dynamically computes current trust using exponential time decay (`exp(-decay_rate * time_delta)`).
**Attributes (Trust_Event):** event_id, user_id, compartment (e.g. SPAM, HARASSMENT), penalty, decay_rate, occurred_at

### **5. MODERATION_EVENT (Strong Entity - Ledger)**
**Description:**  
Immutable ledger recording every decision made by the Decision Engine. Updates/Deletes are blocked by DB triggers.
**Attributes:** id, tenant_id, user_id, content_hash, normalized_text, signals (JSONB), policy_version, decision, created_at

### **6. TENANT_CONFIG & TENANT_POLICY (Strong Entities)**
**Description:**  
Stores global limits and specific behavioral overrides per tenant.
**Attributes (Config):** tenant_id, burst_limit, shadow_ban_threshold, etc.
**Attributes (Policy):** policy_id, tenant_id, rule_name (e.g. URL_FILTER), override_action (e.g. BYPASS), condition_json (e.g. {"min_spam_trust": 80}), is_active

### **7. GRAPH CLUSTERS (Materialized Views)**
**Description:**  
`coordination_clusters` detects swarms of users reporting the same targets. `content_clusters` detects rings of users posting identical hashes.
**Attributes:** reporter_a, reporter_b, shared_targets / poster_a, poster_b, shared_hashes

### **8. OUTBOX_EVENT & AUDIT_LOG (Strong Entities)**
**Description:**  
`outbox_event` handles decoupled asynchronous jobs (ML embeddings, Webhooks) via Spring workers. `audit_log` records human admin actions.

## 2) RELATIONSHIPS
*   **CREATES:** USER (1) -> CONTENT (N)
*   **EVALUATES:** DECISION ENGINE -> reads TRUST_EVENT, CLUSTERS, TENANT_POLICY -> creates MODERATION_EVENT (1)
*   **OVERRIDES:** TENANT_POLICY (N) -> modifies DECISION (1)
*   **DECAYS:** TIME -> reduces TRUST_EVENT penalties -> dynamically alters EFFECTIVE_TRUST
*   **CLUSTERS:** CONTENT/REPORT (N) -> grouped into GRAPH CLUSTERS (N:M relationships detected via SQL JOINs)
