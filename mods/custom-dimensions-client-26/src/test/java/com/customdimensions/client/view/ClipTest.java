package com.customdimensions.client.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClipTest {
    private static List<double[]> square(double z) {
        return List.of(new double[] {0, 0, z, 0, 0}, new double[] {4, 0, z, 1, 0}, new double[] {4, 4, z, 1, 1},
                new double[] {0, 4, z, 0, 1});
    }

    @Test
    void aPolygonWhollyInsideIsUntouched() {
        double[][] keepAll = {{0, 0, 1, 100}};
        assertEquals(4, Clip.clip(square(5), keepAll).size());
    }

    @Test
    void aPolygonWhollyOutsideVanishes() {
        double[][] keepXBelowMinus1 = {{-1, 0, 0, -1}};
        assertTrue(Clip.clip(square(5), keepXBelowMinus1).isEmpty());
    }

    @Test
    void halfASquareKeepsItsInterpolatedTextureCoordinates() {
        double[][] keepXUpTo2 = {{-1, 0, 0, 2}};
        List<double[]> out = Clip.clip(square(5), keepXUpTo2);
        assertEquals(4, out.size());
        for (double[] v : out) {
            assertTrue(v[0] <= 2 + 1e-9);
            assertEquals(v[0] / 4, v[3], 1e-9, "u follows x");
        }
    }

    @Test
    void aCornerCutAddsAVertex() {
        double[][] cut = {{-1, -1, 0, 6}};
        assertEquals(5, Clip.clip(square(5), cut).size());
    }

    @Test
    void theSquashKeepsOrderAndStaysInTheSlab() {
        double d0 = 2.5;
        double eps = 0.08;
        double previous = d0;
        for (double s = 2.6; s < 60; s += 0.7) {
            double target = s * Clip.squashFactor(s, d0, eps);
            assertTrue(target > previous, "monotonic at " + s);
            assertTrue(target >= d0 && target < d0 + eps, "in the slab at " + s);
            previous = target;
        }
        assertEquals(1.0, Clip.squashFactor(2.0, d0, eps), "nothing nearer than the surface moves");
    }

    @Test
    void fogAndColourArithmetic() {
        assertEquals(0, Clip.fog(5, 10, 20));
        assertEquals(0.5, Clip.fog(15, 10, 20), 1e-9);
        assertEquals(1, Clip.fog(25, 10, 20));
        assertEquals(0x808080, Clip.mix(0x000000, 0xFFFFFF, 0.5) & 0xFEFEFE, "half way, rounding aside");
        assertEquals(0xFF0000, Clip.mix(0xFF0000, 0x0000FF, 0));
        assertEquals(0xFFFF66, Clip.scale(0xCCCC33, 2.0));
        assertEquals(0x666666, Clip.scale(0xCCCCCC, 0.5));
    }
}
