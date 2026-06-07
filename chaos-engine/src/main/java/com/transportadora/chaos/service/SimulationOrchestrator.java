package com.transportadora.chaos.service;

import com.transportadora.chaos.config.SimulationProperties;
import com.transportadora.chaos.domain.DriverProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates a full simulation run: build the roster, decide who violates the
 * rodízio, fan every truck out onto the executor, then block until the fleet
 * has finished and report throughput.
 */
@Service
public class SimulationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SimulationOrchestrator.class);

    private final FleetFactory fleetFactory;
    private final TruckSimulatorService truckSimulator;
    private final SimulationProperties props;

    public SimulationOrchestrator(FleetFactory fleetFactory,
                                  TruckSimulatorService truckSimulator,
                                  SimulationProperties props) {
        this.fleetFactory = fleetFactory;
        this.truckSimulator = truckSimulator;
        this.props = props;
    }

    public RunResult run() {
        long t0 = System.currentTimeMillis();
        List<DriverProfile> fleet = fleetFactory.createFleet(props.getTrucks(), props.getHistoryDays());

        // Only single-stint drivers are eligible to be forced violators, so the
        // plate stays constant and the forced violation is unambiguous. SCD2 is
        // demonstrated by the multi-stint drivers, which deliver normally.
        int targetViolators = (int) Math.round(props.getTrucks() * props.getRodizioViolatorRatio());
        AtomicInteger violatorsAssigned = new AtomicInteger();

        List<CompletableFuture<Integer>> futures = new ArrayList<>(fleet.size());
        for (DriverProfile driver : fleet) {
            boolean canViolate = driver.stints().size() == 1;
            boolean violate = canViolate && violatorsAssigned.get() < targetViolators;
            if (violate) violatorsAssigned.incrementAndGet();
            futures.add(truckSimulator.runShift(driver, violate));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        int totalRows = futures.stream().mapToInt(CompletableFuture::join).sum();
        long elapsedMs = System.currentTimeMillis() - t0;

        RunResult result = new RunResult(
                fleet.size(), violatorsAssigned.get(), totalRows, elapsedMs,
                elapsedMs > 0 ? (long) (totalRows / (elapsedMs / 1000.0)) : totalRows);

        log.info("Simulation complete: {} trucks, {} forced violators, {} rows in {} ms ({} rows/s)",
                result.trucks(), result.forcedViolators(), result.rowsInserted(),
                result.elapsedMs(), result.rowsPerSecond());
        return result;
    }

    public record RunResult(
            int trucks,
            int forcedViolators,
            int rowsInserted,
            long elapsedMs,
            long rowsPerSecond
    ) {}
}
