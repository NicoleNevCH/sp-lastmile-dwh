package com.transportadora.chaos.service;

import com.transportadora.chaos.domain.DriverProfile;
import com.transportadora.chaos.domain.VehicleStint;
import com.transportadora.chaos.domain.VehicleType;
import net.datafaker.Faker;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the simulated driver roster using realistic Brazilian data
 * (pt-BR names and valid CPFs) and Mercosul-style plates that always end in a
 * digit (so the rodízio "last digit" rule is unambiguous to extract in SQL).
 *
 * <p>About 1 in 5 drivers gets a mid-history "promotion" (e.g. MOTORCYCLE→VAN),
 * which seeds the SCD Type 2 behaviour in the dimensional model.
 */
@Component
public class FleetFactory {

    private final Faker faker = new Faker(new Locale("pt", "BR"));

    public List<DriverProfile> createFleet(int size, int historyDays) {
        List<DriverProfile> fleet = new ArrayList<>(size);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime historyStart = now.minusDays(historyDays);

        for (int i = 0; i < size; i++) {
            long id = 1000L + i;
            String name = faker.name().fullName();
            String cpf = faker.cpf().valid(); // formatted, e.g. 123.456.789-09

            List<VehicleStint> stints = new ArrayList<>(2);
            VehicleType firstType = randomModal();
            stints.add(new VehicleStint(firstType, randomPlate(), historyStart));

            // ~20% of drivers switch modal partway through the history window.
            if (ThreadLocalRandom.current().nextDouble() < 0.20) {
                VehicleType secondType = upgradeFrom(firstType);
                // Switch happens somewhere in the middle 60% of the window.
                long span = historyDays;
                long offsetDays = 1 + ThreadLocalRandom.current().nextLong(Math.max(1, span - 1));
                OffsetDateTime switchAt = historyStart.plusDays(offsetDays);
                stints.add(new VehicleStint(secondType, randomPlate(), switchAt));
            }

            fleet.add(new DriverProfile(id, name, cpf, stints));
        }
        return fleet;
    }

    private VehicleType randomModal() {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.55) return VehicleType.MOTORCYCLE;
        if (r < 0.90) return VehicleType.VAN;
        return VehicleType.TRUCK;
    }

    /** A plausible "promotion" path: bikes become vans, vans become trucks. */
    private VehicleType upgradeFrom(VehicleType current) {
        return switch (current) {
            case MOTORCYCLE -> VehicleType.VAN;
            case VAN -> VehicleType.TRUCK;
            case TRUCK -> VehicleType.VAN; // fleet reshuffle
        };
    }

    /**
     * Mercosul plate pattern LLLNLNN (3 letters, digit, letter, 2 digits).
     * Ends in a digit so {@code right(plate, 1)} yields the rodízio digit.
     */
    private String randomPlate() {
        return faker.regexify("[A-Z]{3}[0-9][A-Z][0-9]{2}");
    }
}
