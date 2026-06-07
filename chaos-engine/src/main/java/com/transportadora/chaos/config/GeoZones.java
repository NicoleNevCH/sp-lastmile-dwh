package com.transportadora.chaos.config;

/**
 * Real-world (approximate) São Paulo geography used by the simulator.
 *
 * <p>All coordinates are {@code [longitude, latitude]} in WGS84 (SRID 4326),
 * matching the order PostGIS expects in {@code ST_MakePoint(lng, lat)}.
 *
 * <p>These polygons are intentionally simplified but geographically plausible.
 * The authoritative spatial join (rodízio detection) is performed downstream by
 * PostGIS in dbt; the Java copies here are used only to (a) place GPS fixes
 * realistically and (b) decide which routes cross the flood zone.
 */
public final class GeoZones {

    private GeoZones() {}

    /**
     * "Centro Expandido" — the restricted zone for the SP rodízio program,
     * bounded roughly by the Tietê and Pinheiros marginals, Av. dos
     * Bandeirantes, the Maria Maluf interchange and Av. Salim Farah Maluf.
     * Simplified to a closed ring of representative vertices.
     */
    public static final double[][] CENTRO_EXPANDIDO = {
            {-46.6510, -23.5090}, // Marginal Tietê / Ponte da Casa Verde (N)
            {-46.5750, -23.5170}, // Penha (NE)
            {-46.5680, -23.5450}, // Salim Farah Maluf / Tatuapé (E)
            {-46.5750, -23.5870}, // Anhaia Mello / Sapopemba (SE)
            {-46.6300, -23.6230}, // Maria Maluf interchange (S)
            {-46.6850, -23.6230}, // Av. dos Bandeirantes / Congonhas (SW)
            {-46.7350, -23.6000}, // Marginal Pinheiros / Morumbi (W)
            {-46.7300, -23.5450}, // Marginal Pinheiros / Pinheiros (NW)
            {-46.7050, -23.5180}, // Lapa (N)
            {-46.6510, -23.5090}  // close ring
    };

    /**
     * A flooded stretch of the Marginal Tietê (near Ponte das Bandeiras). Any
     * route crossing this polygon gets its delivery timestamp heavily penalised.
     */
    public static final double[][] MARGINAL_TIETE_FLOOD = {
            {-46.6420, -23.5120},
            {-46.6350, -23.5110},
            {-46.6330, -23.5160},
            {-46.6400, -23.5170},
            {-46.6420, -23.5120}  // close ring
    };

    /**
     * Avenida Paulista, expressed as a segment from the Consolação end to the
     * Paraíso end. Forced rodízio violators are dropped onto a random point
     * along this line — squarely inside the Centro Expandido.
     */
    public static final double[] PAULISTA_START = {-46.6620, -23.5560}; // Consolação end
    public static final double[] PAULISTA_END   = {-46.6420, -23.5710}; // Paraíso end

    /** Rough bounding box of the Centro Expandido for scattering normal deliveries. */
    public static final double BBOX_MIN_LNG = -46.7350;
    public static final double BBOX_MAX_LNG = -46.5680;
    public static final double BBOX_MIN_LAT = -23.6230;
    public static final double BBOX_MAX_LAT = -23.5090;

    /** WKT for the Centro Expandido (handy for cross-checking against the dbt macro). */
    public static String centroExpandidoWkt() {
        return toPolygonWkt(CENTRO_EXPANDIDO);
    }

    /** WKT for the flood polygon. */
    public static String floodZoneWkt() {
        return toPolygonWkt(MARGINAL_TIETE_FLOOD);
    }

    private static String toPolygonWkt(double[][] ring) {
        StringBuilder sb = new StringBuilder("POLYGON((");
        for (int i = 0; i < ring.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ring[i][0]).append(' ').append(ring[i][1]);
        }
        return sb.append("))").toString();
    }
}
