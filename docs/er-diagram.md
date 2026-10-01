# **ENTITY – RELATIONSHIP (ER) DIAGRAM**

## 1) ER DIAGRAM:


## 2) ENTITIES AND THEIR ATTRIBUTES

### **1. USER (Strong Entity)**
**Description:**  
Represents a registered user who can create content and submit reports; scoped to a specific tenant.

**Attributes:**
- user_id (Primary Key) – uniquely identifies a user  
- tenant_id (Foreign Key) – references TENANT_CONFIG  
- external_user_id – ID from the external integrating system (e.g., Discord ID)  
- username – display name  
- email – email address  
- status – account status  
- is_visible – shadow-ban flag (false = shadow-banned)  
- account_created_at – account creation timestamp  

### **2. CONTENT (Weak Entity)**
**Description:**  
Represents textual content (posts/comments/reviews) created by users and subject to moderation.

**Attributes:**
- content_id (Primary Key) – uniquely identifies content  
- tenant_id (Foreign Key) – references TENANT_CONFIG  
- user_id (Foreign Key) – author of the content  
- content_text – text body  
- content_type – content category  
- content_hash – SHA-256 of normalised text (lowercased, punctuation stripped); used for duplicate detection within a 24h window  
- created_at – creation timestamp  
- is_visible – per-post visibility flag; allows hiding a single post without shadow-banning the author  
- is_flagged (Derived) – true if unresolved content flags exist  

### **3. REPORT (Weak Entity)**
**Description:**  
Represents a user-submitted report about content; may or may not lead to moderation.

**Attributes:**
- report_id (Primary Key) – uniquely identifies a report  
- content_id (Foreign Key) – the content being reported  
- reporter_id (Foreign Key) – the user who submitted the report  
- report_reason – reason for reporting  
- status – report status  
- reported_at – submission timestamp  
- reporter_ip_hash – hashed IP for swatter defense  

### **4. CONTENT_FLAG (Weak Entity)**
**Description:**  
System-generated flag indicating that content violated moderation rules; exists only for a specific content item.

**Attributes:**
- flag_id (Primary Key)  
- content_id (Foreign Key) – the flagged content  
- flag_type – category of flag  
- reason – explanation for flag  
- status – state machine position: PENDING → REVIEWED → APPEALED → RESOLVED  
  *(Note: `resolved BOOLEAN` removed — `status = 'RESOLVED'` is the single source of truth)*  
- report_count – number of contributing reports  
- flagged_at – flag creation time  

### **5. VIOLATION (Weak Entity)**
**Description:**  
Represents a confirmed violation resulting from a content flag; used in trust scoring.

**Attributes:**
- violation_id (Primary Key)  
- flag_id (Foreign Key) – the flag that resulted in this violation  
- user_id (Foreign Key) – the user who committed the violation  
- violation_type – violation classification  
- severity_level – numeric severity (1-5)  
- violation_date – date of confirmation  

### **6. TRUST_SCORE (Weak Entity)**
**Description:**  
Represents dynamically computed trust metrics for a user based on behavior and moderation outcomes.

**Attributes:**
- user_id (Primary Key, Foreign Key) – exactly one score per user  
- trust_score – calculated numeric value  
- violation_count – total violations committed  
- accurate_reports – validated reports submitted  
- last_updated – last recalculation timestamp  

### **7. BANNED_WORD (Weak Entity)**
**Description:**  
Represents prohibited keywords used by the system for automated moderation, scoped to a tenant.

**Attributes:**
- word_id (Primary Key) – uniquely identifies a banned word  
- tenant_id (Foreign Key) – references TENANT_CONFIG  
- keyword – prohibited word or phrase  
- match_type – EXACT_WORD_ONLY (word-boundary regex) or SUBSTRING_ALLOWED  
- severity_level – severity classification  
- word_status – ACTIVE (enforced by triggers) or PENDING (proposed by N-gram agent)  
- added_by – system/admin identifier  
- added_at – date added  

### **8. AUDIT_LOG (Strong Entity)**
**Description:**  
Maintains an immutable record of moderation-related system actions. Immutability enforced by a trigger that ABORTs any UPDATE or DELETE.

**Attributes:**
- audit_id (Primary Key) – uniquely identifies an audit entry  
- action_type – type of action  
- action_details – detailed description  
- action_timestamp – time of action  
- reference_id – affected entity identifier  
- reference_type – affected entity type  
- actor_id – the user/admin who performed the action (NULL for trigger/system-initiated actions)  
- actor_type – TRIGGER | SYSTEM | ADMIN | APP_USER  

### **9. OUTBOX_EVENT (Strong Entity)**
**Description:**  
Transactional outbox table. DB triggers write events here instead of side-effecting directly. Spring workers poll and claim events atomically using `SELECT ... FOR UPDATE SKIP LOCKED`.

**Attributes:**
- event_id (Primary Key)  
- aggregate_type – entity type that triggered the event  
- aggregate_id – ID of the triggering entity  
- event_type – e.g., CONTENT_FLAGGED, USER_SHADOW_BANNED  
- payload – JSONB event data  
- created_at – event creation timestamp  
- processed_at – NULL = unprocessed; non-NULL = successfully claimed and processed timestamp  
  *(Replaces `processed BOOLEAN` — enables `SKIP LOCKED` claim pattern, eliminating the concurrent-worker race condition)*

### **10. TENANT_CONFIG (Strong Entity)**
**Description:**  
Per-tenant configuration table. Stores tunable thresholds and scoring formula weights, eliminating hardcoded magic numbers from trigger logic. Phase 6+ ML weight tuner writes proposed updates here.

**Attributes:**
- tenant_id (Primary Key) – links to the tenant this config applies to; 'GLOBAL' row is the fallback  
- weight_accurate_reports – formula weight for accurate report count (default 5.0)  
- weight_severity – formula weight for violation severity (default 1.0)  
- weight_account_age – formula weight for account age bonus (default 10.0)  
- shadow_ban_threshold – trust score below which a user is shadow-banned (default 10)  
- report_threshold – number of reports within 7 days that auto-flag content (default 3)  
- burst_limit – max posts per minute before rate-limit flag (default 3)  
- sustained_limit – max posts per hour before rate-limit flag (default 10)  
- trgm_similarity_threshold – pg_trgm similarity sensitivity for fuzzy matching (default 0.35)  
- created_at / updated_at – timestamps

### **11. MODERATION_PRECEDENT (Strong Entity) — Phase 6+ Only**
**Description:**  
Stores historical moderation cases and their vector embeddings for Case-Based Reasoning. Populated only when a `content_flag` is resolved (not on every content insert).

**Attributes:**
- precedent_id (Primary Key)
- content_hash
- content_embedding – pgvector representation (generated asynchronously via outbox worker)
- final_decision




## 3) RELATIONSHIPS

### **1. CREATES (USER–CONTENT)**
- **Type:** 1:N  
- **Participation:** USER (Partial), CONTENT (Total)  
- **Description:** A user may create zero or more content items; each content item is created by exactly one user.

### **2. SUBMITS (USER–REPORT)**
- **Type:** 1:N  
- **Participation:** USER (Partial), REPORT (Total)  
- **Description:** A user may submit zero or more reports; each report is submitted by exactly one user.

### **3. IS_REPORTED_IN (CONTENT–REPORT)**
- **Type:** 1:N  
- **Participation:** CONTENT (Partial), REPORT (Total)  
- **Description:** A content item may receive zero or more reports; each report refers to exactly one content item.

### **4. IS_FLAGGED_AS (CONTENT–CONTENT_FLAG)**
- **Type:** 1:N  
- **Participation:** CONTENT (Partial), CONTENT_FLAG (Total)  
- **Description:** A content item may generate zero or more content flags; each content flag is associated with exactly one content item.

### **5. CONTRIBUTES_TO (REPORT–CONTENT_FLAG)**
- **Type:** N:1  
- **Participation:** REPORT (Partial), CONTENT_FLAG (Total)  
- **Description:** Multiple reports may contribute to a single content flag; a report may or may not contribute to a flag.

### **6. LEADS_TO (CONTENT_FLAG–VIOLATION)**
- **Type:** 1:N  
- **Participation:** CONTENT_FLAG (Partial), VIOLATION (Total)  
- **Description:** A content flag may result in zero or more violations; each violation originates from exactly one content flag.

### **7. COMMITS (USER–VIOLATION)**
- **Type:** 1:N  
- **Participation:** USER (Partial), VIOLATION (Total)  
- **Description:** A user may commit zero or more violations; each violation is committed by exactly one user.

### **8. HAS (USER–TRUST_SCORE)**
- **Type:** 1:1  
- **Participation:** USER (Total), TRUST_SCORE (Total)  
- **Description:** Each user has exactly one trust score, and each trust score belongs to exactly one user.

### **9. LOGS (AUDIT_LOG–System Entities)**
- **Type:** N:1 (Conceptual)  
- **Participation:** AUDIT_LOG (Total), Referenced Entity (Partial)  
- **Description:** Each audit log records a system action performed on one entity instance; an entity instance may be referenced by zero or more audit logs.

### **10. CONFIGURES (TENANT_CONFIG–USER/CONTENT/BANNED_WORD)**
- **Type:** 1:N  
- **Participation:** TENANT_CONFIG (Partial), USER/CONTENT/BANNED_WORD (Total)  
- **Description:** A tenant configuration dictates the thresholds and rules for multiple users, content items, and banned words within that community.
