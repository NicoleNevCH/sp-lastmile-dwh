# Operations Dashboard (Streamlit)

An interactive front-end for the dbt star schema. It reads the `analytics` marts
from the PostgreSQL + PostGIS warehouse and renders:

- **KPI cards** — deliveries, rodízio infractions, SLA breaches, avg delivery time,
  flood-zone deliveries, active drivers (all respond to the sidebar filters).
- **Map** — every delivery plotted over São Paulo on a dark Carto basemap, with the
  Centro Expandido and flood polygons drawn from PostGIS. Forced rodízio violators
  show up in red on Avenida Paulista; flood-zone deliveries in amber.
- **Operations** — volume & infractions over time, infractions by weekday (the
  rodízio pattern), SLA breach rate by neighborhood, delivery-time distribution
  (flood vs normal, with the SLA line), and fleet mix.
- **Fleet (SCD2)** — the drivers who changed vehicle, their version history, and a
  timeline of each driver's vehicle versions.
- **Data** — the filtered rows, with a CSV download.

The sidebar also has a **Run simulation** button that calls the Spring Boot chaos
engine to append a fresh batch of events, and a **Reload** button to refresh.

## Prerequisites

The warehouse must be running and populated first (from the project root):

1. `docker compose up -d`
2. `mvn spring-boot:run` in `chaos-engine` (wait for `Simulation complete: ...`)
3. `dbt build --profiles-dir .` in `dbt_lastmile`

## Run

Use a **separate** virtual environment from dbt (its dependencies are unrelated and
pinning them together can cause conflicts). On Windows:

```
cd dashboard
py -3.13 -m venv .venv-dash
.venv-dash\Scripts\activate
pip install -r requirements.txt
streamlit run app.py
```

Streamlit opens the dashboard in your browser (usually http://localhost:8501).

On macOS / Linux, swap the venv activation for `source .venv-dash/bin/activate`.

## Configuration

Connection settings are read from environment variables, with local defaults that
match `docker-compose.yml`:

| Variable          | Default                 |
|-------------------|-------------------------|
| `PGHOST`          | `localhost`             |
| `PGPORT`          | `5432`                  |
| `PGDATABASE`      | `lastmile`              |
| `PGUSER`          | `logistics`             |
| `PGPASSWORD`      | `logistics`             |
| `CHAOS_ENGINE_URL`| `http://localhost:8080` |

## Notes

- Infraction and SLA flags are computed in the warehouse (dbt + PostGIS), not in the
  dashboard — the app only reads and visualizes them.
- Each "Run simulation" appends ~15k events. To reset to a clean slate:
  `docker compose down -v && docker compose up -d`, then re-run the ingestion and
  `dbt build`.
