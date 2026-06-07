package com.transportadora.chaos.config;

import com.transportadora.chaos.service.SimulationOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs one simulation automatically on startup when {@code simulation.auto-start}
 * is true. Otherwise the run is driven on demand via the REST endpoint.
 */
@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final SimulationProperties props;
    private final SimulationOrchestrator orchestrator;

    public StartupRunner(SimulationProperties props, SimulationOrchestrator orchestrator) {
        this.props = props;
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isAutoStart()) {
            log.info("simulation.auto-start=false — waiting for POST /api/simulations/run");
            return;
        }
        log.info("Auto-starting simulation: {} trucks x {} deliveries",
                props.getTrucks(), props.getDeliveriesPerTruck());
        orchestrator.run();
    }
}
