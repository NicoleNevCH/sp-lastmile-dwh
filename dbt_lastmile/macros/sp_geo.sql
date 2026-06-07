{#
    São Paulo geospatial helpers. The WKT rings here MUST match the Java copies
    in GeoZones.java — the simulator places points using those, PostGIS judges
    containment using these.

    geo_point(lng, lat): builds a SRID-tagged WGS84 point geometry.
    centro_expandido_geom(): the rodízio restricted-zone polygon.
    flood_zone_geom(): the flooded Marginal Tietê polygon.
#}

{% macro geo_point(lng, lat) -%}
    ST_SetSRID(ST_MakePoint({{ lng }}, {{ lat }}), {{ var('geo_srid', 4326) }})
{%- endmacro %}


{% macro centro_expandido_geom() -%}
    ST_SetSRID(
        ST_GeomFromText(
            'POLYGON((' ||
            '-46.6510 -23.5090,' ||
            '-46.5750 -23.5170,' ||
            '-46.5680 -23.5450,' ||
            '-46.5750 -23.5870,' ||
            '-46.6300 -23.6230,' ||
            '-46.6850 -23.6230,' ||
            '-46.7350 -23.6000,' ||
            '-46.7300 -23.5450,' ||
            '-46.7050 -23.5180,' ||
            '-46.6510 -23.5090' ||
            '))'
        ),
        {{ var('geo_srid', 4326) }}
    )
{%- endmacro %}


{% macro flood_zone_geom() -%}
    ST_SetSRID(
        ST_GeomFromText(
            'POLYGON((' ||
            '-46.6420 -23.5120,' ||
            '-46.6350 -23.5110,' ||
            '-46.6330 -23.5160,' ||
            '-46.6400 -23.5170,' ||
            '-46.6420 -23.5120' ||
            '))'
        ),
        {{ var('geo_srid', 4326) }}
    )
{%- endmacro %}
