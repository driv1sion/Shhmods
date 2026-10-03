-- V1__init_extensions_and_roles.sql

-- Enable required extensions
-- pg_trgm: trigram similarity for fuzzy/leetspeak matching (covers all our text matching needs)
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- pgcrypto: cryptographic hashing for PII (IP addresses)
CREATE EXTENSION IF NOT EXISTS pgcrypto;
-- pgvector: semantic embeddings for Case-Based Reasoning.
-- Requires the vector extension to be installed on the server (e.g., `apt install postgresql-16-pgvector`).
-- Uncomment when ready for Case-Based Reasoning:
-- CREATE EXTENSION IF NOT EXISTS vector;

-- Create least-privilege app user (used by Spring Boot API — INSERT/SELECT on specific tables only)
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_user') THEN
    CREATE ROLE app_user WITH LOGIN PASSWORD '${app_user_password}';
  END IF;
END
$$;

-- Create admin role (used by admin dashboard — full read, limited write)
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'shhmods_admin') THEN
    CREATE ROLE shhmods_admin WITH LOGIN PASSWORD '${admin_password}';
  END IF;
END
$$;
