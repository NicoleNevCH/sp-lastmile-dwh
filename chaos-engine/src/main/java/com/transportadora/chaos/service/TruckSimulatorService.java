package com.transportadora.chaos.service;

import com.transportadora.chaos.config.GeoZones;
import com.transportadora.chaos.config.SimulationProperties;
import com.transportadora.chaos.domain.DriverProfile;
import com.transportadora.chaos.domain.VehicleStint;
import com.transportadora.chaos.model.DeliveryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a single truck's shift on a dedicated thread. It generates a burst
 * of delivery events — most ordinary, some warped by the chaos rules — and
 * bulk-inserts them. Many of these run concurrently, mimicking a live fleet
 * hammering the database in parallel.
 */
@Service
public class TruckSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(TruckSimulatorService.class);

    private final RecipientFaker recipients;
    private final GeoUtil geo;
    private final ChaosService chaos;
    private final BatchIngestionService ingestion;
    private final SimulationProperties props;

    public TruckSimulatorService(RecipientFaker recipients, GeoUtil geo, ChaosService chaos,
                                 BatchIngestionService ingestion, SimulationProperties props) {
        this.recipients = recipients;
        this.geo = geo;
        this.chaos = chaos;
        this.ingestion = ingestion;
        this.props = props;
    }

    /**
     * Run one truck's shift.
     *
     * @param driver         the driver/vehicle profile
     * @param forceViolation whether this truck must commit one rodízio violation
     */
    @Async("truckExecutor")
    public CompletableFuture<Integer> runShift(DriverProfile driver, boolean forceViolation) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime historyStart = now.minusDays(props.getHistoryDays());

        int count = props.getDeliveriesPerTruck();
        List<DeliveryEvent> events = new ArrayList<>(count);

        // Pick which delivery (if any) is the forced violation.
        int violationIndex = forceViolation ? ThreadLocalRandom.current().nextInt(count) : -1;

        for (int i = 0; i < count; i++) {
            if (i == violationIndex) {
                events.add(buildViolation(driver, historyStart, now));
            } else {
                events.add(buildOrdinary(driver, historyStart, now));
            }
        }

        int inserted = ingestion.ingest(events, props.getBatchSize());
        log.debug("truck driver={} delivered={} (violation={})", driver.id(), inserted, forceViolation);
        return CompletableFuture.completedFuture(inserted);
    }

    // ----------------------------------------------------------------------

    private DeliveryEvent buildOrdinary(DriverProfile driver, OffsetDateTime historyStart, OffsetDateTime now) {
        OffsetDateTime dispatchedAt = randomDispatch(historyStart, now);
        long baseMinutes = 15 + ThreadLocalRandom.current().nextLong(0, 61); // 15–75 min
        OffsetDateTime deliveredAt = dispatchedAt.plusMinutes(baseMinutes);

        // Resolve the modal in effect at delivery time (drives SCD2 downstream).
        VehicleStint stint = driver.stintAt(deliveredAt);

        double[] point;
        boolean crossedFlood = false;
        if (chaos.rolls(props.getFloodAffectedRatio())) {
            // Route crosses the flooded Marginal Tietê → big delivery penalty.
            point = chaos.floodPoint();
            deliveredAt = deliveredAt.plusMinutes(chaos.floodDelayMinutes());
            crossedFlood = true;
        } else {
            point = geo.randomPointInBox(
                    GeoZones.BBOX_MIN_LNG, GeoZones.BBOX_MAX_LNG,
                    GeoZones.BBOX_MIN_LAT, GeoZones.BBOX_MAX_LAT);
        }

        return assemble(driver, stint, point, dispatchedAt, deliveredAt, false, crossedFlood);
    }

    private DeliveryEvent buildViolation(DriverProfile driver, OffsetDateTime historyStart, OffsetDateTime now) {
        // Forced violators are single-stint drivers, so the plate is stable.
        VehicleStint stint = driver.stintAt(now);
        OffsetDateTime deliveredAt = chaos.forcedRodizioInstant(stint.plate(), historyStart, now);
        long baseMinutes = 15 + ThreadLocalRandom.current().nextLong(0, 61);
        OffsetDateTime dispatchedAt = deliveredAt.minusMinutes(baseMinutes);
        double[] point = chaos.forcedRodizioPoint(); // on Avenida Paulista
        return assemble(driver, stint, point, dispatchedAt, deliveredAt, true, false);
    }

    private DeliveryEvent assemble(DriverProfile driver, VehicleStint stint, double[] point,
                                   OffsetDateTime dispatchedAt, OffsetDateTime deliveredAt,
                                   boolean forcedViolation, boolean crossedFlood) {
        RecipientFaker.Recipient r = recipients.next();
        String status = ThreadLocalRandom.current().nextDouble() < 0.97 ? "DELIVERED" : "FAILED";

        return new DeliveryEvent(
                UUID.randomUUID(),
                driver.id(),
                driver.name(),
                driver.cpf(),
                stint.plate(),
                stint.type().name(),
                r.name(), r.cpf(), r.street(), r.houseNumber(), r.neighborhood(), r.cep(), r.city(),
                point[1],   // latitude
                point[0],   // longitude
                dispatchedAt,
                deliveredAt,
                status,
                forcedViolation,
                crossedFlood
        );
    }

    /** A dispatch timestamp within the history window, biased toward peak hours. */
    private OffsetDateTime randomDispatch(OffsetDateTime historyStart, OffsetDateTime now) {
        long days = props.getHistoryDays();
        OffsetDateTime day = historyStart.plusDays(ThreadLocalRandom.current().nextLong(0, days + 1));
        int hour;
        if (ThreadLocalRandom.current().nextDouble() < 0.40) {
            // peak-leaning
            hour = ThreadLocalRandom.current().nextBoolean()
                    ? ThreadLocalRandom.current().nextInt(7, 10)
                    : ThreadLocalRandom.current().nextInt(17, 20);
        } else {
            hour = ThreadLocalRandom.current().nextInt(6, 22);
        }
        int minute = ThreadLocalRandom.current().nextInt(60);
        OffsetDateTime candidate = day.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        return candidate.isAfter(now) ? now.minusMinutes(5) : candidate;
    }
}
