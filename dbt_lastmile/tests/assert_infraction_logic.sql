/*
    Internal consistency of the headline rodízio metric. INFRACTION_RISK must be
    true exactly when all three conditions hold: inside the restricted zone, on
    a day the plate is restricted, during a peak window. Any row where the flag
    disagrees with its definition is a logic bug. Must return zero rows.
*/

select
    event_id,
    is_in_restricted_zone,
    is_rodizio_restricted_day,
    is_peak_hour,
    infraction_risk
from {{ ref('fct_deliveries') }}
where infraction_risk
      is distinct from
      (is_in_restricted_zone and is_rodizio_restricted_day and is_peak_hour)
