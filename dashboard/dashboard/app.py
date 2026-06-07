"""
SP Last-Mile Logistics — Operations Dashboard
=============================================

A Streamlit front-end for the dbt star schema built by this project. It reads the
`analytics` marts straight from the PostgreSQL + PostGIS warehouse and turns them
into an operations view: headline KPIs, an interactive São Paulo map of deliveries
and rodízio infractions, SLA / chaos analytics, and the SCD Type 2 fleet timeline.

Run it (with the warehouse up and `dbt build` already run):

    streamlit run app.py

Connection is configured via environment variables, with local defaults:
    PGHOST=localhost PGPORT=5432 PGDATABASE=lastmile PGUSER=logistics PGPASSWORD=logistics
The optional "run simulation" button calls the Spring Boot chaos engine at
    CHAOS_ENGINE_URL=http://localhost:8080
"""

from __future__ import annotations

import json
import os

import numpy as np
import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
import pydeck as pdk
import requests
import streamlit as st
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError

# --------------------------------------------------------------------------------------
# Page + theme
# --------------------------------------------------------------------------------------

st.set_page_config(
    page_title="SP Last-Mile — Ops",
    page_icon="🛵",
    layout="wide",
    initial_sidebar_state="expanded",
)

# Palette (mirrors .streamlit/config.toml)
CYAN = "#26C6DA"
AMBER = "#FFB300"
RED = "#EF5350"
GREEN = "#66BB6A"
MUTED = "#8AA0B3"
SURFACE = "#141B24"
GRID = "#1E2A36"

# deck.gl RGBA colors
C_INFRACTION = [239, 83, 80, 210]
C_FLOOD = [255, 167, 38, 170]
C_NORMAL = [38, 198, 218, 70]

CARTO_DARK = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

CUSTOM_CSS = """
<style>
@import url('https://fonts.googleapis.com/css2?family=Archivo:wght@600;700;800&family=IBM+Plex+Mono:wght@500;600&family=Public+Sans:wght@400;500;600&display=swap');

html, body, [class*="css"], .stMarkdown, .stText { font-family: 'Public Sans', sans-serif; }
h1, h2, h3, h4 { font-family: 'Archivo', sans-serif !important; letter-spacing: -0.02em; }

/* Header band */
.ops-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 22px; margin-bottom: 8px;
  background: linear-gradient(135deg, #10161E 0%, #0B0F14 60%);
  border: 1px solid #1E2A36; border-radius: 14px;
  box-shadow: inset 0 0 0 1px rgba(38,198,218,0.05);
}
.ops-title { font-family:'Archivo',sans-serif; font-weight:800; font-size:1.55rem; color:#E6EDF3; line-height:1.1; }
.ops-sub { color:#8AA0B3; font-size:0.86rem; margin-top:3px; }
.ops-pill {
  font-family:'IBM Plex Mono',monospace; font-size:0.72rem; color:#0B0F14;
  background:#26C6DA; padding:5px 11px; border-radius:999px; font-weight:600;
  white-space:nowrap;
}

/* Metric cards */
[data-testid="stMetric"] {
  background: linear-gradient(180deg,#141B24 0%,#10161E 100%);
  border: 1px solid #1E2A36; border-left: 3px solid #26C6DA;
  padding: 14px 16px 12px 16px; border-radius: 12px;
}
[data-testid="stMetricValue"] { font-family:'IBM Plex Mono',monospace; font-size:1.7rem; font-weight:600; }
[data-testid="stMetricLabel"] p { text-transform:uppercase; letter-spacing:.08em; font-size:.72rem !important; color:#8AA0B3 !important; }

/* Alert-flavored metric cards (2nd and 3rd) */
div[data-testid="stHorizontalBlock"] > div:nth-child(2) [data-testid="stMetric"] { border-left-color:#EF5350; }
div[data-testid="stHorizontalBlock"] > div:nth-child(3) [data-testid="stMetric"] { border-left-color:#FFB300; }

/* Tabs */
button[data-baseweb="tab"] { font-family:'Archivo',sans-serif; font-weight:600; letter-spacing:.01em; }

/* Tighten top padding */
.block-container { padding-top: 1.6rem; }
</style>
"""

st.markdown(CUSTOM_CSS, unsafe_allow_html=True)


# --------------------------------------------------------------------------------------
# Database access
# --------------------------------------------------------------------------------------

@st.cache_resource(show_spinner=False)
def get_engine():
    host = os.getenv("PGHOST", "localhost")
    port = os.getenv("PGPORT", "5432")
    db = os.getenv("PGDATABASE", "lastmile")
    user = os.getenv("PGUSER", "logistics")
    pwd = os.getenv("PGPASSWORD", "logistics")
    url = f"postgresql+psycopg2://{user}:{pwd}@{host}:{port}/{db}"
    return create_engine(url, pool_pre_ping=True)


DELIVERIES_SQL = """
    select
        f.event_id,
        f.latitude,
        f.longitude,
        f.status,
        f.vehicle_type,
        f.vehicle_plate,
        f.delivered_local,
        f.delivery_hour,
        f.delivery_minutes,
        f.is_peak_hour,
        f.is_in_restricted_zone,
        f.is_in_flood_zone,
        f.is_rodizio_restricted_day,
        f.infraction_risk,
        f.sla_breach,
        f.sla_threshold_minutes,
        f.sim_forced_rodizio_violation,
        f.sim_route_crossed_flood,
        d.date_day,
        d.day_name,
        d.iso_dow,
        l.neighborhood,
        l.cep5,
        dr.driver_id,
        dr.version
    from analytics.fct_deliveries f
    join analytics.dim_date     d  on d.date_sk     = f.date_sk
    join analytics.dim_location l  on l.location_sk = f.location_sk
    join analytics.dim_driver   dr on dr.driver_sk  = f.driver_sk
"""

DRIVERS_SQL = """
    select
        driver_id, version, driver_name, vehicle_type, vehicle_plate,
        plate_last_digit, valid_from, valid_to, is_current, deliveries_in_version
    from analytics.dim_driver
    order by driver_id, version
"""

ZONES_SQL = "select zone_name, description, ST_AsGeoJSON(geom) as gj from staging.geo_sp_zones"


@st.cache_data(ttl=600, show_spinner="Loading deliveries from the warehouse…")
def load_deliveries() -> pd.DataFrame:
    eng = get_engine()
    df = pd.read_sql(DELIVERIES_SQL, eng)
    # Normalize types
    for c in ["infraction_risk", "sla_breach", "is_peak_hour", "is_in_restricted_zone",
              "is_in_flood_zone", "is_rodizio_restricted_day",
              "sim_forced_rodizio_violation", "sim_route_crossed_flood"]:
        df[c] = df[c].astype(bool)
    df["delivered_local"] = pd.to_datetime(df["delivered_local"])
    df["date_day"] = pd.to_datetime(df["date_day"]).dt.date
    df["delivery_minutes"] = pd.to_numeric(df["delivery_minutes"], errors="coerce")
    return df


@st.cache_data(ttl=600, show_spinner=False)
def load_drivers() -> pd.DataFrame:
    eng = get_engine()
    df = pd.read_sql(DRIVERS_SQL, eng)
    df["valid_from"] = pd.to_datetime(df["valid_from"], utc=True)
    df["valid_to"] = pd.to_datetime(df["valid_to"], utc=True)
    return df


@st.cache_data(ttl=600, show_spinner=False)
def load_zones() -> list[dict]:
    eng = get_engine()
    rows = pd.read_sql(ZONES_SQL, eng)
    zones = []
    for _, r in rows.iterrows():
        gj = json.loads(r["gj"])
        # Polygon -> coordinates[0] is the outer ring as [[lng,lat], ...]
        ring = gj["coordinates"][0]
        if r["zone_name"] == "flood_marginal_tiete":
            fill, line = [255, 167, 38, 55], [255, 167, 38, 220]
        else:
            fill, line = [38, 198, 218, 22], [38, 198, 218, 200]
        zones.append({
            "name": r["zone_name"],
            "description": r["description"],
            "polygon": [[float(x), float(y)] for x, y in ring],
            "fill_color": fill,
            "line_color": line,
        })
    return zones


def to_naive_sp(s: pd.Series) -> pd.Series:
    """tz-aware -> São Paulo wall-clock, tz dropped (for plotting)."""
    s = pd.to_datetime(s, utc=True)
    return s.dt.tz_convert("America/Sao_Paulo").dt.tz_localize(None)


# --------------------------------------------------------------------------------------
# Load (with a friendly failure message)
# --------------------------------------------------------------------------------------

try:
    deliveries = load_deliveries()
    drivers = load_drivers()
    zones = load_zones()
except SQLAlchemyError as e:
    st.error(
        "Couldn't reach the warehouse. Make sure the database container is running "
        "(`docker compose up -d`) and that you've loaded data and run `dbt build`.\n\n"
        f"Details: `{type(e).__name__}`"
    )
    st.stop()
except Exception as e:  # e.g. tables not built yet
    st.error(
        "Connected, but couldn't read the `analytics` marts. Have you run the Spring "
        "Boot ingestion and then `dbt build --profiles-dir .`?\n\n"
        f"Details: `{type(e).__name__}: {e}`"
    )
    st.stop()

if deliveries.empty:
    st.warning("The warehouse has no deliveries yet. Run the chaos engine, then `dbt build`.")
    st.stop()


# --------------------------------------------------------------------------------------
# Header
# --------------------------------------------------------------------------------------

pg_host = os.getenv("PGHOST", "localhost")
pg_db = os.getenv("PGDATABASE", "lastmile")
st.markdown(
    f"""
    <div class="ops-header">
      <div>
        <div class="ops-title">SP Last-Mile · Operations</div>
        <div class="ops-sub">Rodízio enforcement &amp; SLA monitoring over a simulated São Paulo delivery fleet</div>
      </div>
      <div class="ops-pill">● {pg_db}@{pg_host}</div>
    </div>
    """,
    unsafe_allow_html=True,
)


# --------------------------------------------------------------------------------------
# Sidebar — filters + controls
# --------------------------------------------------------------------------------------

with st.sidebar:
    st.header("Filters")

    dmin, dmax = deliveries["date_day"].min(), deliveries["date_day"].max()
    if dmin == dmax:
        date_range = (dmin, dmax)
        st.caption(f"Single day of data: {dmin}")
    else:
        date_range = st.slider(
            "Delivery date",
            min_value=dmin, max_value=dmax, value=(dmin, dmax), format="MMM DD",
        )

    all_hoods = sorted(deliveries["neighborhood"].dropna().unique().tolist())
    hoods = st.multiselect("Neighborhood", all_hoods, default=[],
                           help="Leave empty to include all neighborhoods")

    all_vtypes = ["MOTORCYCLE", "VAN", "TRUCK"]
    vtypes = st.multiselect("Vehicle type", all_vtypes, default=all_vtypes)

    only_infractions = st.toggle("Only rodízio infractions", value=False)

    st.divider()
    st.header("Chaos engine")
    st.caption(
        "Trigger a fresh simulation run on the Spring Boot engine. Each run "
        "appends ~15k events, then the dashboard reloads. The engine must be "
        "running (`mvn spring-boot:run`)."
    )
    engine_url = st.text_input(
        "Engine URL", value=os.getenv("CHAOS_ENGINE_URL", "http://localhost:8080")
    )
    run_clicked = st.button("▶  Run simulation", use_container_width=True)
    if run_clicked:
        try:
            with st.spinner("Asking the fleet to hit the streets…"):
                resp = requests.post(f"{engine_url.rstrip('/')}/api/simulations/run", timeout=180)
                resp.raise_for_status()
                payload = resp.json()
            rows = int(payload.get("rowsInserted", 0) or 0)
            viol = payload.get("forcedViolators", "?")
            ms = payload.get("elapsedMs", "?")
            st.success(f"Inserted {rows:,} rows ({viol} forced violators) in {ms} ms.")
            load_deliveries.clear()
            load_drivers.clear()
            st.rerun()
        except requests.exceptions.RequestException:
            st.warning(
                "Couldn't reach the chaos engine. Start it with `mvn spring-boot:run` "
                "in the `chaos-engine` folder, then try again."
            )

    if st.button("↻  Reload data from warehouse", use_container_width=True):
        load_deliveries.clear()
        load_drivers.clear()
        load_zones.clear()
        st.rerun()


# --------------------------------------------------------------------------------------
# Apply filters
# --------------------------------------------------------------------------------------

df = deliveries.copy()
df = df[(df["date_day"] >= date_range[0]) & (df["date_day"] <= date_range[1])]
if hoods:
    df = df[df["neighborhood"].isin(hoods)]
if vtypes:
    df = df[df["vehicle_type"].isin(vtypes)]
if only_infractions:
    df = df[df["infraction_risk"]]

if df.empty:
    st.warning("No deliveries match the current filters. Loosen them in the sidebar.")
    st.stop()


# --------------------------------------------------------------------------------------
# KPI row
# --------------------------------------------------------------------------------------

total = len(df)
infractions = int(df["infraction_risk"].sum())
breaches = int(df["sla_breach"].sum())
avg_min = float(df["delivery_minutes"].mean())
in_flood = int(df["is_in_flood_zone"].sum())
active_drivers = int(df["driver_id"].nunique())


def pct(n: int) -> str:
    return f"{(100.0 * n / total):.1f}% of total" if total else "—"


k1, k2, k3, k4, k5, k6 = st.columns(6)
k1.metric("Deliveries", f"{total:,}")
k2.metric("Rodízio infractions", f"{infractions:,}", pct(infractions), delta_color="off")
k3.metric("SLA breaches", f"{breaches:,}", pct(breaches), delta_color="off")
k4.metric("Avg delivery", f"{avg_min:.0f} min")
k5.metric("In flood zone", f"{in_flood:,}", pct(in_flood), delta_color="off")
k6.metric("Active drivers", f"{active_drivers:,}")

st.write("")


# --------------------------------------------------------------------------------------
# Plotly helper
# --------------------------------------------------------------------------------------

def style_fig(fig: go.Figure, height: int = 360) -> go.Figure:
    fig.update_layout(
        template="plotly_dark",
        height=height,
        margin=dict(l=10, r=10, t=44, b=10),
        paper_bgcolor="rgba(0,0,0,0)",
        plot_bgcolor="rgba(0,0,0,0)",
        font=dict(family="Public Sans, sans-serif", color="#E6EDF3", size=12),
        title=dict(font=dict(family="Archivo, sans-serif", size=15)),
        legend=dict(bgcolor="rgba(0,0,0,0)"),
    )
    fig.update_xaxes(gridcolor=GRID, zeroline=False)
    fig.update_yaxes(gridcolor=GRID, zeroline=False)
    return fig


# --------------------------------------------------------------------------------------
# Tabs
# --------------------------------------------------------------------------------------

tab_map, tab_ops, tab_fleet, tab_data = st.tabs(
    ["🗺  Map", "📊  Operations", "🚚  Fleet (SCD2)", "🧾  Data"]
)

# ---- MAP -----------------------------------------------------------------------------
with tab_map:
    left, right = st.columns([4, 1])
    with right:
        st.markdown("**Layers**")
        show_normal = st.checkbox("Normal deliveries", value=True)
        show_flood = st.checkbox("Flood-zone", value=True)
        show_infr = st.checkbox("Infractions", value=True)
        st.markdown(
            f"""
            <div style="font-size:.82rem;line-height:1.9;margin-top:8px">
            <span style="color:{CYAN}">●</span> Normal delivery<br/>
            <span style="color:{AMBER}">●</span> Flood-zone delivery<br/>
            <span style="color:{RED}">●</span> Rodízio infraction<br/>
            <span style="color:{CYAN}">▭</span> Centro Expandido<br/>
            <span style="color:{AMBER}">▭</span> Flood polygon
            </div>
            """,
            unsafe_allow_html=True,
        )

    # Categorize each point for color/size
    m = df.copy()
    cat = np.where(m["infraction_risk"], "infraction",
          np.where(m["is_in_flood_zone"], "flood", "normal"))
    m["category"] = cat
    keep = []
    if show_normal:
        keep.append("normal")
    if show_flood:
        keep.append("flood")
    if show_infr:
        keep.append("infraction")
    m = m[m["category"].isin(keep)]

    color_map = {"infraction": C_INFRACTION, "flood": C_FLOOD, "normal": C_NORMAL}
    radius_map = {"infraction": 95, "flood": 70, "normal": 42}
    m["color"] = m["category"].map(color_map)
    m["radius"] = m["category"].map(radius_map)
    m["infraction_label"] = np.where(m["infraction_risk"], "YES", "no")
    m["flood_label"] = np.where(m["sim_route_crossed_flood"], "flood route", "—")
    # Draw infractions last (on top)
    order = {"normal": 0, "flood": 1, "infraction": 2}
    m = m.sort_values(by="category", key=lambda s: s.map(order))

    with left:
        if m.empty:
            st.info("No points for the selected layers.")
        else:
            poly_layer = pdk.Layer(
                "PolygonLayer",
                data=zones,
                get_polygon="polygon",
                get_fill_color="fill_color",
                get_line_color="line_color",
                line_width_min_pixels=2,
                stroked=True,
                filled=True,
                pickable=False,
            )
            scatter = pdk.Layer(
                "ScatterplotLayer",
                data=m,
                get_position=["longitude", "latitude"],
                get_fill_color="color",
                get_radius="radius",
                radius_min_pixels=2,
                radius_max_pixels=10,
                pickable=True,
                opacity=0.85,
            )
            view = pdk.ViewState(latitude=-23.56, longitude=-46.64, zoom=10.2, pitch=0)
            tooltip = {
                "html": "<b>{neighborhood}</b><br/>{vehicle_type} · {status}"
                        "<br/>Infraction: {infraction_label}"
                        "<br/>{delivery_minutes} min · {flood_label}",
                "style": {"backgroundColor": "#0B0F14", "color": "#E6EDF3",
                          "fontFamily": "Public Sans, sans-serif", "fontSize": "12px"},
            }
            deck = pdk.Deck(
                layers=[poly_layer, scatter],
                initial_view_state=view,
                map_style=CARTO_DARK,
                tooltip=tooltip,
            )
            st.pydeck_chart(deck, use_container_width=True)
            st.caption(
                f"Showing {len(m):,} of {total:,} deliveries. Forced violators are dropped on "
                "Avenida Paulista (inside Centro Expandido) at peak hour — they surface here in red."
            )

# ---- OPERATIONS ----------------------------------------------------------------------
with tab_ops:
    c1, c2 = st.columns(2)

    # Deliveries + infractions over time
    daily = (df.groupby("date_day")
               .agg(deliveries=("event_id", "size"),
                    infractions=("infraction_risk", "sum"),
                    breaches=("sla_breach", "sum"))
               .reset_index())
    daily["date_day"] = pd.to_datetime(daily["date_day"])
    fig_t = go.Figure()
    fig_t.add_bar(x=daily["date_day"], y=daily["deliveries"], name="Deliveries",
                  marker_color=CYAN, opacity=0.55)
    fig_t.add_trace(go.Scatter(x=daily["date_day"], y=daily["infractions"], name="Infractions",
                               mode="lines+markers", line=dict(color=RED, width=2.5), yaxis="y2"))
    fig_t.update_layout(
        title="Volume & infractions by day",
        yaxis=dict(title="Deliveries"),
        yaxis2=dict(title="Infractions", overlaying="y", side="right", showgrid=False),
        legend=dict(orientation="h", y=1.12, x=0),
    )
    c1.plotly_chart(style_fig(fig_t), use_container_width=True)

    # Infractions by weekday (rodízio pattern)
    dow_order = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    wk = (df.groupby(["iso_dow", "day_name"])
            .agg(infractions=("infraction_risk", "sum"))
            .reset_index()
            .sort_values("iso_dow"))
    fig_w = px.bar(wk, x="day_name", y="infractions",
                   category_orders={"day_name": dow_order},
                   color_discrete_sequence=[AMBER])
    fig_w.update_layout(title="Infractions by weekday", xaxis_title="", yaxis_title="Infractions")
    c2.plotly_chart(style_fig(fig_w), use_container_width=True)

    c3, c4 = st.columns(2)

    # SLA breach rate by neighborhood (min volume guard)
    by_hood = (df.groupby("neighborhood")
                 .agg(deliveries=("event_id", "size"), breaches=("sla_breach", "sum"))
                 .reset_index())
    by_hood = by_hood[by_hood["deliveries"] >= 20].copy()
    by_hood["breach_rate"] = 100.0 * by_hood["breaches"] / by_hood["deliveries"]
    by_hood = by_hood.sort_values("breach_rate", ascending=True).tail(15)
    if by_hood.empty:
        c3.info("Not enough volume per neighborhood for a breach-rate ranking under these filters.")
    else:
        fig_h = px.bar(by_hood, x="breach_rate", y="neighborhood", orientation="h",
                       color="breach_rate", color_continuous_scale=["#1E2A36", AMBER, RED],
                       labels={"breach_rate": "SLA breach %", "neighborhood": ""})
        fig_h.update_layout(title="SLA breach rate by neighborhood (top 15)",
                            coloraxis_showscale=False)
        c3.plotly_chart(style_fig(fig_h, height=420), use_container_width=True)

    # Delivery-time distribution: flood vs normal
    dd = df.copy()
    dd["route"] = np.where(dd["sim_route_crossed_flood"], "Flood route", "Normal route")
    sla_threshold = int(df["sla_threshold_minutes"].iloc[0]) if "sla_threshold_minutes" in df else 90
    fig_d = px.histogram(dd, x="delivery_minutes", color="route", nbins=50, barmode="overlay",
                         color_discrete_map={"Normal route": CYAN, "Flood route": AMBER},
                         labels={"delivery_minutes": "Delivery time (min)", "route": ""})
    fig_d.add_vline(x=sla_threshold, line_dash="dash", line_color=RED,
                    annotation_text=f"SLA {sla_threshold}m", annotation_position="top")
    fig_d.update_layout(title="Delivery-time distribution", yaxis_title="Deliveries")
    c4.plotly_chart(style_fig(fig_d, height=420), use_container_width=True)

    # Vehicle mix
    vm = df["vehicle_type"].value_counts().reset_index()
    vm.columns = ["vehicle_type", "count"]
    fig_v = px.pie(vm, names="vehicle_type", values="count", hole=0.55,
                   color="vehicle_type",
                   color_discrete_map={"MOTORCYCLE": CYAN, "VAN": AMBER, "TRUCK": GREEN})
    fig_v.update_layout(title="Fleet mix (by delivery)")
    fig_v.update_traces(textinfo="percent+label")
    st.plotly_chart(style_fig(fig_v, height=340), use_container_width=True)

# ---- FLEET (SCD2) --------------------------------------------------------------------
with tab_fleet:
    st.markdown(
        "**Slowly Changing Dimension, Type 2.** Each time a driver switches vehicle "
        "(e.g. motorcycle → van) or plate, their old dimension row is closed and a new "
        "version opens. Deliveries always link to the version that was valid at delivery time."
    )

    total_drivers = drivers["driver_id"].nunique()
    versioned = drivers.groupby("driver_id").size()
    multi_ids = versioned[versioned > 1].index.tolist()

    f1, f2, f3 = st.columns(3)
    f1.metric("Drivers", f"{total_drivers:,}")
    f2.metric("Versioned drivers", f"{len(multi_ids):,}", "changed vehicle", delta_color="off")
    f3.metric("Dimension rows", f"{len(drivers):,}")

    if not multi_ids:
        st.info("No multi-version drivers in the current dataset.")
    else:
        multi = drivers[drivers["driver_id"].isin(multi_ids)].copy()

        st.markdown("##### Version history (drivers who changed vehicle)")
        show = multi[["driver_id", "version", "vehicle_type", "vehicle_plate",
                      "valid_from", "valid_to", "is_current", "deliveries_in_version"]].copy()
        show["valid_from"] = to_naive_sp(show["valid_from"])
        vt = to_naive_sp(show["valid_to"])
        show["valid_to"] = np.where(pd.to_datetime(show["valid_to"]).dt.year >= 9999, pd.NaT, vt)
        st.dataframe(show, use_container_width=True, hide_index=True)

        # Timeline for a selected driver
        st.markdown("##### Vehicle timeline")
        sel = st.selectbox("Driver", multi_ids,
                           format_func=lambda i: f"Driver {i} — "
                           f"{multi.loc[multi.driver_id==i,'driver_name'].iloc[0]}")
        d = multi[multi["driver_id"] == sel].copy().sort_values("version")
        cap = to_naive_sp(drivers["valid_from"]).max()
        data_max = pd.Timestamp(deliveries["delivered_local"].max())
        cap = max(cap, data_max)
        d["start"] = to_naive_sp(d["valid_from"])
        end = to_naive_sp(d["valid_to"])
        d["end"] = np.where(pd.to_datetime(d["valid_to"]).dt.year >= 9999,
                            cap, end)
        d["end"] = pd.to_datetime(d["end"])
        d["lane"] = f"Driver {sel}"
        d["label"] = d["vehicle_type"] + " · " + d["vehicle_plate"]
        fig_tl = px.timeline(
            d, x_start="start", x_end="end", y="lane", color="vehicle_type",
            text="label",
            color_discrete_map={"MOTORCYCLE": CYAN, "VAN": AMBER, "TRUCK": GREEN},
        )
        fig_tl.update_yaxes(title="")
        fig_tl.update_layout(title=f"Driver {sel} — vehicle versions over time",
                             showlegend=True)
        st.plotly_chart(style_fig(fig_tl, height=240), use_container_width=True)

# ---- DATA ----------------------------------------------------------------------------
with tab_data:
    st.markdown(f"**{len(df):,} rows** under the current filters.")
    cols = ["event_id", "delivered_local", "neighborhood", "cep5", "vehicle_type",
            "vehicle_plate", "driver_id", "version", "status", "delivery_minutes",
            "is_peak_hour", "is_in_restricted_zone", "is_rodizio_restricted_day",
            "infraction_risk", "is_in_flood_zone", "sla_breach"]
    st.dataframe(df[cols], use_container_width=True, hide_index=True, height=460)
    st.download_button(
        "⬇  Download filtered CSV",
        data=df[cols].to_csv(index=False).encode("utf-8"),
        file_name="sp_lastmile_deliveries_filtered.csv",
        mime="text/csv",
    )

st.caption(
    "Reads the dbt `analytics` marts (fct_deliveries, dim_date, dim_location, dim_driver) "
    "from PostgreSQL + PostGIS. Infraction and SLA flags are derived in the warehouse, not here."
)
