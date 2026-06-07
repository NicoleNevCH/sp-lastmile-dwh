package com.transportadora.chaos.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Minimal computational-geometry helpers used by the chaos engine to place GPS
 * fixes and decide flood-zone crossings — no external GIS dependency needed on
 * the Java side. The authoritative spatial logic still runs in PostGIS via dbt.
 */
@Component
public class GeoUtil {

    /**
     * Ray-casting point-in-polygon test. {@code ring} is a closed list of
     * {@code [lng, lat]} vertices.
     */
    public boolean contains(double[][] ring, double lng, double lat) {
        boolean inside = false;
        int n = ring.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring[i][0], yi = ring[i][1];
            double xj = ring[j][0], yj = ring[j][1];
            boolean intersects = ((yi > lat) != (yj > lat))
                    && (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi);
            if (intersects) inside = !inside;
        }
        return inside;
    }

    /** A random point linearly interpolated along the segment a→b. */
    public double[] pointOnSegment(double[] a, double[] b) {
        double t = ThreadLocalRandom.current().nextDouble();
        return new double[] {
                a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t
        };
    }

    /** A uniformly random point inside the given bounding box. */
    public double[] randomPointInBox(double minLng, double maxLng, double minLat, double maxLat) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new double[] {
                r.nextDouble(minLng, maxLng),
                r.nextDouble(minLat, maxLat)
        };
    }

    /** A point nudged towards the flood polygon's centroid, so the route "crosses" it. */
    public double[] pointInside(double[][] ring) {
        // Average the ring vertices for a centroid that is guaranteed interior
        // for the small convex flood polygon we use.
        double sx = 0, sy = 0;
        int n = ring.length - 1; // last vertex repeats the first
        for (int i = 0; i < n; i++) {
            sx += ring[i][0];
            sy += ring[i][1];
        }
        double cx = sx / n, cy = sy / n;
        // tiny jitter so points aren't all identical
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new double[] {
                cx + r.nextDouble(-0.0008, 0.0008),
                cy + r.nextDouble(-0.0008, 0.0008)
        };
    }
}
