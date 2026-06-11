# **ENTITY – RELATIONSHIP (ER) DIAGRAM**
Date: 29.1.26

## 1) ER DIAGRAM:
![moderator-ER](https://github.com/user-attachments/assets/f464aeca-78a2-4fb3-8d22-50d0660dcf09)

## 2) ENTITIES AND THEIR ATTRIBUTES

### **1. USER (Strong Entity)**
**Description:**  
Represents a registered user who can create content and submit reports; exists independently.

**Attributes:**
- user_id (Primary Key) – uniquely identifies a user  
- username – display name  
- email – email address  
- status – account status  
- account_created_at – account creation timestamp  

### **2. CONTENT (Strong Entity)**
**Description:**  
Represents textual content (posts/comments/reviews) created by users and subject to moderation.

**Attributes:**
- content_id (Primary Key) – uniquely identifies content  
- content_text – text body  
- content_type – content category  
- content_hash – hash for duplicate detection  
- created_at – creation timestamp  
- is_flagged (Derived) – true if unresolved content flags exist  

### **3. REPORT (Strong Entity)**
**Description:**  
Represents a user-submitted report about content; may or may not lead to moderation.

**Attributes:**
- report_id (Primary Key) – uniquely identifies a report  
- report_reason – reason for reporting  
- reported_at – submission timestamp  
- status – report status  

### **4. CONTENT_FLAG (Weak Entity)**
**Description:**  
System-generated flag indicating that content violated moderation rules; exists only for a specific content item.

**Attributes:**
- flag_id (Partial Key) – distinguishes flags under the same content  
- flag_type – category of flag  
- reason – explanation for flag  
- flagged_at – flag creation time  
- resolved – resolution status  
- report_count – number of contributing reports  

### **5. VIOLATION (Weak Entity)**
**Description:**  
Represents a confirmed violation resulting from a content flag; used in trust scoring.

**Attributes:**
- violation_id (Partial Key) – distinguishes violations under the same flag  
- violation_type – violation classification  
- violation_date – date of confirmation  

### **6. TRUST_SCORE (Weak Entity)**
**Description:**  
Represents dynamically computed trust metrics for a user based on behavior and moderation outcomes.

**Attributes:**
- trust_score – calculated numeric value  
- violation_count – total violations committed  
- accurate_reports – validated reports submitted  
- last_updated – last recalculation timestamp  

### **7. BANNED_WORD (Strong Entity)**
**Description:**  
Represents prohibited keywords used by the system for automated moderation.

**Attributes:**
- word_id (Primary Key) – uniquely identifies a banned word  
- keyword – prohibited word or phrase  
- severity_level – severity classification  
- added_at – date added  
- added_by – system/admin identifier  

### **8. AUDIT_LOG (Strong Entity)**
**Description:**  
Maintains an immutable record of moderation-related system actions.

**Attributes:**
- audit_id (Primary Key) – uniquely identifies an audit entry  
- action_type – type of action  
- action_details – detailed description  
- action_timestamp – time of action  
- reference_id – affected entity identifier  
- reference_type – affected entity type  

### **9. MODERATION_PRECEDENT (Strong Entity)**
**Description:**  
Stores historical moderation cases and their vector embeddings for Case-Based Reasoning.

**Attributes:**
- precedent_id (Primary Key)
- content_hash
- content_embedding – vector representation
- final_decision

### **10. RULE_PROPOSAL (Weak Entity)**
**Description:**  
Proposed by the N-gram Agent, linked to BANNED_WORD.

**Attributes:**
- proposal_id (Primary Key)
- proposed_phrase
- statistical_confidence
- status

### **11. ATTACK_GRAPH_LOG (Strong Entity)**
**Description:**  
Logs detected swatter rings by the Graph-Based Agent.

**Attributes:**
- attack_id (Primary Key)
- target_user_id
- calculated_variance
- penalized_users_array


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
