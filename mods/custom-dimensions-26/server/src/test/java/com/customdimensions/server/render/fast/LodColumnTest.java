package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class LodColumnTest {
    static final int MIN_Y = -64;
    static final int LO = -64;
    static final int HI = 255;
    static final int TOP = 320;
    static final LodColumn.Palette<String> PALETTE = new LodColumn.Palette<>("air", "stone", b -> b.equals("lava") ? 15 : 0);

    /** A rule that tops every run with "top" and gives every dressed underside "under". */
    static final RunDress.Materials<String> RULE = (x, y, z, above, below, water) -> above == 1 ? "top" : below == 1 ? "under" : null;
    static final RunDress.Fluids<String> DRY = (x, y, z) -> null;
    static final RunDress.Fluids<String> SEA = (x, y, z) -> y < 63 ? new RunDress.Fluid<>("water", 63) : null;

    static ColumnRuns runs(int... bounds) {
        boolean[] solid = new boolean[HI - LO + 1];
        for (int i = 0; i < bounds.length; i += 2) {
            for (int y = bounds[i]; y <= bounds[i + 1]; y++) solid[y - LO] = true;
        }
        return ColumnRuns.of(solid, LO, solid.length);
    }

    static List<LodColumn.Segment<String, String>> segments(ColumnRuns r, RunDress.Fluids<String> fluids, boolean undersides,
                                                            boolean skylit) {
        RunDress<String> d = RunDress.of(r, 0, 0, LO, HI, RULE, fluids, "stone", undersides);
        List<String> biomes = new ArrayList<>();
        for (int i = 0; i < r.count(); i++) biomes.add("b" + i);
        if (r.count() == 0) biomes.add("void");
        List<LodColumn.Segment<String, String>> out = new ArrayList<>();
        LodColumn.segments(r, d, biomes, MIN_Y, TOP, r.roofed(HI), skylit, PALETTE, out);
        assertNull(LodBench.check(out, MIN_Y), "a column DH takes");
        return out;
    }

    static LodColumn.Segment<String, String> seg(int bottom, int top, String block, String biome, int sky, int light) {
        return new LodColumn.Segment<>(bottom, top, block, biome, sky, light);
    }

    @Test
    void aSingleSurfaceIsSkyTheTopBlockAndTheBody() {
        var s = segments(runs(LO, 70), DRY, false, true);
        assertEquals(List.of(
                seg(71, TOP, "air", "b0", 15, 0),
                seg(70, 71, "top", "b0", 15, 0),
                seg(LO, 70, "stone", "b0", 0, 0)), s);
    }

    @Test
    void fluidOverGroundIsItsOwnSegmentAndDimsTheFloorByItsDepth() {
        var s = segments(runs(LO, 40), SEA, false, true);
        assertEquals(List.of(
                seg(63, TOP, "air", "b0", 15, 0),
                seg(41, 63, "water", "b0", 15, 0),
                seg(40, 41, "top", "b0", 0, 0),
                seg(LO, 40, "stone", "b0", 0, 0)), s);
        var shallow = segments(runs(LO, 59), SEA, false, true);
        assertEquals(12, shallow.get(2).skyLight(), "three blocks of water over the floor");
    }

    @Test
    void lavaLightsTheBlockUnderIt() {
        RunDress.Fluids<String> lava = (x, y, z) -> y < 32 ? new RunDress.Fluid<>("lava", 32) : null;
        var s = segments(runs(LO, 20), lava, false, false);
        assertEquals(seg(20, 21, "top", "b0", 0, 15), s.get(2));
    }

    @Test
    void aRoofIsDressedUnderneathAndKeepsTheSkyFromTheCavernAndTheFloor() {
        var s = segments(runs(100, HI, LO, 40), DRY, true, true);
        assertEquals(List.of(
                seg(HI + 1, TOP, "air", "b0", 0, 0),
                seg(HI, HI + 1, "top", "b0", 0, 0),
                seg(101, HI, "stone", "b0", 0, 0),
                seg(100, 101, "under", "b0", 0, 0),
                seg(41, 100, "air", "b1", 0, 0),
                seg(40, 41, "top", "b1", 0, 0),
                seg(LO, 40, "stone", "b1", 0, 0)), s);
    }

    @Test
    void threeTiersOverAVoidAreEachLitWhereTheBandsUnderThemAreTall() {
        var s = segments(runs(200, 210, 150, 160, 60, 64, 20, 22), DRY, true, true);
        List<String> blocks = s.stream().map(LodColumn.Segment::block).toList();
        assertEquals(List.of("air", "top", "stone", "under", "air", "top", "stone", "under", "air", "top", "stone", "under", "air", "top",
                "stone", "under", "air"), blocks);
        assertEquals(seg(161, 200, "air", "b1", 15, 0), s.get(4), "a 39-block band under a tier is lit from its sides");
        assertEquals(seg(160, 161, "top", "b1", 15, 0), s.get(5));
        assertEquals(seg(23, 60, "air", "b3", 15, 0), s.get(12));
        assertEquals(seg(MIN_Y, 20, "air", "b3", 15, 0), s.getLast(), "the void under the lowest island");
        assertEquals(seg(21, 22, "stone", "b3", 0, 0), s.get(14), "a three-block island: top, one body block, underside");
    }

    @Test
    void aShortGapUnderARunIsACaveWithoutSky() {
        var s = segments(runs(LO, 30, 40, 80), DRY, false, true);
        assertEquals(seg(31, 40, "air", "b1", 0, 0), s.get(3));
        assertEquals(seg(30, 31, "top", "b1", 0, 0), s.get(4));
    }

    @Test
    void aLevelWithoutSkyLightIsDarkEverywhere() {
        var s = segments(runs(LO, 70), DRY, false, false);
        assertEquals(0, s.getFirst().skyLight());
        assertEquals(0, s.get(1).skyLight());
    }

    @Test
    void anEmptyColumnIsAirTopToFloorInTheColumnsBiome() {
        var s = segments(runs(), DRY, false, true);
        assertEquals(List.of(seg(MIN_Y, TOP, "air", "void", 15, 0)), s);
    }

    @Test
    void aOneBlockRunIsOnlyItsTop() {
        var s = segments(runs(LO, 10, 100, 100), DRY, true, true);
        assertEquals(seg(100, 101, "top", "b0", 15, 0), s.get(1));
        assertEquals("air", s.get(2).block());
    }

    @Test
    void clipDropsWhatIsAboveAndCutsTheRunThroughIt() {
        ColumnRuns r = runs(200, HI, 90, 120, LO, 40).clip(100);
        assertEquals(2, r.count());
        assertEquals(100, r.top(0));
        assertEquals(90, r.bottom(0));
        assertEquals(0, runs(200, HI).clip(100).count());
        ColumnRuns low = runs(LO, 40);
        assertSame(low, low.clip(100), "nothing above the cut");
    }

    @Test
    void withinCutsBothEndsAndSameAsComparesRuns() {
        ColumnRuns r = runs(200, 220, 90, 120, LO, 40).within(30, 100);
        assertEquals(2, r.count());
        assertEquals(100, r.top(0));
        assertEquals(30, r.bottom(1));
        assertEquals(true, r.sameAs(runs(90, 100, 30, 40)));
        assertEquals(false, r.sameAs(runs(90, 100)));
    }

    @Test
    void withFloorCarriesGroundCutAtTheWindowDownToTheNoiseFloor() {
        ColumnRuns cut = runs(32, 70);
        ColumnRuns carried = cut.withFloor(32, LO);
        assertEquals(LO, carried.bottom(0));
        assertEquals(70, carried.top(0));
        ColumnRuns floating = runs(50, 70);
        assertSame(floating, floating.withFloor(32, LO), "a run above the window's floor is left alone");
        assertSame(ColumnRuns.EMPTY, ColumnRuns.EMPTY.withFloor(32, LO));
    }

    @Test
    void theCheckNamesAGapAndAColumnShortOfTheFloor() {
        List<LodColumn.Segment<String, String>> gap = List.of(seg(70, TOP, "air", "b", 15, 0), seg(LO, 60, "stone", "b", 0, 0));
        assertEquals("gap above segment 1", LodBench.check(gap, MIN_Y));
        assertEquals("ends at 60, not the floor -64", LodBench.check(List.of(seg(60, TOP, "air", "b", 15, 0)), MIN_Y));
        assertEquals("no segments", LodBench.check(Collections.emptyList(), MIN_Y));
    }
}
