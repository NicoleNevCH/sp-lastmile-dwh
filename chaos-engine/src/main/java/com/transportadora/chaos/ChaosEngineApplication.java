package com.transportadora.chaos;

import com.transportadora.chaos.config.SimulationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.TimeZone;

/**
 * Chaos Engine — the carrier's "core" system.
 *
 * <p>It spins up a pool of threads, each simulating a delivery truck on the
 * streets of São Paulo. Trucks emit thousands of delivery events which are
 * bulk-inserted into the PostgreSQL raw zone via {@code JdbcTemplate} batch
 * inserts. On top of plain volume, the engine deliberately injects business
 * complexity (rodízio violations and a flooded Marginal Tietê) to stress-test
 * the downstream dbt analytics.
 */
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(SimulationProperties.class)
public class ChaosEngineApplication {

    public static void main(String[] args) {
        // This is a São Paulo simulation: rodízio restrictions and peak windows
        // are defined in SP local time. Pin the JVM clock to America/Sao_Paulo
        // so every timestamp the engine generates is SP-local regardless of the
        // host timezone — which is exactly how dbt re-evaluates them downstream
        // (delivered_at AT TIME ZONE 'America/Sao_Paulo'). Without this, a host
        // running in UTC would shift "evening peak" hours out of the window.
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
        SpringApplication.run(ChaosEngineApplication.class, args);
    }
}
