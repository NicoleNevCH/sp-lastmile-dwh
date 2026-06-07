package com.transportadora.chaos.domain;

/**
 * The delivery modals operated by the fleet. A driver may switch modals over
 * time (e.g. promoted from a motorcycle to a commercial van), which is the
 * change captured by the SCD Type 2 driver dimension downstream.
 */
public enum VehicleType {
    MOTORCYCLE,
    VAN,
    TRUCK
}
