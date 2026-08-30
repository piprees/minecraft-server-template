package com.customdimensions.dimension;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What raising the band default does to the lookup, run rather than argued.
 *
 * <p>{@code NoiseHypercube.getSquaredDistance} adds {@code square(offset)} to
 * the six axis terms and nothing else, so a default stamped on every unauthored
 * band adds the SAME constant to every band and nothing to a native. Two
 * consequences the whole feature rests on: a cell can only ever move band to
 * native as the default rises, and a table whose every cell is a band is
 * unchanged by any default at all.
 *
 * <p>The second is the claim that suppresses the drift warning on 46 of the 68
 * banded dimensions ({@code DimensionConfig.defaultOffsetCanAct}). It was
 * derived from the code and never executed.
 *
 * <p>Lookups go through {@code getValueSimple}, the plain argmin. The tree
 * lookup adds the {@code ThreadLocal} incumbent on top ([T59]), which can only
 * decide a TIE — and once the default is above zero a band/native tie is no
 * longer a tie, in the native's favour, which is the same direction.
 */
class BandOffsetMonotonicityTest {

    private static MultiNoiseUtil.ParameterRange range(double lo, double hi) {
        return MultiNoiseUtil.ParameterRange.of((float) lo, (float) hi);
    }

    private static final MultiNoiseUtil.ParameterRange OPEN = range(-2.0, 2.0);

    /** A single-axis band: distance zero anywhere inside it, so it pays only the offset. */
    private static DimensionManager.BandCube band(double lo, double hi) {
        return new DimensionManager.BandCube(MultiNoiseUtil.createNoiseHypercube(
                OPEN, OPEN, OPEN, OPEN, OPEN, range(lo, hi), 0.0f), false);
    }

    /** A native paying on two axes and authoring no offset of its own. */
    private static MultiNoiseUtil.NoiseHypercube nativeCube(double weirdness, double temperature) {
        return MultiNoiseUtil.createNoiseHypercube(
                range(temperature, temperature), OPEN, OPEN, OPEN, OPEN,
                range(weirdness, weirdness), 0.0f);
    }

    private static MultiNoiseUtil.NoiseValuePoint point(double weirdness, double temperature) {
        return MultiNoiseUtil.createNoiseValuePoint(
                (float) temperature, 0.0f, 0.0f, 0.0f, 0.0f, (float) weirdness);
    }

    /** The query lattice, in a fixed order so every run compares like with like. */
    private static List<MultiNoiseUtil.NoiseValuePoint> lattice() {
        List<MultiNoiseUtil.NoiseValuePoint> out = new ArrayList<>();
        for (int w = -10; w <= 10; w++) {
            for (int t = -4; t <= 4; t++) {
                out.add(point(w / 10.0, t / 4.0));
            }
        }
        return out;
    }

    private static final List<DimensionManager.BandCube> BANDS = List.of(
            band(-0.9, -0.3), band(-0.1, 0.2), band(0.4, 0.9));

    private static final List<Pair<MultiNoiseUtil.NoiseHypercube, String>> NATIVES = List.of(
            Pair.of(nativeCube(-0.6, 0.5), "native_a"),
            Pair.of(nativeCube(0.0, -0.5), "native_b"),
            Pair.of(nativeCube(0.7, 0.75), "native_c"));

    /** The assignment over the lattice with the bands stamped at {@code defaultOffset}. */
    private static Map<MultiNoiseUtil.NoiseValuePoint, String> assign(
            float defaultOffset, boolean withNatives) {
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> entries = new ArrayList<>();
        for (int i = 0; i < BANDS.size(); i++) {
            entries.add(Pair.of(
                    DimensionManager.withDefaultOffset(BANDS.get(i), defaultOffset), "band_" + i));
        }
        if (withNatives) {
            entries.addAll(NATIVES);
        }
        MultiNoiseUtil.Entries<String> table = new MultiNoiseUtil.Entries<>(entries);
        Map<MultiNoiseUtil.NoiseValuePoint, String> out = new LinkedHashMap<>();
        for (MultiNoiseUtil.NoiseValuePoint p : lattice()) {
            out.put(p, table.getValueSimple(p));
        }
        return out;
    }

    private static Set<MultiNoiseUtil.NoiseValuePoint> heldByBands(float defaultOffset) {
        Set<MultiNoiseUtil.NoiseValuePoint> out = new LinkedHashSet<>();
        for (Map.Entry<MultiNoiseUtil.NoiseValuePoint, String> e : assign(defaultOffset, true).entrySet()) {
            if (e.getValue().startsWith("band_")) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static final float[] SWEEP = {0.0f, 0.1f, 0.2f, 0.3f, 0.45f, 0.6f, 0.8f, 1.0f};

    @Test
    void bandGroundOnlyEverShrinksAsTheDefaultRises() {
        Set<MultiNoiseUtil.NoiseValuePoint> previous = heldByBands(SWEEP[0]);
        for (int i = 1; i < SWEEP.length; i++) {
            Set<MultiNoiseUtil.NoiseValuePoint> now = heldByBands(SWEEP[i]);
            for (MultiNoiseUtil.NoiseValuePoint p : now) {
                assertTrue(previous.contains(p),
                        "a cell moved back to a band at default " + SWEEP[i]
                                + " — nothing raises a band's standing, so this cannot happen");
            }
            previous = now;
        }
    }

    @Test
    void theFixtureReallyDoesLoseGround() {
        // Without this the subset assertion above passes on a fixture where
        // nothing ever moves, which reads exactly like a pass [T63].
        assertEquals(189, lattice().size(), "the lattice is 21 weirdness x 9 temperature");
        int atZero = heldByBands(0.0f).size();
        int atOne = heldByBands(1.0f).size();
        assertTrue(atZero > 0, "the bands must hold something at offset 0 to be able to lose it");
        assertTrue(atOne < atZero,
                "the bands must actually lose ground across the sweep, or the "
                        + "monotonicity test is vacuous: " + atZero + " -> " + atOne);
    }

    @Test
    void anAllBandedTableIsIdenticalAtEveryDefault() {
        // The 46-of-68 no-op: every cell is a band, every band takes the same
        // default, so the identical square(offset) is common-mode and the
        // argmin cannot move for any value of BAND_OFFSET_BASE.
        Map<MultiNoiseUtil.NoiseValuePoint, String> baseline = assign(SWEEP[0], false);
        for (float offset : SWEEP) {
            assertEquals(baseline, assign(offset, false),
                    "an all-banded table moved at default " + offset
                            + " — the common-mode argument behind defaultOffsetCanAct is wrong");
        }
    }
}
