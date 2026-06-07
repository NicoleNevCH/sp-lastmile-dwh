{#
    BONUS — the idiomatic dbt way to do SCD Type 2.

    dim_driver reconstructs the full version history from the event stream in a
    single run, which is what this simulation needs (history already exists in
    the data). A dbt *snapshot*, by contrast, captures change over wall-clock
    time across repeated runs: each time you re-run the simulator and then
    `dbt snapshot`, any driver whose latest modal/plate changed gets its old row
    closed (dbt_valid_to set) and a new row opened — the canonical production
    pattern when the source only ever shows the *current* state.

    Strategy 'check' versions a row whenever vehicle_type or vehicle_plate
    changes for a given driver_id. Reads the masked staging layer, so no PII is
    ever snapshotted.

    Run with:  dbt snapshot --profiles-dir .
#}

{% snapshot driver_vehicle_snapshot %}

{{
    config(
        target_schema='snapshots',
        unique_key='driver_id',
        strategy='check',
        check_cols=['vehicle_type', 'vehicle_plate'],
        invalidate_hard_deletes=True
    )
}}

-- Current state per driver = the modal/plate on their most recent delivery.
select distinct on (driver_id)
    driver_id,
    driver_name,
    driver_cpf_hash,
    vehicle_type,
    vehicle_plate
from {{ ref('stg_deliveries') }}
order by driver_id, delivered_at desc

{% endsnapshot %}
