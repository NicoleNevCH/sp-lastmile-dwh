{{
    config(materialized='table')
}}

/*
    Delivery fact — grain: exactly one row per delivery event.

    Foreign keys:
      - date_sk     : calendar day of delivery (America/Sao_Paulo)
      - driver_sk   : the SCD2 driver version in effect AT delivered_at
      - location_sk : destination at neighborhood + cep5 grain

    The headline business measures (infraction_risk, sla_breach) are carried
    straight from the intermediate model, where they were derived from raw geo
    and time via PostGIS — never from the simulator's own flags. Those flags
    (sim_*) ride along only so the singular tests can confirm the pipeline
    re-derived the truth the chaos engine injected.
*/

with deliveries as (
    select * from {{ ref('int_deliveries_enriched') }}
),

dim_date as (
    select date_sk, date_day from {{ ref('dim_date') }}
),

dim_driver as (
    select driver_sk, driver_id, valid_from, valid_to from {{ ref('dim_driver') }}
),

dim_location as (
    select location_sk, neighborhood, cep5 from {{ ref('dim_location') }}
),

final as (
    select
        {{ dbt_utils.generate_surrogate_key(['d.event_id']) }}        as delivery_sk,
        d.event_id,

        -- Dimensional foreign keys.
        dd.date_sk,
        dr.driver_sk,
        dl.location_sk,

        -- Degenerate / descriptive attributes kept on the fact for convenience.
        d.vehicle_type,
        d.vehicle_plate,
        d.status,

        -- Timeline.
        d.dispatched_at,
        d.delivered_at,
        d.delivered_local,
        d.delivery_hour,
        d.delivery_iso_dow,
        d.is_peak_hour,

        -- Geo measures / flags.
        d.latitude,
        d.longitude,
        d.is_in_restricted_zone,
        d.is_in_flood_zone,
        d.is_rodizio_restricted_day,

        -- Headline business metrics (derived upstream from geo + time only).
        d.delivery_minutes,
        d.sla_threshold_minutes,
        d.sla_breach,
        d.infraction_risk,

        -- Simulator provenance — for validation tests, not for analytics.
        d.sim_forced_rodizio_violation,
        d.sim_route_crossed_flood
    from deliveries d
    left join dim_date dd
        on dd.date_day = d.delivered_local::date
    left join dim_driver dr
        on dr.driver_id = d.driver_id
       and d.delivered_at >= dr.valid_from
       and d.delivered_at <  dr.valid_to
    left join dim_location dl
        on dl.neighborhood = d.neighborhood
       and dl.cep5 = d.cep5
)

select * from final
