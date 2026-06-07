package com.transportadora.chaos.service;

import com.transportadora.chaos.model.DeliveryEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Bulk-loads delivery events into {@code raw.delivery_events} using
 * {@code JdbcTemplate} batch inserts. The driver-level rewriteBatchedInserts is
 * enabled via the JDBC URL parameter so the batches collapse into multi-row
 * INSERTs on the wire — the fast path for landing thousands of rows.
 */
@Service
public class BatchIngestionService {

    private static final String INSERT_SQL = """
            INSERT INTO raw.delivery_events (
                event_id, driver_id, driver_name, driver_cpf, vehicle_plate, vehicle_type,
                recipient_name, recipient_cpf, street, house_number, neighborhood, cep, city,
                latitude, longitude, dispatched_at, delivered_at, status,
                sim_forced_rodizio_violation, sim_route_crossed_flood
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public BatchIngestionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the given events in chunks of {@code batchSize}.
     *
     * @return total rows submitted
     */
    public int ingest(List<DeliveryEvent> events, int batchSize) {
        jdbc.batchUpdate(INSERT_SQL, events, batchSize, (ps, e) -> {
            ps.setObject(1, e.eventId());
            ps.setLong(2, e.driverId());
            ps.setString(3, e.driverName());
            ps.setString(4, e.driverCpf());
            ps.setString(5, e.vehiclePlate());
            ps.setString(6, e.vehicleType());
            ps.setString(7, e.recipientName());
            ps.setString(8, e.recipientCpf());
            ps.setString(9, e.street());
            ps.setString(10, e.houseNumber());
            ps.setString(11, e.neighborhood());
            ps.setString(12, e.cep());
            ps.setString(13, e.city());
            ps.setDouble(14, e.latitude());
            ps.setDouble(15, e.longitude());
            ps.setObject(16, e.dispatchedAt());
            ps.setObject(17, e.deliveredAt());
            ps.setString(18, e.status());
            ps.setBoolean(19, e.simForcedRodizioViolation());
            ps.setBoolean(20, e.simRouteCrossedFlood());
        });
        return events.size();
    }
}
