{{
    config(
        materialized='table',
        post_hook="CREATE INDEX IF NOT EXISTS idx_geo_sp_zones_geom ON {{ this }} USING GIST (geom)"
    )
}}

/*
    Reference polygons used by the spatial logic, materialised as proper PostGIS
    geometries with a GiST index. Two zones:
      - centro_expandido       : the rodízio restricted area
      - flood_marginal_tiete   : the simulated flooded stretch

    Kept as a table so it can also feed maps/BI and so the spatial join in the
    intermediate model reads against an indexed geometry.
*/

select
    'centro_expandido'::text as zone_name,
    'Rodízio restricted area (Centro Expandido)'::text as description,
    {{ centro_expandido_geom() }} as geom

union all

select
    'flood_marginal_tiete'::text as zone_name,
    'Flooded stretch of the Marginal Tietê'::text as description,
    {{ flood_zone_geom() }} as geom
