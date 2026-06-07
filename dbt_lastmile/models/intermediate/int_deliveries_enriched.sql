{{
    config(materialized='table')
}}

/*
    The analytical brain. From masked staging rows it derives, independently of
    any flag the simulator set:

      INFRACTION_RISK = inside Centro Expandido (PostGIS ST_Contains)
                        AND plate restricted on that weekday (rodízio seed join)
                        AND delivery time within a peak window

      SLA_BREACH      = (delivered_at - dispatched_at) in minutes
                        > var('sla_threshold_minutes')

    Times are evaluated in America/Sao_Paulo so weekday/hour are correct
    regardless of the session timezone.
*/

with deliveries as (
    select * from {{ ref('stg_deliveries') }}
),

zones as (
    select
        (select geom from {{ ref('geo_sp_zones') }} where zone_name = 'centro_expandido')     as centro_expandido,
        (select geom from {{ ref('geo_sp_zones') }} where zone_name = 'flood_marginal_tiete')  as flood_zone
),

geo as (
    select
        d.*,
        {{ geo_point('d.longitude', 'd.latitude') }}                  as delivery_point,
        (d.delivered_at at time zone 'America/Sao_Paulo')             as delivered_local,
        right(d.vehicle_plate, 1)::int                                as plate_last_digit
    from deliveries d
),

spatial as (
    select
        g.*,
        ST_Contains(z.centro_expandido, g.delivery_point)             as is_in_restricted_zone,
        ST_Contains(z.flood_zone, g.delivery_point)                   as is_in_flood_zone
    from geo g
    cross join zones z
),

timed as (
    select
        s.*,
        extract(isodow from s.delivered_local)::int                   as delivery_iso_dow,
        extract(hour   from s.delivered_local)::int                   as delivery_hour,
        -- SP peak windows: 07:00–10:00 and 17:00–20:00
        (
            extract(hour from s.delivered_local)::int between 7 and 9
            or extract(hour from s.delivered_local)::int between 17 and 19
        )                                                             as is_peak_hour,
        round(extract(epoch from (s.delivered_at - s.dispatched_at)) / 60.0)::int as delivery_minutes
    from spatial s
),

rodizio as (
    select
        t.*,
        (rs.blocked_digit is not null)                                as is_rodizio_restricted_day
    from timed t
    left join {{ ref('rodizio_schedule') }} rs
        on rs.iso_dow = t.delivery_iso_dow
       and rs.blocked_digit = t.plate_last_digit
),

final as (
    select
        event_id,
        driver_id,
        driver_name,
        driver_cpf_hash,
        vehicle_plate,
        vehicle_type,
        plate_last_digit,

        recipient_key,
        house_number_hash,
        neighborhood,
        cep5,
        city,

        latitude,
        longitude,

        dispatched_at,
        delivered_at,
        delivered_local,
        delivery_iso_dow,
        delivery_hour,
        delivery_minutes,
        status,

        is_in_restricted_zone,
        is_in_flood_zone,
        is_rodizio_restricted_day,
        is_peak_hour,

        -- The headline business metrics, derived from raw geo + time only.
        (is_in_restricted_zone and is_rodizio_restricted_day and is_peak_hour) as infraction_risk,

        {{ var('sla_threshold_minutes') }}::int                       as sla_threshold_minutes,
        (delivery_minutes > {{ var('sla_threshold_minutes') }})       as sla_breach,

        -- Carried for validation against the simulator's intent.
        sim_forced_rodizio_violation,
        sim_route_crossed_flood
    from rodizio
)

select * from final
