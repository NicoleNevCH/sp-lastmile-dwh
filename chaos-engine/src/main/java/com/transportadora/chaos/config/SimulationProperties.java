package com.transportadora.chaos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed binding of the {@code simulation.*} keys in application.yml.
 */
@ConfigurationProperties(prefix = "simulation")
public class SimulationProperties {

    private boolean autoStart = true;
    private int trucks = 250;
    private int deliveriesPerTruck = 60;
    private int batchSize = 500;
    private double rodizioViolatorRatio = 0.18;
    private double floodAffectedRatio = 0.12;
    private int historyDays = 7;

    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }

    public int getTrucks() { return trucks; }
    public void setTrucks(int trucks) { this.trucks = trucks; }

    public int getDeliveriesPerTruck() { return deliveriesPerTruck; }
    public void setDeliveriesPerTruck(int deliveriesPerTruck) { this.deliveriesPerTruck = deliveriesPerTruck; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public double getRodizioViolatorRatio() { return rodizioViolatorRatio; }
    public void setRodizioViolatorRatio(double rodizioViolatorRatio) { this.rodizioViolatorRatio = rodizioViolatorRatio; }

    public double getFloodAffectedRatio() { return floodAffectedRatio; }
    public void setFloodAffectedRatio(double floodAffectedRatio) { this.floodAffectedRatio = floodAffectedRatio; }

    public int getHistoryDays() { return historyDays; }
    public void setHistoryDays(int historyDays) { this.historyDays = historyDays; }
}
