package com.transportadora.chaos.web;

import com.transportadora.chaos.service.SimulationOrchestrator;
import com.transportadora.chaos.service.SimulationOrchestrator.RunResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets you trigger a simulation run on demand:
 * <pre>curl -X POST http://localhost:8080/api/simulations/run</pre>
 * Returns throughput stats for the run.
 */
@RestController
@RequestMapping("/api/simulations")
public class SimulationController {

    private final SimulationOrchestrator orchestrator;

    public SimulationController(SimulationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/run")
    public RunResult run() {
        return orchestrator.run();
    }
}
