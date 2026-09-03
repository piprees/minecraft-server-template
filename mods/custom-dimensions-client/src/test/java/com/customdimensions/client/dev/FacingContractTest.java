package com.customdimensions.client.dev;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PlayerFacts#facing(double)} against the game's own answer, so the
 * table cannot drift from vanilla. Separate from {@link PlayerFactsTest},
 * which stays free of Minecraft types.
 */
class FacingContractTest {

    /** Cardinals, both sides of every quadrant boundary, negatives, and wraps. */
    private static final double[] YAWS = {
        0, 44.9, 45, 45.0001, 90, 134.9, 135, 180, 224.9, 225, 269.9, 270, 314.9, 315,
        359.9, 360, 720, 1170, -0.0001, -1, -90, -134.9, -135, -180, -270, -3600
    };

    @Test
    void everyYawAgreesWithVanilla() {
        for (double yaw : YAWS) {
            assertEquals(Direction.fromRotation(yaw).asString(), PlayerFacts.facing(yaw),
                    "yaw " + yaw);
        }
    }

    /** A full turn in one-degree steps, so no quadrant is pinned by luck. */
    @Test
    void everyDegreeOfATurnAgreesWithVanilla() {
        for (int degree = -360; degree <= 720; degree++) {
            assertEquals(Direction.fromRotation(degree).asString(), PlayerFacts.facing(degree),
                    "yaw " + degree);
        }
    }
}
