package com.transportadora.chaos.domain;

/**
 * A driver in the fleet. {@code id} is the natural/business key that remains
 * stable across modal changes; the dimensional model versions the rest.
 */
public record Driver(
        long id,
        String name,
        String cpf,
        VehicleType vehicleType,
        String plate
) {}
