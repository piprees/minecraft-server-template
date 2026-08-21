package com.customdimensions.roll;

import com.customdimensions.command.ColumnScan;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One question, one answer: {@link TerrainShape} — what the map is drawn
 * from — must return the same y as the sampler the FACTS read, over the same
 * column.
 *
 * <p>This exists because they drifted. The facts read
 * {@code SpikeSampler.sample}: {@code OCEAN_FLOOR_WG} for an open dimension
 * (vanilla's heightmap, which answers the block ABOVE the floor) and
 * {@link ColumnScan} for a ceilinged one (roof, underside, floor with
 * headroom). {@code TerrainShape} answered the highest solid block, and did
 * not know what a ceiling was. Measured against a live world on 2026-08-13:
 * the nether disagreed on 1303 of 1313 columns and the overworld on 114 of
 * 200 recorded ones, in opposite directions, and each looked like a
 * plausible answer on its own.
 *
 * <p>The two sides read different things — one a density field, the other
 * block states — so they are driven here from ONE synthetic column, with
 * "solid" meaning density above zero on one side and opaque on the other.
 * Anything they can still disagree about is a difference in the rule, which
 * is exactly what this pins.
 *
 * <p><b>Columns are built with no feature thinner than the rung.</b> That is
 * not a convenience: the density a generator interpolates is linear between
 * two cell corners, so a solid layer thinner than one cell cannot exist in a
 * real world — and a rung walk that stepped over one would be finding a
 * defect in the fixture rather than in the code.
 */
class HeightSourceParityTest {

    /** Cell height for every column here; also the minimum feature thickness. */
    private static final int RUNG = 8;

    /**
     * A column as a list of solid intervals, readable as either a density or
     * an opacity predicate.
     */
    private record Column(boolean[] solid, int bottom) {

        boolean isSolid(int y) {
            int i = y - this.bottom;
            return i >= 0 && i < this.solid.length && this.solid[i];
        }

        TerrainShape.Density density() {
            return (x, y, z) -> isSolid(y) ? 1.0 : -1.0;
        }

        IntPredicate opaque() {
            return this::isSolid;
        }
    }

    /**
     * A random column whose solid runs and gaps are each a whole number of
     * rungs — the coarsest terrain a generator on this cell height can make.
     *
     * <p>One in five columns instead carries ground ONLY in the bottom
     * sliver, thinner than a rung. That is not a thin-feature case the walk is
     * allowed to miss — the density crossing simply falls inside the band's
     * first cell, which any generator with a floor does — and the earlier
     * fixture could not produce it: every run started exactly at {@code
     * bottom} with a minimum length of a whole rung, so anything touching the
     * floor also reached the first coarse-tested point above it. Four hundred
     * random columns never exercised the case for that reason, and a real
     * lattice-alignment bug lived underneath them.
     */
    private static Column random(Random rng, int bottom, int top, boolean roof) {
        boolean[] solid = new boolean[top - bottom + 1];
        if (rng.nextInt(5) == 0) {
            // Ground ONLY in the band's bottom sliver, thinner than a rung —
            // a density crossing inside the first cell, which any generator
            // with a floor produces. It has to be the ONLY ground in the
            // column: leave anything solid above it and the coarse walk finds
            // that instead and the case is never reached. A first attempt did
            // exactly that and passed against the bug it was written for.
            int thickness = 1 + rng.nextInt(RUNG - 1);
            for (int i = 0; i < thickness; i++) {
                solid[i] = true;
            }
            return roof ? withRoof(solid, bottom, top) : new Column(solid, bottom);
        }
        int y = bottom;
        boolean filling = rng.nextBoolean();
        while (y <= top) {
            int run = RUNG * (1 + rng.nextInt(4));
            for (int i = 0; i < run && y + i <= top; i++) {
                solid[y + i - bottom] = filling;
            }
            y += run;
            filling = !filling;
        }
        if (roof) {
            // A ceilinged world always has a lid, and the density above it
            // stays solid because the roof gradient is clamped.
            return withRoof(solid, bottom, top);
        }
        return new Column(solid, bottom);
    }

    /**
     * Lays the lid on. It starts on a RUNG boundary: laying it at an arbitrary
     * y shortens the gap beneath it to fewer blocks than a cell, which no
     * generator can produce and which a rung walk is entitled to step over —
     * a fixture that did that failed this test for its own reasons.
     */
    private static Column withRoof(boolean[] solid, int bottom, int top) {
        int span = top - bottom + 1;
        for (int i = span - 1; i >= Math.max(0, (span / RUNG) * RUNG - 3 * RUNG); i--) {
            solid[i] = true;
        }
        return new Column(solid, bottom);
    }

    /**
     * What {@code SpikeSampler.sample} answers for an OPEN dimension:
     * {@code getHeight(OCEAN_FLOOR_WG)}, which vanilla defines as one above
     * the highest block matching its predicate.
     */
    private static Integer factsOpen(Column column, int bottom, int top) {
        for (int y = top; y >= bottom; y--) {
            if (column.isSolid(y)) {
                return y + 1;
            }
        }
        return null;
    }

    /** What {@code SpikeSampler.sample} answers for a CEILINGED dimension. */
    private static Integer factsCeilinged(Column column, int bottom, int top) {
        ColumnScan.Result result = ColumnScan.scan(top, bottom, column.opaque());
        return result.isPresent() ? result.floorY() : null;
    }

    @Test
    void openDimensionsAgreeOnEveryRandomColumn() {
        Random rng = new Random(20260813L);
        TerrainShape.Band band = new TerrainShape.Band(-64, 319, RUNG);   // height 384: span is rung-1 mod rung, as every real band is
        for (int i = 0; i < 400; i++) {
            Column column = random(rng, band.bottomY(), band.topY(), false);
            assertEquals(factsOpen(column, band.bottomY(), band.topY()),
                    TerrainShape.surfaceY(column.density(), band, 0, 0, false),
                    "column " + i + ": the map and the facts must answer one height");
        }
    }

    @Test
    void ceilingedDimensionsAgreeOnEveryRandomColumn() {
        Random rng = new Random(20260814L);
        TerrainShape.Band band = new TerrainShape.Band(0, 191, RUNG);
        for (int i = 0; i < 400; i++) {
            Column column = random(rng, band.bottomY(), band.topY(), true);
            assertEquals(factsCeilinged(column, band.bottomY(), band.topY()),
                    TerrainShape.surfaceY(column.density(), band, 0, 0, true),
                    "column " + i + ": the map and the facts must answer one height");
        }
    }

    @Test
    void theNetherCaseTheLiveWorldShowed() {
        // Roof from 120 to the top of the band and beyond (clamped gradient),
        // open interior, ground at 40. The old walk answered 191 here — the
        // band top — for every column in the world.
        TerrainShape.Band band = new TerrainShape.Band(0, 191, RUNG);
        boolean[] solid = new boolean[192];
        for (int y = 0; y <= 191; y++) {
            solid[y] = y <= 40 || y >= 120;
        }
        Column column = new Column(solid, 0);
        assertEquals(41, factsCeilinged(column, 0, 191));
        assertEquals(41, TerrainShape.surfaceY(column.density(), band, 0, 0, true));
    }

    /**
     * The production path, end to end: the density the render actually walks
     * is {@link TerrainShape#cellInterpolated}, and the blocks a world holds
     * come from that same interpolated value. Both sides are driven from one
     * smooth field so nothing is assumed about either.
     *
     * <p>The earlier cases drive the walk with a RAW density, which pins the
     * walk's rule — the convention and the ceiling. This pins the rule
     * against the wrapper the renderer really uses, over terrain steep enough
     * that a raw sample and an interpolated one are different numbers, which
     * is the case that was 20 blocks out against a live world.
     */
    @Test
    void theInterpolatedPathAgreesOverSteepTerrain() {
        int cellH = 4;
        int cellV = 8;
        TerrainShape.Band band = new TerrainShape.Band(-64, 320, cellV);
        // A ridged surface that climbs fast: ~6 blocks of height per block of
        // x, so a cell corner and its interior differ substantially.
        TerrainShape.Density raw = (x, y, z) -> {
            double surface = 80 + 6.0 * Math.sin(x * 0.11) * Math.cos(z * 0.07) * 7.0;
            return surface - y;
        };
        TerrainShape.Density forWalk =
                TerrainShape.cellInterpolated(raw, cellH, cellV, band.bottomY());
        TerrainShape.Density forWorld =
                TerrainShape.cellInterpolated(raw, cellH, cellV, band.bottomY());

        int compared = 0;
        for (int x = -37; x <= 37; x += 3) {
            for (int z = -29; z <= 29; z += 7) {
                // The world: the highest block that blocks movement, plus one.
                Integer world = null;
                for (int y = band.topY(); y >= band.bottomY(); y--) {
                    if (forWorld.at(x, y, z) > 0) {
                        world = y + 1;
                        break;
                    }
                }
                assertEquals(world, TerrainShape.surfaceY(forWalk, band, x, z, false),
                        "(" + x + ", " + z + ")");
                compared++;
            }
        }
        assertEquals(25 * 9, compared, "the sweep must actually have run");
    }

    @Test
    void aVoidColumnIsAbsentOnBothSides() {
        TerrainShape.Band band = new TerrainShape.Band(0, 191, RUNG);
        Column empty = new Column(new boolean[192], 0);
        assertEquals(null, factsOpen(empty, 0, 191));
        assertEquals(null, TerrainShape.surfaceY(empty.density(), band, 0, 0, false));
        assertEquals(null, factsCeilinged(empty, 0, 191));
        assertEquals(null, TerrainShape.surfaceY(empty.density(), band, 0, 0, true));
    }
}
