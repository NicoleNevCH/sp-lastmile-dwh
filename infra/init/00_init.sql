-- ============================================================================
--  00_init.sql  —  runs automatically the first time the container is created
--  (docker-entrypoint-initdb.d). Sets up extensions and the schema layout used
--  by both the Spring Boot ingestion engine and dbt.
-- ============================================================================

-- PostGIS comes pre-installed in the postgis/postgis image, but enabling it is
-- idempotent and makes the dependency explicit.
CREATE EXTENSION IF NOT EXISTS postgis;

-- pgcrypto gives us digest() for the SHA-256 one-way hashing used by the
-- LGPD masking layer in dbt.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- Schema layout
--   raw       -> landing zone, written by Spring Boot (no transforms here)
--   staging   -> dbt views: cleaning + PII masking
--   analytics -> dbt marts: the star schema (dims + facts)
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS raw       AUTHORIZATION logistics;
CREATE SCHEMA IF NOT EXISTS staging   AUTHORIZATION logistics;
CREATE SCHEMA IF NOT EXISTS analytics AUTHORIZATION logistics;

GRANT ALL ON SCHEMA raw, staging, analytics TO logistics;
