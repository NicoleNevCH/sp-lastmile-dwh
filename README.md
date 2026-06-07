# SP Last-Mile Logistics Data Warehouse

A corporate-style data-engineering project that simulates a São Paulo last-mile
delivery carrier end to end:

- a **Spring Boot** "chaos engine" that spins up a thread pool of *trucks*,
  generates thousands of realistic delivery events, and bulk-loads them into
  **PostgreSQL + PostGIS** via `JdbcTemplate` batch inserts; and
- a **dbt Core** project that masks PII, runs PostGIS spatial joins, and builds
  a dimensional **star schema** with an **SCD Type 2** fleet dimension.

The simulator deliberately injects business complexity — rodízio violations and
a flooded Marginal Tietê — and the analytics layer has to *rediscover* that
truth from raw GPS and timestamps, never from the simulator's own flags.

---

## Architecture

```
                 ┌──────────────────────────┐
                 │   Spring Boot core app    │   ThreadPoolTaskExecutor
                 │     "chaos-engine"        │   = a fleet of trucks
                 │                           │
   trucks ──▶    │  FleetFactory             │   • valid pt-BR CPFs, Mercosul
   (threads)     │  TruckSimulatorService    │     plates, SP addresses
                 │  ChaosService             │   • forced rodízio invasions
                 │  BatchIngestionService    │   • flood delay penalties
                 └────────────┬──────────────┘
                              │ JDBC batch INSERT (rewriteBatchedInserts)
                              ▼
        ┌─────────────────────────────────────────────┐
        │           PostgreSQL + PostGIS                │
        │                                               │
        │  raw.delivery_events   ← landing zone         │
        └───────────────────────┬───────────────────────┘
                                 │  dbt Core
            ┌────────────────────┼─────────────────────────────┐
            ▼                    ▼                             ▼
   staging (views)      intermediate / geo (tables)     analytics (tables)
   ─ stg_deliveries     ─ geo_sp_zones (GiST index)     ─ dim_date
     = LGPD boundary    ─ int_deliveries_enriched         ─ dim_location
       (PII hashed)       = PostGIS ST_Contains +         ─ dim_driver  (SCD2)
                            rodízio + SLA logic           ─ fct_deliveries
```

Schema layout in the database: `raw` (Spring writes here), `staging` and
`analytics` (dbt builds here), `snapshots` (bonus dbt snapshot).

---

## The four business rules

1. **LGPD / governance.** The first dbt layer (`stg_deliveries`, a view) is the
   privacy boundary. Personal identifiers — CPF, recipient name, street, house
   number — are dropped or replaced with a salted, one-way **SHA-256** token.
   Analysts downstream only ever see regional grain (neighborhood + 5-digit
   CEP). A structural test asserts the clear-text columns no longer exist.

2. **Rodízio geo-intelligence.** `int_deliveries_enriched` crosses each
   delivery's GPS fix with the *Centro Expandido* polygon using PostGIS
   `ST_Contains`, joins the plate's last digit to the `rodizio_schedule` seed,
   and checks the peak window. `INFRACTION_RISK = inside the zone AND restricted
   weekday for that plate AND peak hour`.

3. **Star schema with SCD Type 2.** `dim_driver` versions the fleet: when a
   driver switches modal (motorcycle → van) or plate, the old row is closed
   (`valid_to`) and a new one opened (`is_current`). `fct_deliveries` resolves
   the driver version that was valid **at delivery time**.

4. **Chaos engine.** A slice of trucks is forced onto Avenida Paulista at an
   evening-peak time on a day their plate is blocked; another slice has routes
   crossing a flood polygon on the Marginal Tietê, with a heavy `delivered_at`
   penalty. The pipeline must catch the infractions and SLA breaches amid
   massive parallel inserts. Two end-to-end tests confirm every injected
   violation/breach is independently re-derived.

---

## Prerequisites

- **Docker** + Docker Compose
- **JDK 17+**
- **Maven 3.9+** (the `chaos-engine` is a standard Maven project)
- **Python 3.9+** with dbt:
  ```bash
  pip install dbt-postgres        # pulls in dbt-core
  ```

---

## Run it

### 1. Start the warehouse

```bash
docker compose up -d
```

On first boot this provisions PostGIS + pgcrypto and the `raw` / `staging` /
`analytics` schemas (`infra/init/00_init.sql`). Wait for the container health
check to go healthy (`docker compose ps`).

### 2. Generate and ingest the data (Spring Boot)

```bash
cd chaos-engine
mvn -q spring-boot:run
```

With `simulation.auto-start=true` (the default) it runs one simulation on
startup and bulk-loads the events. The startup DDL (`schema-staging.sql`)
creates `raw.delivery_events` automatically.

Override the knobs without editing files:

```bash
mvn -q spring-boot:run \
  -Dspring-boot.run.arguments="--simulation.trucks=500 --simulation.deliveries-per-truck=40"
```

Or build a jar and run it:

```bash
mvn -q clean package
java -jar target/chaos-engine-1.0.0.jar
```

Prefer to trigger runs on demand? Set `simulation.auto-start=false` and call:

```bash
curl -X POST http://localhost:8080/api/simulations/run
```

### 3. Build the analytics (dbt)

```bash
cd ../dbt_lastmile
dbt deps  --profiles-dir .
dbt build --profiles-dir .     # seeds + models + snapshot + tests, in DAG order
```

`profiles.yml` ships in the project, so `--profiles-dir .` keeps everything
self-contained (no `~/.dbt` setup needed).

---

## Verify

After `dbt build`, every test should pass. A few things to look at:

```sql
-- Headline counts
select
    count(*)                                  as deliveries,
    count(*) filter (where infraction_risk)   as rodizio_infractions,
    count(*) filter (where sla_breach)        as sla_breaches
from analytics.fct_deliveries;

-- The SCD2 dimension in action (drivers with more than one version)
select driver_id, version, vehicle_type, vehicle_plate, valid_from, valid_to, is_current
from analytics.dim_driver
where driver_id in (
    select driver_id from analytics.dim_driver group by driver_id having count(*) > 1
)
order by driver_id, version;
```

The two analyses under `analyses/` (`deliveries_overview`,
`driver_scd2_infractions`) are ready-made BI queries — compile them with
`dbt compile` and find the SQL under `target/compiled/...`.

Key data-quality / logic guarantees enforced by `dbt test`:

- `assert_no_pii_leak` / `assert_pii_is_hashed` — the LGPD boundary holds.
- `assert_infraction_logic` / `assert_sla_logic` — the flags match their
  definitions.
- `assert_forced_rodizio_detected` / `assert_flood_breaches_sla` — every chaos
  event injected by the simulator is independently caught by the pipeline.

---

## Project structure

```
sp-lastmile-dwh/
├── docker-compose.yml              PostGIS warehouse
├── infra/init/00_init.sql          extensions + schema layout (first boot)
├── chaos-engine/                   Spring Boot simulator + ingestion
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/transportadora/chaos/
│       │   ├── config/             executor, properties, SP geo zones, startup
│       │   ├── domain/             Driver, VehicleStint, DriverProfile (SCD2 seed)
│       │   ├── model/              DeliveryEvent (one row in raw)
│       │   ├── service/            fleet, rodízio calendar, chaos, batch insert
│       │   └── web/                REST trigger
│       └── resources/
│           ├── application.yml     simulation + executor + Hikari knobs
│           └── schema-staging.sql  raw.delivery_events DDL (startup)
└── dbt_lastmile/                   dbt Core star schema
    ├── dbt_project.yml
    ├── profiles.yml                local postgres profile
    ├── packages.yml                dbt_utils
    ├── seeds/rodizio_schedule.csv  weekday → blocked plate digits
    ├── macros/                     PII masking, PostGIS helpers, schema naming
    ├── models/
    │   ├── staging/                stg_deliveries (LGPD boundary) + source/tests
    │   ├── geo/                    geo_sp_zones (indexed PostGIS polygons)
    │   ├── intermediate/           int_deliveries_enriched (the analytical brain)
    │   └── marts/                  dim_date, dim_location, dim_driver, fct_deliveries
    ├── snapshots/                  bonus idiomatic SCD2 snapshot
    ├── analyses/                   example BI queries
    └── tests/                      singular data + logic tests
```

---

## Notes on key design decisions

- **The simulator's flags are not trusted as truth.** `sim_forced_rodizio_violation`
  and `sim_route_crossed_flood` are provenance only. `INFRACTION_RISK` and
  `SLA_BREACH` are recomputed in dbt from raw geometry and time; the singular
  tests then cross-check that the two agree. That is the whole point — the
  warehouse earns its conclusions.

- **SCD2 is reconstructed from the event stream** in a single dbt run, because
  the history already exists in the data. Each `(vehicle_type, vehicle_plate)`
  period a driver operates becomes a version with a half-open
  `[valid_from, valid_to)` window, so every delivery resolves to exactly one
  version with no gaps or overlaps. The `snapshots/` model shows the
  complementary, idiomatic dbt approach for capturing change over repeated
  production runs.

- **Forced rodízio violators are single-stint drivers**, so their plate is
  constant and the violation is unambiguous; multi-stint drivers demonstrate
  the SCD2 versioning through their normal deliveries.

- **The Java polygons and the dbt WKT are kept in lockstep.** `GeoZones.java`
  places GPS fixes; the `sp_geo.sql` macro judges containment in PostGIS. The
  coordinate order is `[lng, lat]` everywhere (`ST_MakePoint(lng, lat)`).

- **Times are evaluated in `America/Sao_Paulo`** so weekday and peak-hour logic
  is correct regardless of the database session timezone.
