package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunDressTest {
    static final int LO = -64;
    static final int HI = 127;

    /** A stand-in material rule: grass on a dry top, sand under water, a ceiling block on an underside, else the default. */
    static final class Stub implements RunDress.Materials<String> {
        final List<String> calls = new ArrayList<>();

        @Override
        public String at(int x, int y, int z, int depthAbove, int depthBelow, int waterHeight) {
            calls.add(y + ":" + depthAbove + ":" + depthBelow + ":" + (waterHeight == Integer.MIN_VALUE ? "dry" : waterHeight));
            if (depthBelow == 1 && depthAbove > 1) return "ceiling";
            if (depthAbove == 1) return waterHeight == Integer.MIN_VALUE ? "grass" : "sand";
            return null;
        }
    }

    /** Water below y 63, as a sea-level fluid picker gives it. */
    static final RunDress.Fluids<String> SEA = (x, y, z) -> y < 63 ? new RunDress.Fluid<>("water", 63) : null;

    static ColumnRuns runs(int... bounds) {
        boolean[] solid = new boolean[HI - LO + 1];
        for (int i = 0; i < bounds.length; i += 2) {
            for (int y = bounds[i]; y <= bounds[i + 1]; y++) solid[y - LO] = true;
        }
        return ColumnRuns.of(solid, LO, solid.length);
    }

    @Test
    void aDryTopTakesTheRulesSurfaceBlock() {
        Stub rule = new Stub();
        RunDress<String> d = RunDress.of(runs(LO, 70), 0, 0, LO, HI, rule, SEA, "stone", false);
        assertEquals("grass", d.top(0));
        assertNull(d.fluid(0));
        assertEquals(List.of("70:1:135:dry"), rule.calls);
    }

    @Test
    void aTopUnderTheSeaSeesTheWaterHeightAndCarriesTheFluid() {
        Stub rule = new Stub();
        RunDress<String> d = RunDress.of(runs(LO, 40), 0, 0, LO, HI, rule, SEA, "stone", false);
        assertEquals("sand", d.top(0));
        assertEquals("water", d.fluid(0));
        assertEquals(63, d.fluidLevel(0));
        assertEquals(List.of("40:1:105:63"), rule.calls);
    }

    @Test
    void theFluidSurfaceStopsAtTheRunAbove() {
        RunDress<String> d = RunDress.of(runs(50, HI, LO, 40), 0, 0, LO, HI, new Stub(), SEA, "stone", true);
        assertEquals(50, d.fluidLevel(1), "water from 41 to 49, under the roof at 50");
        assertEquals("ceiling", d.bottom(0));
        assertNull(d.bottom(1), "the floor rests on the bottom of the noise");
        assertNull(d.fluid(0), "nothing is open above a roof at the top of the noise");
    }

    @Test
    void undersidesAreLeftOutUnlessAsked() {
        Stub rule = new Stub();
        RunDress<String> d = RunDress.of(runs(100, 110), 0, 0, LO, HI, rule, SEA, "stone", false);
        assertNull(d.bottom(0));
        assertEquals(1, rule.calls.size());
    }

    @Test
    void withAquifersACaveUnderARunTakesNoPickerFluid() {
        ColumnRuns r = runs(40, 50, LO, 20);
        RunDress<String> d = RunDress.of(r, 0, 0, LO, HI, new Stub(), SEA, "stone", true, RunDress.openRuns(r.count(), true, false));
        assertEquals("water", d.fluid(0), "the sea over the topmost run, under open sky");
        assertNull(d.fluid(1), "the cave between 21 and 39 stays open");
    }

    @Test
    void withAquifersAFloorUnderARoofTakesNoPickerFluid() {
        ColumnRuns floor = runs(LO, 40);
        RunDress<String> d = RunDress.of(floor, 0, 0, LO, HI, new Stub(), SEA, "stone", true, RunDress.openRuns(1, true, true));
        assertNull(d.fluid(0), "a floor whose roof the coarse shortcut left out is under ground");
        assertEquals("grass", d.top(0), "and its top is dressed dry");
        ColumnRuns roofed = runs(100, HI, 20, 40, LO, 0);
        RunDress<String> whole = RunDress.of(roofed, 0, 0, LO, HI, new Stub(), SEA, "stone", true, RunDress.openRuns(3, true, true));
        for (int i = 0; i < roofed.count(); i++) assertNull(whole.fluid(i), "run " + i + " of a roofed whole column");
    }

    @Test
    void withoutAquifersEveryOpenRunTakesThePickerFluid() {
        ColumnRuns r = runs(100, HI, LO, 20);
        assertEquals(2, RunDress.openRuns(2, false, true));
        RunDress<String> d = RunDress.of(r, 0, 0, LO, HI, new Stub(), SEA, "stone", true, RunDress.openRuns(r.count(), false, true));
        assertEquals("water", d.fluid(1), "a roofed sea in a level without aquifers, as the Nether's lava");
        assertEquals(0, RunDress.openRuns(0, true, false));
        assertEquals(1, RunDress.openRuns(3, true, false));
    }

    @Test
    void aBlockTheRuleLeavesIsTheDefault() {
        RunDress.Materials<String> none = (x, y, z, a, b, w) -> null;
        RunDress<String> d = RunDress.of(runs(100, 110, LO, 0), 0, 0, LO, HI, none, SEA, "stone", true);
        assertEquals("stone", d.top(0));
        assertEquals("stone", d.bottom(0));
        assertEquals("stone", d.top(1));
    }
}
