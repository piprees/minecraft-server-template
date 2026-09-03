package com.customdimensions.dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Named curves from the jar table, for tests asserting which curve a group resolved to. */
final class TypeDefaultCurves {

    private TypeDefaultCurves() {
    }

    /**
     * A named curve, asserted present. A missing name resolves to null on BOTH
     * sides of a curve assertion, which reads as a match while every group it
     * names has silently gone uniform.
     */
    static double[] namedCurve(String name) {
        double[] curve = StructureGroupRegistry.curve(name);
        assertNotNull(curve, "structure_type_defaults.json declares no curve named `" + name + "`");
        assertEquals(10, curve.length, "curve `" + name + "` is not 10 points");
        return curve;
    }
}
