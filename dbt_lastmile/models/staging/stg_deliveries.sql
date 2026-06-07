{{
    config(materialized='view')
}}

/*
    Staging layer = the LGPD boundary.

    Everything downstream of this view sees ONLY:
      - hashed, irreversible tokens for personal identifiers (CPF, recipient
        name, house number), and
      - regional grain for the destination (neighborhood + 5-digit CEP).

    Raw street address, house number, recipient name and CPF never leave this
    model in clear text. Coordinates are retained because they are required for
    the PostGIS rodízio join and are not, on their own, direct identifiers.
*/

with source as (
    select * from {{ source('raw', 'delivery_events') }}
),

cleaned as (
    select
        event_id,

        -- Driver: operational label kept; CPF hashed (never needed in clear).
        driver_id,
        nullif(trim(driver_name), '')                       as driver_name,
        {{ mask_sha256('driver_cpf') }}                     as driver_cpf_hash,
        upper(trim(vehicle_plate))                          as vehicle_plate,
        upper(trim(vehicle_type))                           as vehicle_type,

        -- Recipient: fully anonymised. recipient_key is a stable hash so the
        -- same customer collapses to the same token without ever being named.
        {{ mask_sha256('recipient_cpf') }}                  as recipient_key,
        {{ mask_sha256('house_number') }}                   as house_number_hash,

        -- Regional grain that analysts ARE allowed to see.
        initcap(trim(neighborhood))                         as neighborhood,
        {{ cep5('cep') }}                                   as cep5,
        initcap(trim(city))                                 as city,

        -- Geo (for the spatial join).
        latitude,
        longitude,

        -- Timeline.
        dispatched_at,
        delivered_at,
        upper(trim(status))                                 as status,

        -- Simulator provenance (used only to validate the analytics, never as
        -- the source of truth for infraction / SLA).
        sim_forced_rodizio_violation,
        sim_route_crossed_flood,

        ingested_at
    from source
)

select * from cleaned
