# **SOFTWARE REQUIREMENTS SPECIFICATION (SRS)**  
**Date:22.1.2026**

## **1. Introduction**  
### **1.1 Purpose**

This SRS defines the requirements for a DBMS-driven system that moderates user-generated textual content and computes dynamic trust scores for users. The system flags harmful or spammy content using SQL-based rules and automates trust scoring to support transparent moderation on small- to medium-scale online platforms.

### **1.2 Document Conventions**

Terms like "flag" mean marking content for review; "trust score" is a numeric value (0-100) reflecting user reliability. SQL keywords appear in uppercase (e.g., TRIGGER). Diagrams use standard UML notation where referenced.

### **1.3 Intended Audience and Reading Suggestions**

Targeted at developers, DBAs, platform owners, and stakeholders. Read Section 1 and 2 for overview; Sections 3-5 for detailed requirements. Technical readers focus on 3.1 and 5.

### **1.4 Project Scope**

In-scope: Moderation of forum posts/comments/reviews via banned keywords, duplicates, posting frequency, and user reports; trust score calculation from violations, activity, and reporting accuracy; DBMS automation with triggers, procedures, and views; Spring Boot backend for API endpoints; Post-Launch ML agents. Out-of-scope: Image/video moderation, external third-party paid ML APIs, mobile apps.

### **1.5 References**

IEEE Std 830-1998 (SRS template); provided project brief on content moderation challenges.  

---  

## **2. Overall Description**  
### **2.1 Product Perspective**

Current moderation relies on costly, opaque ML tools; this system uses auditable SQL logic within the database for lightweight, explainable flagging and scoring, ideal for platforms handling high-volume text without manual review.

### **2.2 Product Features**

- Auto-flags content matching banned keywords, duplicates, high-frequency posts, or user reports.
- Dynamically updates user trust scores via triggers on moderation events.
- Provides views for flagged content, trusted users, and audit trails.
- Supports behavior tracking over time (violations, account age).

### **2.3 User Classes and Characteristics**

- **Platform Admins**: View/manage flagged content, adjust rules; experienced DB users.
- **Moderators**: Review flags, update scores; basic SQL knowledge.
- **End Users**: Submit content/reports; non-technical.
- **System (DBMS)**: Automated via triggers/procedures.

### **2.4 Operating Environment**

### **2.4 Operating Environment**

PostgreSQL 15+ DBMS, Java 17+ (Spring Boot), Docker/Testcontainers, React.js frontend.

### **2.5 Design and Implementation Constraints**

Database-first moderation engine utilizing PostgreSQL triggers, stored procedures, and views. Backend is a Java Spring Boot REST API ensuring idempotent Outbox event processing. Post-launch Python ML sidecars run in isolated containers. Strict use of FOSS technologies with zero external paid APIs.

### **2.6 Assumptions and Dependencies**

Assumes DBMS supports triggers/stored procedures (e.g., PostgreSQL); stable schema; content limited to text (<10KB/post). Depends on accurate banned keyword lists maintained by admins.  

---  

## **3. System Features**  
### **3.1 Functional Requirements**

- **FR1**: System scans new posts/comments/reviews for banned keywords (table: banned_word); flags if match count > 0 via BEFORE INSERT TRIGGER.
- **FR2**: Detects duplicates by hashing content (SHA-256); flags if hash exists in last 24h via TRIGGER.
- **FR3**: Flags users exceeding burst and sustained rate limits via window functions. Limits are dynamically read from `tenant_config`.
- **FR4**: Flags content on 3+ user reports (report_threshold read from `tenant_config`) within 7 days.
- **FR5**: Computes trust score dynamically via stored procedure. Formula weights are dynamically read from `tenant_config` to eliminate hardcoded values.
- **FR6**: Creates views: vw_flagged_content (all active flags), vw_top_users (trust_score > 80), vw_audit_log (immutable decision traces).
- **FR7**: Trigger auto-runs score recalculation on flag resolution or report validation.
- **FR8 (Post-Launch)**: System shall execute asynchronous Case-Based Reasoning by calculating vector distance (`pgvector`) between current flagged content and historically resolved content.
- **FR9 (Post-Launch)**: System shall detect coordinated attack graphs (Botnets) via Phase 4 SQL variance checks, followed by Phase 6+ Python Isolation Forest sidecars.
- **FR10 (Post-Launch)**: System shall utilize N-gram analysis on the `report` table to automatically extract high-probability evasion phrases and propose new banned words.

---  

## **4. External Interface Requirements**  
### **4.1 User Interfaces**

Web dashboard (future): Tables from vw_flagged_content/vw_top_users; forms for reports/bans. Console access via pgAdmin/MySQL Workbench for SQL queries.

### **4.2 Hardware Interfaces**

None; database server handles all processing.

### **4.3 Software Interfaces**

DBMS native (PostgreSQL/MySQL); optional ODBC/JDBC for reporting tools.

### **4.4 Communications Interfaces**

HTTP APIs (if web layer added) for content submission; internal via SQL transactions.  

---  

## **5. Nonfunctional Requirements**  
### **5.1 Performance Requirements**

Handles 10,000 daily posts with <100ms query time; scales to 1M rows via indexes on content_hash, timestamp, user_id.

### **5.2 Safety Requirements**

Nonapplicable; no physical systems.

### **5.3 Security Requirements**

Role-based access (admin read/write flags); audit all changes; SQL injection prevention via parameterized procedures. Encrypt sensitive user data.

### **5.4 Software Quality Attributes**

- **Maintainability**: All logic in SQL comments; modular procedures.
- **Usability**: Views simplify queries for non-experts.
- **Reliability**: 99% flag accuracy via rules; transactions ensure consistency.
- **Portability**: Standard SQL-99 compliant.
- **Auditability**: Logs every flag/score change with timestamps/reasons.
