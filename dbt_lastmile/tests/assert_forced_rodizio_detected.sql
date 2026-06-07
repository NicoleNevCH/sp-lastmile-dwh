/*
    End-to-end chaos detection #1.

    The chaos engine forces a slice of trucks onto Avenida Paulista (inside the
    Centro Expandido) at an evening-peak time on a day their plate is blocked.
    The analytics layer derives INFRACTION_RISK independently, from raw GPS +
    time via PostGIS — it never reads the simulator's flag. This test confirms
    the two agree: every forced violation must surface as an infraction.

    A non-zero result means the pipeline missed an injected violation. Must
    return zero rows.
*/

select
    event_id,
    vehicle_plate,
    delivered_local,
    is_in_restricted_zone,
    is_rodizio_restricted_day,
    is_peak_hour,
    infraction_risk
from {{ ref('fct_deliveries') }}
where sim_forced_rodizio_violation
  and not infraction_risk
