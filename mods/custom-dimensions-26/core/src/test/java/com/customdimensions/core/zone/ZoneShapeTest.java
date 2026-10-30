package com.customdimensions.core.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneShapeTest {
    private static final double EPS = 1e-9;

    @Test
    void boxMeasuresTheLargerAxisDistance() {
        ZoneShape box = new ZoneShape.Box(100, 50);
        assertEquals(-50, box.outside(0, 0), EPS);
        assertEquals(0, box.outside(100, 10), EPS);
        assertEquals(20, box.outside(120, 0), EPS);
        assertEquals(30, box.outside(110, 80), EPS);
    }

    @Test
    void sectorCoversItsAnglesBetweenItsRadii() {
        ZoneShape east = new ZoneShape.Sector(0, 1000, -30, 30);
        assertTrue(east.outside(500, 0) < 0);
        assertTrue(east.outside(500, 200) < 0);
        assertTrue(east.outside(-500, 0) > 0);
        assertEquals(100, east.outside(1100, 0), EPS);
        double perpendicular = 600 * Math.sin(Math.toRadians(15));
        double[] p = {600 * Math.cos(Math.toRadians(45)), 600 * Math.sin(Math.toRadians(45))};
        assertEquals(perpendicular, east.outside(p[0], p[1]), 1e-6);
    }

    @Test
    void sectorAnglesWrapPastEast() {
        ZoneShape wrap = new ZoneShape.Sector(200, 800, 300, 60);
        assertTrue(wrap.outside(500, 0) < 0);
        assertTrue(wrap.outside(0, 500) > 0);
        assertEquals(100, wrap.outside(100, 0), EPS);
    }

    @Test
    void sectorRejectsAFullOrEmptySweep() {
        assertThrows(IllegalArgumentException.class, () -> new ZoneShape.Sector(0, 100, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new ZoneShape.Sector(0, 100, 0, 360));
        assertThrows(IllegalArgumentException.class, () -> new ZoneShape.Sector(100, 50, 0, 90));
    }

    @Test
    void polygonIsSignedDistanceToItsEdges() {
        ZoneShape square = new ZoneShape.Polygon(List.of(new double[] {-100, -100}, new double[] {100, -100},
                new double[] {100, 100}, new double[] {-100, 100}));
        assertEquals(-100, square.outside(0, 0), EPS);
        assertEquals(-10, square.outside(90, 0), EPS);
        assertEquals(50, square.outside(150, 0), EPS);
        assertEquals(Math.hypot(30, 40), square.outside(130, 140), EPS);
        ZoneShape notch = new ZoneShape.Polygon(List.of(new double[] {0, 0}, new double[] {200, 0},
                new double[] {200, 200}, new double[] {100, 50}, new double[] {0, 200}));
        assertTrue(notch.outside(100, 150) > 0);
        assertTrue(notch.outside(20, 100) < 0);
        assertEquals(Math.hypot(200, 200), notch.extent(), EPS);
    }

    @Test
    void polygonNeedsThreePairs() {
        assertThrows(IllegalArgumentException.class, () -> new ZoneShape.Polygon(List.of(new double[] {0, 0}, new double[] {1, 1})));
        assertThrows(IllegalArgumentException.class, () -> new ZoneShape.Polygon(List.of(new double[] {0, 0},
                new double[] {1, 1}, new double[] {1})));
    }

    @Test
    void allIsInsideEverywhere() {
        assertTrue(new ZoneShape.All().outside(1e7, -1e7) < 0);
    }

    @Test
    void ringIsTheBandBetweenItsRadii() {
        ZoneShape ring = new ZoneShape.Ring(2000, 3000);
        assertEquals(-500, ring.outside(2500, 0), EPS);
        assertEquals(500, ring.outside(1500, 0), EPS);
        assertEquals(100, ring.outside(0, 3100), EPS);
    }
}
