/*
    SCD Type 2 showcase.

    Rodízio infractions attributed to the exact fleet version (modal + plate)
    the driver operated at the moment of delivery. Because the fact joins to the
    versioned driver dimension on the delivery's validity window, a driver who
    switched modal mid-history has their infractions correctly split across the
    relevant versions instead of being collapsed onto their latest vehicle.
*/

select
    dr.driver_id,
    dr.version,
    dr.vehicle_type,
    dr.vehicle_plate,
    dr.is_current,
    dr.valid_from,
    dr.valid_to,
    count(*)                                   as deliveries,
    count(*) filter (where f.infraction_risk)  as infractions
from {{ ref('fct_deliveries') }} f
join {{ ref('dim_driver') }} dr on dr.driver_sk = f.driver_sk
group by
    dr.driver_id, dr.version, dr.vehicle_type, dr.vehicle_plate,
    dr.is_current, dr.valid_from, dr.valid_to
having count(*) filter (where f.infraction_risk) > 0
order by infractions desc, dr.driver_id, dr.version
