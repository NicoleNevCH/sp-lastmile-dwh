/*
    End-to-end chaos detection #2.

    Routes the engine sends across the flooded Marginal Tietê get a heavy
    delivered_at penalty (120–240 min on top of a 15–75 min base), which always
    exceeds the 90-minute SLA. The analytics layer recomputes SLA_BREACH purely
    from the timestamps. Every flood-crossing delivery must therefore be flagged
    as breached. Must return zero rows.
*/

select
    event_id,
    delivery_minutes,
    sla_threshold_minutes,
    sla_breach
from {{ ref('fct_deliveries') }}
where sim_route_crossed_flood
  and not sla_breach
