package com.transportadora.chaos.service;

import com.transportadora.chaos.config.GeoZones;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The "business chaos" the engine injects on top of raw volume:
 *
 * <ol>
 *   <li><b>Rodízio invasion</b> — a slice of trucks is forced to deliver on
 *   Avenida Paulista (inside the Centro Expandido) at an evening-peak time on a
 *   day their plate is restricted. The dbt layer must catch this via PostGIS
 *   {@code ST_Contains} even amid massive parallel inserts.</li>
 *
 *   <li><b>Flood bottleneck</b> — routes crossing the flooded Marginal Tietê
 *   polygon get their {@code delivered_at} heavily penalised, so the analytics
 *   layer has to compute {@code SLA_BREACH} from the distorted timeline.</li>
 * </ol>
 */
@Service
public class ChaosService {

    private final GeoUtil geo;
    private final RodizioCalendar rodizio;

    public ChaosService(GeoUtil geo, RodizioCalendar rodizio) {
        this.geo = geo;
        this.rodizio = rodizio;
    }

    /** A GPS fix somewhere on Avenida Paulista. */
    public double[] forcedRodizioPoint() {
        return geo.pointOnSegment(GeoZones.PAULISTA_START, GeoZones.PAULISTA_END);
    }

    /**
     * A delivery instant pinned to a day/peak-time where {@code plate} is
     * actually restricted, so the violation is genuine and reproducible.
     */
    public OffsetDateTime forcedRodizioInstant(String plate, OffsetDateTime historyStart, OffsetDateTime now) {
        return rodizio.mostRecentRestrictedPeakInstant(plate, historyStart, now);
    }

    /** A GPS fix inside the flooded Marginal Tietê polygon. */
    public double[] floodPoint() {
        return geo.pointInside(GeoZones.MARGINAL_TIETE_FLOOD);
    }

    /**
     * Penalty (in minutes) added to a delivery whose route crosses the flood
     * zone — a heavy, noisy delay that pushes deliveries past their SLA.
     */
    public long floodDelayMinutes() {
        return 120 + ThreadLocalRandom.current().nextLong(0, 121); // 120–240 min
    }

    public boolean rolls(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }
}
