{{
    config(materialized='table')
}}

/*
    Calendar dimension covering exactly the span of delivery activity observed
    in the warehouse (derived from the data, so it never drifts out of range).

    Beyond the usual calendar attributes it carries the São Paulo rodízio
    context for each weekday — which final plate digits are blocked — so BI can
    slice "deliveries on a restricted weekday" without re-deriving the rule.

    Dates are taken in America/Sao_Paulo (delivered_local) so the calendar grain
    matches how the business reads the clock.
*/

with bounds as (
    select
        min(delivered_local::date) as min_date,
        max(delivered_local::date) as max_date
    from {{ ref('int_deliveries_enriched') }}
),

spine as (
    select
        generate_series(
            (select min_date from bounds),
            (select max_date from bounds),
            interval '1 day'
        )::date as date_day
),

-- One row per weekday with the blocked digits collapsed into an array.
rodizio_by_dow as (
    select
        iso_dow,
        array_agg(blocked_digit order by blocked_digit) as blocked_digits
    from {{ ref('rodizio_schedule') }}
    group by iso_dow
),

final as (
    select
        {{ dbt_utils.generate_surrogate_key(['s.date_day']) }}        as date_sk,
        s.date_day,
        extract(isodow from s.date_day)::int                          as iso_dow,
        trim(to_char(s.date_day, 'Day'))                              as day_name,
        extract(day   from s.date_day)::int                           as day_of_month,
        extract(week  from s.date_day)::int                           as iso_week,
        extract(month from s.date_day)::int                           as month_number,
        trim(to_char(s.date_day, 'Month'))                            as month_name,
        extract(quarter from s.date_day)::int                         as quarter_number,
        extract(year  from s.date_day)::int                           as year_number,
        (extract(isodow from s.date_day)::int >= 6)                   as is_weekend,
        coalesce(r.blocked_digits, array[]::int[])                    as rodizio_blocked_digits,
        (r.blocked_digits is not null)                                as is_rodizio_active_day
    from spine s
    left join rodizio_by_dow r
        on r.iso_dow = extract(isodow from s.date_day)::int
)

select * from final
