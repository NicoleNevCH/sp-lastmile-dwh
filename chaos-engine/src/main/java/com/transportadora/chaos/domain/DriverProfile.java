package com.transportadora.chaos.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A driver plus the timeline of modals they have operated. Most drivers have a
 * single stint; a minority change modal mid-history, which the dbt SCD2 model
 * picks up and versions.
 */
public record DriverProfile(
        long id,
        String name,
        String cpf,
        List<VehicleStint> stints   // ordered by validFrom ascending
) {

    /** The stint in effect at instant {@code t}. */
    public VehicleStint stintAt(OffsetDateTime t) {
        VehicleStint active = stints.get(0);
        for (VehicleStint s : stints) {
            if (!s.validFrom().isAfter(t)) {
                active = s;
            } else {
                break;
            }
        }
        return active;
    }
}
