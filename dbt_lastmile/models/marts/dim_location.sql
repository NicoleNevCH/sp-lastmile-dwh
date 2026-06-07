{{
    config(materialized='table')
}}

/*
    Destination dimension at the regional grain that survives PII masking:
    neighborhood + 5-digit CEP prefix. Street and house number never reach this
    layer (they are dropped/hashed in staging), so this is the finest location
    detail analysts are allowed to see.

    Grain: one row per distinct (neighborhood, cep5) pair actually observed.
*/

with locations as (
    select distinct
        neighborhood,
        cep5,
        city
    from {{ ref('int_deliveries_enriched') }}
    where neighborhood is not null
      and cep5 is not null
)

select
    {{ dbt_utils.generate_surrogate_key(['neighborhood', 'cep5']) }}  as location_sk,
    neighborhood,
    cep5,
    city
from locations
