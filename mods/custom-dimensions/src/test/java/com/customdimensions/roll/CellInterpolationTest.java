package com.customdimensions.roll;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TerrainShape#cellInterpolated} must reproduce what a generator does
 * with its density function, not what the function says.
 *
 * <p>The regression: a map drawn from raw samples disagreed with the world by
 * a median of 2 blocks where terrain was flat and 20 where it was steep,
 * measured over 1239 columns and monotonic across four relief quartiles. The
 * cause is that generation evaluates the density at its own cell corners and
 * interpolates, and a raw sample at an interior point is simply a different
 * number.
 */
class CellInterpolationTest {

    private static final int CELL_H = 4;
    private static final int CELL_V = 8;
    private static final int MIN_Y = -64;

    private static TerrainShape.Density field(ToDoubleFunction<int[]> f) {
        return (x, y, z) -> f.applyAsDouble(new int[]{x, y, z});
    }

    private static TerrainShape.Density wrap(TerrainShape.Density raw) {
        return TerrainShape.cellInterpolated(raw, CELL_H, CELL_V, MIN_Y);
    }

    @Test
    void atACellCornerTheInterpolationIsTheRawSample() {
        // The one place the two must agree exactly. If they do not, the
        // lattice is in the wrong place.
        TerrainShape.Density raw = field(p -> p[0] * 0.5 + p[1] * 0.25 + p[2] * 0.125);
        TerrainShape.Density interpolated = wrap(raw);
        for (int x : new int[]{-8, -4, 0, 4, 128}) {
            for (int z : new int[]{-8, 0, 4, 64}) {
                for (int y : new int[]{MIN_Y, MIN_Y + CELL_V, MIN_Y + 4 * CELL_V}) {
                    assertEquals(raw.at(x, y, z), interpolated.at(x, y, z), 1e-9,
                            "corner (" + x + ", " + y + ", " + z + ")");
                }
            }
        }
    }

    @Test
    void aLinearFieldIsReproducedEverywhere() {
        // Interpolation is exact on anything linear, so a linear field is the
        // control: any deviation here is an arithmetic error, not the effect
        // the wrapper exists to model.
        TerrainShape.Density raw = field(p -> 3.0 * p[0] - 2.0 * p[1] + 0.5 * p[2] + 7.0);
        TerrainShape.Density interpolated = wrap(raw);
        for (int x = -13; x <= 13; x++) {
            for (int z = -7; z <= 7; z++) {
                for (int y = -70; y <= 40; y += 3) {
                    assertEquals(raw.at(x, y, z), interpolated.at(x, y, z), 1e-9,
                            "(" + x + ", " + y + ", " + z + ")");
                }
            }
        }
    }

    @Test
    void aSpikeBetweenCornersIsInvisible_whichIsTheWholePoint() {
        // A feature that exists only strictly inside a cell cannot reach the
        // world: the generator never evaluates the function there. A raw walk
        // sees it and puts terrain where the world has none.
        TerrainShape.Density raw = field(p ->
                Math.floorMod(p[0], CELL_H) == 2 && Math.floorMod(p[1] - MIN_Y, CELL_V) == 4
                        ? 100.0 : -1.0);
        TerrainShape.Density interpolated = wrap(raw);
        assertTrue(raw.at(2, MIN_Y + 4, 0) > 0, "the raw field spikes inside the cell");
        assertTrue(interpolated.at(2, MIN_Y + 4, 0) < 0,
                "the generator never samples there, so neither may the map");
    }

    @Test
    void aColumnWalkPaysFourRawSamplesPerCellLevel() {
        // The cost claim, asserted rather than asserted-in-a-comment. Walking
        // one column across three cells reads four corners per LEVEL and
        // nothing per block — otherwise interpolation is eight samples a
        // block and a detail render stops being affordable.
        AtomicInteger reads = new AtomicInteger();
        TerrainShape.Density counted = (x, y, z) -> {
            reads.incrementAndGet();
            return -1.0;
        };
        TerrainShape.Density interpolated = wrap(counted);
        // Every block of three whole cells, top down, the way a walk reads it.
        for (int y = MIN_Y + 3 * CELL_V; y >= MIN_Y; y--) {
            interpolated.at(1, y, 1);
        }
        // Five levels are touched — the four cell floors from MIN_Y up, plus
        // the ceiling above the topmost block — at four corners each. Twenty-
        // five blocks were read; the count is per LEVEL, which is the claim.
        assertEquals(5 * 4, reads.get(),
                "four corners per cell level, and nothing per block");
    }

    @Test
    void anAscendingWalkCostsFourSamplesPerCellToo() {
        // The refine pass inside `highestSolid` walks UP. The first cache was
        // tuned for descent — it resolved the two levels one at a time, so on
        // an upward rung crossing the first miss evicted what the second
        // needed and the crossing cost eight samples instead of four. Values
        // stayed right, which is why only a cost assertion can see it.
        AtomicInteger reads = new AtomicInteger();
        TerrainShape.Density counted = (x, y, z) -> {
            reads.incrementAndGet();
            return -1.0;
        };
        TerrainShape.Density interpolated = wrap(counted);
        for (int y = MIN_Y; y <= MIN_Y + 3 * CELL_V; y++) {
            interpolated.at(1, y, 1);
        }
        assertEquals(5 * 4, reads.get(),
                "an upward walk must reuse the shared level, exactly as a downward one does");
    }

    @Test
    void reversingDirectionMidCellCostsNothingExtra() {
        AtomicInteger reads = new AtomicInteger();
        TerrainShape.Density counted = (x, y, z) -> {
            reads.incrementAndGet();
            return -1.0;
        };
        TerrainShape.Density interpolated = wrap(counted);
        interpolated.at(1, MIN_Y + 12, 1);          // one cell: two levels
        int afterFirst = reads.get();
        assertEquals(2 * 4, afterFirst);
        for (int i = 0; i < 20; i++) {              // wander inside the same cell
            interpolated.at(1, MIN_Y + 8 + (i % CELL_V), 1);
        }
        assertEquals(afterFirst, reads.get(), "nothing inside a cell costs a sample");
    }

    @Test
    void movingToANewColumnDropsTheCache() {
        AtomicInteger reads = new AtomicInteger();
        TerrainShape.Density counted = (x, y, z) -> {
            reads.incrementAndGet();
            return -1.0;
        };
        TerrainShape.Density interpolated = wrap(counted);
        interpolated.at(1, 0, 1);
        int afterFirst = reads.get();
        interpolated.at(1, 0, 1);
        assertEquals(afterFirst, reads.get(), "the same column and level is cached");
        interpolated.at(9, 0, 9);
        assertTrue(reads.get() > afterFirst,
                "a different column must not answer from another column's cache");
    }

    @Test
    void negativeCoordinatesUseTheSameLattice() {
        // Math.floorDiv, not integer division: -1 / 4 is 0 in Java and the
        // cell containing -1 starts at -4. Getting this wrong shifts every
        // cell in the western and northern half of every world.
        TerrainShape.Density raw = field(p -> p[0] == -4 ? 1.0 : -1.0);
        TerrainShape.Density interpolated = wrap(raw);
        // x=-1 sits three quarters of the way from -4 to 0, so a corner value
        // of 1 at -4 and -1 at 0 interpolates to -0.5.
        assertEquals(-0.5, interpolated.at(-1, 0, 0), 1e-9);
        assertEquals(1.0, interpolated.at(-4, 0, 0), 1e-9);
    }

    @Test
    void aCellSizeOfOneIsTheIdentity() {
        TerrainShape.Density raw = field(p -> p[0] * 1.5 + p[1] - p[2] * 0.25);
        TerrainShape.Density identity = TerrainShape.cellInterpolated(raw, 1, 1, 0);
        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                assertEquals(raw.at(x, y, 3), identity.at(x, y, 3), 1e-9);
            }
        }
    }
}
