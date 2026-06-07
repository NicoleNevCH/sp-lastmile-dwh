package com.transportadora.chaos.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single delivery event — exactly one row in {@code raw.delivery_events}.
 * Carries raw, unmasked data: PII (CPF, house number, recipient name) is
 * masked later by the dbt staging layer, never here.
 */
public record DeliveryEvent(
        UUID eventId,

        long driverId,
        String driverName,
        String driverCpf,
        String vehiclePlate,
        String vehicleType,

        String recipientName,
        String recipientCpf,
        String street,
        String houseNumber,
        String neighborhood,
        String cep,
        String city,

        double latitude,
        double longitude,

        OffsetDateTime dispatchedAt,
        OffsetDateTime deliveredAt,
        String status,

        boolean simForcedRodizioViolation,
        boolean simRouteCrossedFlood
) {}
