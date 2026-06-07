package com.transportadora.chaos.domain;

import java.time.OffsetDateTime;

/**
 * A continuous period during which a driver operated a specific modal + plate.
 * A driver with two stints (e.g. MOTORCYCLE then VAN) is exactly what produces
 * a new version in the SCD Type 2 dimension.
 */
public record VehicleStint(
        VehicleType type,
        String plate,
        OffsetDateTime validFrom
) {}
