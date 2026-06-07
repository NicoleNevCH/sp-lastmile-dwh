/*
    Example BI query — compiled by `dbt compile` (or `dbt build`) into
    target/compiled, not materialised as a table.

    Operational overview: for each calendar day and neighborhood, how many
    deliveries ran, how many were rodízio infractions, and the SLA-breach rate —
    stitching the star schema together. Change the grain or filters to answer
    adjacent questions (by weekday, by vehicle_type, current fleet only, etc.).
*/

select
    dd.date_day,
    dd.day_name,
    dl.neighborhood,
    count(*)                                              as deliveries,
    count(*) filter (where f.infraction_risk)             as infractions,
    count(*) filter (where f.sla_breach)                  as sla_breaches,
    round(
        100.0 * count(*) filter (where f.sla_breach) / nullif(count(*), 0)
    , 1)                                                  as sla_breach_pct
from {{ ref('fct_deliveries') }} f
join {{ ref('dim_date') }}     dd on dd.date_sk = f.date_sk
join {{ ref('dim_location') }} dl on dl.location_sk = f.location_sk
group by dd.date_day, dd.day_name, dl.neighborhood
having count(*) filter (where f.infraction_risk) > 0
    or count(*) filter (where f.sla_breach) > 0
order by infractions desc, sla_breaches desc, deliveries desc
