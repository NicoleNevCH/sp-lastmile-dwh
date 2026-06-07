/*
    Internal consistency of the SLA metric: SLA_BREACH must be true exactly when
    the door-to-door duration exceeds the configured threshold. Must return zero
    rows.
*/

select
    event_id,
    delivery_minutes,
    sla_threshold_minutes,
    sla_breach
from {{ ref('fct_deliveries') }}
where sla_breach is distinct from (delivery_minutes > sla_threshold_minutes)
