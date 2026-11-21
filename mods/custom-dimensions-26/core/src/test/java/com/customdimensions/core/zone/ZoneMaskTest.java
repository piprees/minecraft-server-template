package com.customdimensions.core.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ZoneMaskTest {
    private static final double EPS = 1e-9;
    private final ZoneMask ring = new ZoneMask(0, 0, new ZoneShape.Ring(600, 1200), 120);

    @Test
    void ringIsFullInsideAndEmptyInTheHoleAndBeyond() {
        assertEquals(1, ring.at(900, 0), EPS);
        assertEquals(1, ring.at(0, -600), EPS);
        assertEquals(1, ring.at(1200, 0), EPS);
        assertEquals(0, ring.at(0, 0), EPS);
        assertEquals(0, ring.at(480, 0), EPS);
        assertEquals(0, ring.at(1320, 0), EPS);
        assertEquals(0, ring.at(5000, 5000), EPS);
    }

    @Test
    void edgesRampLinearlyAcrossTheBlend() {
        assertEquals(0.5, ring.at(540, 0), EPS);
        assertEquals(0.5, ring.at(1260, 0), EPS);
        assertEquals(0.25, ring.at(0, 1290), EPS);
    }

    @Test
    void distanceIsHorizontalAndMeasuredFromTheCentre() {
        ZoneMask moved = new ZoneMask(1000, -2000, new ZoneShape.Ring(600, 1200), 120);
        assertEquals(0, moved.at(1000, -2000), EPS);
        assertEquals(1, moved.at(1000 + 900 * Math.cos(1), -2000 + 900 * Math.sin(1)), EPS);
    }

    @Test
    void circleIsFullToItsRadius() {
        ZoneMask circle = new ZoneMask(0, 0, new ZoneShape.Circle(500), 100);
        assertEquals(1, circle.at(0, 0), EPS);
        assertEquals(1, circle.at(500, 0), EPS);
        assertEquals(0.5, circle.at(550, 0), EPS);
        assertEquals(0, circle.at(600, 0), EPS);
    }

    @Test
    void ringWithNoHoleBehavesAsACircle() {
        ZoneMask solid = new ZoneMask(0, 0, new ZoneShape.Ring(0, 500), 100);
        assertEquals(1, solid.at(0, 0), EPS);
        assertEquals(0.5, solid.at(550, 0), EPS);
    }

    @Test
    void rejectsShapesThatCannotBeBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new ZoneShape.Circle(0));
        assertThrows(IllegalArgumentException.class, () -> new ZoneShape.Ring(1200, 600));
        assertThrows(IllegalArgumentException.class, () -> new ZoneShape.Ring(-1, 600));
        assertThrows(IllegalArgumentException.class, () -> new ZoneMask(0, 0, new ZoneShape.Circle(10), -1));
        assertThrows(IllegalArgumentException.class, () -> new ZoneMask(0, 0, new ZoneShape.Circle(10), 8, -1));
        assertThrows(IllegalArgumentException.class, () -> new ZoneMask(0, 0, new ZoneShape.Ring(100, 600), 150));
        assertThrows(IllegalArgumentException.class, () -> new ZoneShape.Ring(Double.NaN, 600));
    }
}
