{{
    config(materialized='table')
}}

/*
    Driver dimension — SCD Type 2.

    The fleet changes over time: a driver promoted from a motorcycle to a van
    (or reassigned to a different plate) must NOT overwrite history. Each
    distinct (vehicle_type, vehicle_plate) a driver operates becomes its own
    versioned row, with a validity window and an is_current flag.

    There is no explicit "fleet change log" in the source — only the delivery
    events, each stamped with the modal/plate in effect at delivery time. So we
    reconstruct the versions from the event stream:

      1. order each driver's deliveries by delivered_at
      2. mark a new version wherever (vehicle_type, vehicle_plate) changes
      3. the version's valid_from is its first observed delivery; valid_to is
         the next version's valid_from (exclusive). The open version gets a
         sentinel far-future valid_to and is_current = true.

    Half-open windows [valid_from, valid_to) chain with no gaps or overlaps, so
    every delivery resolves to exactly one driver version in the fact table.
*/

with deliveries as (
    select
        driver_id,
        driver_name,
        driver_cpf_hash,
        vehicle_type,
        vehicle_plate,
        delivered_at
    from {{ ref('int_deliveries_enriched') }}
),

-- Look back one delivery to detect attribute changes within each driver.
flagged as (
    select
        d.*,
        lag(vehicle_type)  over w as prev_type,
        lag(vehicle_plate) over w as prev_plate
    from deliveries d
    window w as (partition by driver_id order by delivered_at)
),

-- A new version starts on the driver's first event or whenever modal/plate change.
change_points as (
    select
        *,
        case
            when prev_type is null then 1
            when vehicle_type  <> prev_type  then 1
            when vehicle_plate <> prev_plate then 1
            else 0
        end as is_version_start
    from flagged
),

-- Running sum of the start flags = a 1-based version number per driver.
versioned as (
    select
        *,
        sum(is_version_start) over (
            partition by driver_id
            order by delivered_at
            rows between unbounded preceding and current row
        ) as version
    from change_points
),

-- Collapse to one row per (driver, version). Attributes are constant within a
-- version by construction, so min() simply returns that constant value.
version_spans as (
    select
        driver_id,
        version,
        min(driver_name)     as driver_name,
        min(driver_cpf_hash) as driver_cpf_hash,
        min(vehicle_type)    as vehicle_type,
        min(vehicle_plate)   as vehicle_plate,
        min(delivered_at)    as valid_from,
        max(delivered_at)    as last_seen_at,
        count(*)             as deliveries_in_version
    from versioned
    group by driver_id, version
),

-- Close each version at the start of the next one.
scd as (
    select
        vs.*,
        lead(valid_from) over (partition by driver_id order by version) as next_valid_from
    from version_spans vs
)

select
    {{ dbt_utils.generate_surrogate_key(['driver_id', 'version']) }}      as driver_sk,
    driver_id,
    version,
    driver_name,
    driver_cpf_hash,
    vehicle_type,
    vehicle_plate,
    right(vehicle_plate, 1)::int                                          as plate_last_digit,
    valid_from,
    coalesce(next_valid_from, timestamptz '9999-12-31 00:00:00+00')       as valid_to,
    (next_valid_from is null)                                             as is_current,
    last_seen_at,
    deliveries_in_version
from scd
