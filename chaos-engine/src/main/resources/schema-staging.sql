-- ============================================================================
--  schema-staging.sql — executed by Spring Boot on startup (spring.sql.init).
--  Idempotent DDL for the raw landing zone written by the ingestion engine.
--  No business logic lives here: it is a faithful, append-only dump of the
--  events emitted by the trucks. All cleaning happens downstream in dbt.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS raw;

CREATE TABLE IF NOT EXISTS raw.delivery_events (
    event_id          UUID         PRIMARY KEY,

    -- Driver / fleet attributes (the modal a driver uses can change over time,
    -- which is what feeds the SCD2 dimension downstream).
    driver_id         BIGINT       NOT NULL,
    driver_name       TEXT         NOT NULL,
    driver_cpf        TEXT         NOT NULL,      -- PII: masked in staging
    vehicle_plate     TEXT         NOT NULL,
    vehicle_type      TEXT         NOT NULL,      -- MOTORCYCLE | VAN | TRUCK

    -- Recipient / destination. Personal fields here are PII.
    recipient_name    TEXT         NOT NULL,      -- PII
    recipient_cpf     TEXT         NOT NULL,      -- PII: masked in staging
    street            TEXT         NOT NULL,
    house_number      TEXT         NOT NULL,      -- PII: masked in staging
    neighborhood      TEXT         NOT NULL,      -- kept (regional grain)
    cep               TEXT         NOT NULL,      -- truncated to 5 digits in staging
    city              TEXT         NOT NULL,

    -- Geo: the GPS fix where the package was handed over.
    latitude          DOUBLE PRECISION NOT NULL,
    longitude         DOUBLE PRECISION NOT NULL,

    -- Timeline.
    dispatched_at     TIMESTAMPTZ  NOT NULL,
    delivered_at      TIMESTAMPTZ  NOT NULL,      -- penalised by the flood chaos
    status            TEXT         NOT NULL,      -- DELIVERED | FAILED

    -- Provenance flags emitted by the chaos engine. These are NOT trusted as
    -- business truth — dbt recomputes infraction / SLA independently from the
    -- raw geo + time data. They exist so we can validate the analytics layer
    -- against the simulator's intent.
    sim_forced_rodizio_violation BOOLEAN NOT NULL DEFAULT FALSE,
    sim_route_crossed_flood      BOOLEAN NOT NULL DEFAULT FALSE,

    ingested_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Helps the analytical reads that follow (dbt scans by event time).
CREATE INDEX IF NOT EXISTS idx_delivery_events_delivered_at
    ON raw.delivery_events (delivered_at);

CREATE INDEX IF NOT EXISTS idx_delivery_events_driver
    ON raw.delivery_events (driver_id, dispatched_at);
