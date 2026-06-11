# Shhmods

A lightweight, database-driven digital content moderation system.

Shhmods automates text moderation and dynamically computes user trust scores directly through database-level logic, avoiding the need for heavy external APIs.

## Architecture

- **Migrations First**: Database state is managed purely through Flyway migrations.
- **Integration Testing**: We utilize Testcontainers to spin up ephemeral PostgreSQL 15 instances during integration tests. This ensures that all database triggers, stored procedures, and schema definitions perform identically to a production environment.
- **Security by Default**: The database leverages Row-Level Security (RLS) to enforce data privacy, ensuring that unprivileged roles cannot bypass application logic.

### Relational Schema

```mermaid
erDiagram
    USER ||--o{ CONTENT : "creates"
    USER ||--o{ REPORT : "submits"
    USER ||--o{ VIOLATION : "commits"
    USER ||--|| TRUST_SCORE : "has"
    CONTENT ||--o{ REPORT : "receives"
    CONTENT ||--o{ CONTENT_FLAG : "generates"
    CONTENT_FLAG ||--o{ VIOLATION : "leads to"

    USER {
        bigint user_id PK
        string username
        string email
        string status
        boolean is_visible
    }
    CONTENT {
        bigint content_id PK
        bigint user_id FK
        text content_text
        string content_type
        string content_hash
    }
    REPORT {
        bigint report_id PK
        bigint content_id FK
        bigint reporter_id FK
        text report_reason
        string status
    }
    CONTENT_FLAG {
        bigint flag_id PK
        bigint content_id FK
        string flag_type
        string status
    }
    VIOLATION {
        bigint violation_id PK
        bigint flag_id FK
        string violation_type
    }
    TRUST_SCORE {
        bigint user_id PK
        decimal trust_score
    }
    BANNED_WORD {
        bigint word_id PK
        string keyword
        string match_type
    }
    AUDIT_LOG {
        bigint audit_id PK
        string action_type
        string reference_type
    }
```

## Getting Started

### Prerequisites
- JDK 21+
- Gradle 8.5+ or 9.5+
- Docker (required for Testcontainers)

### Setup & Verification
To verify the database foundation, run the integration tests. This command will automatically pull the necessary PostgreSQL Docker image, start the container, apply all Flyway migrations (`V1` to `V2`), and verify that the core schema, roles, and extensions (like `pg_trgm`) are created correctly.

```bash
./gradlew test
```

### Running the Application
```bash
./gradlew bootRun
```
