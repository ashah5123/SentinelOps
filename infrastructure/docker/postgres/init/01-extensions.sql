-- Enable required PostgreSQL extensions for SentinelOps local development.
-- Idempotent: safe to re-run.
CREATE EXTENSION IF NOT EXISTS vector;
