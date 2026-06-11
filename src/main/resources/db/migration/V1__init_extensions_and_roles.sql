-- V1__init_extensions_and_roles.sql

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
-- Note: pgvector might require specific installation depending on the environment.
-- We will enable it if it's available. If it fails, uncomment the next line and handle it later or ignore for this phase.
-- CREATE EXTENSION IF NOT EXISTS vector;

-- Create least-privilege app user
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_user') THEN
    CREATE ROLE app_user WITH LOGIN PASSWORD '${app_user_password}';
  END IF;
END
$$;

-- Create admin role
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'admin') THEN
    CREATE ROLE admin WITH LOGIN PASSWORD '${admin_password}';
  END IF;
END
$$;
