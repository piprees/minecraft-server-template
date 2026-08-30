package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.JsonObject;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offset a band gets when its author writes none:
 * {@link DimensionManager#bandCubeFrom}, {@link DimensionManager#defaultBandOffset}
 * and {@link DimensionManager#withDefaultOffset}.
 *
 * <p>A band pays nothing on the axes it does not name where a native pays on
 * every axis it declares, so the offset is the only term a band cannot avoid
 * ([T19]). Natives reach the lookup with their offsets multiplied by the
 * projection's factor, which is why the default tracks it.
 */
class BandOffsetDefaultTest {

    /** The game's fixed point: a hypercube stores climate as {@code v * 10000}. */
    private static final double SCALE = 10000.0;

    private static com.google.gson.JsonArray range(double lo, double hi) {
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        out.add(lo);
        out.add(hi);
        return out;
    }

    /**
     * Every axis carries a DIFFERENT range on purpose. An unconstrained axis
     * is {@code [-2, 2]} like every other one, so a fixture naming only
     * weirdness is palindromic under a swap and cannot see a reordered rebuild.
     */
    private static JsonObject params(Double offset) {
        JsonObject params = new JsonObject();
        params.add("temperature", range(-0.9, -0.8));
        params.add("humidity", range(-0.6, -0.5));
        params.add("continentalness", range(-0.3, -0.2));
        params.add("erosion", range(0.0, 0.1));
        params.add("depth", range(0.3, 0.4));
        params.add("weirdness", range(0.6, 0.7));
        if (offset != null) {
            params.addProperty("offset", offset);
        }
        return params;
    }

    private static DimensionManager.BandCube parse(Double offset) {
        return DimensionManager.bandCubeFrom(params(offset), "a_dimension", "minecraft:taiga");
    }

    @Test
    void aBandWritingNoOffsetIsUnauthored() {
        assertFalse(parse(null).offsetAuthored());
    }

    @Test
    void anExplicitZeroIsAuthoredAndKeepsItsZero() {
        // "absent" and "0" are different asks: 0 is an author saying this band
        // pays nothing, and the default must not overrule it.
        DimensionManager.BandCube band = parse(0.0);

        assertTrue(band.offsetAuthored());
        assertEquals(0L, DimensionManager.withDefaultOffset(band, 0.25f).offset());
    }

    @Test
    void anAuthoredOffsetOutranksTheDefault() {
        DimensionManager.BandCube band = parse(0.5);

        assertTrue(band.offsetAuthored());
        assertEquals(Math.round(0.5 * SCALE),
                DimensionManager.withDefaultOffset(band, 0.25f).offset());
    }

    @Test
    void anOffsetOutsideZeroToOneIsUnauthoredSoTheDefaultApplies() {
        // A typo is not an instruction to pay nothing. Falling back to 0 would
        // hand a mistyped band the largest possible advantage over a native.
        DimensionManager.BandCube band = parse(5.0);

        assertFalse(band.offsetAuthored());
        assertEquals(Math.round(0.25 * SCALE),
                DimensionManager.withDefaultOffset(band, 0.25f).offset());
    }

    @Test
    void anUnauthoredBandTakesTheDefault() {
        assertEquals(Math.round(0.25 * SCALE),
                DimensionManager.withDefaultOffset(parse(null), 0.25f).offset());
    }

    @Test
    void theBaseIsVanillasHeaviestAuthoredOffset() {
        // warped_forest, in MultiNoiseBiomeSourceParameterList$Preset$1. Pinned
        // as a literal on purpose: a test written against the constant moves
        // with it and cannot see it change.
        assertEquals(0.375f, DimensionConfig.BAND_OFFSET_BASE);
    }

    @Test
    void theDefaultScalesWithTheFactorTheProjectionApplied() {
        // the_lantern_pools measures 0.583, so its bands are sized against
        // natives whose own offsets were multiplied by that.
        assertEquals(0.375f * 0.583f, DimensionManager.defaultBandOffset(0.583), 1e-6);
    }

    @Test
    void anUnprojectedDimensionGetsTheBaseUnscaled() {
        // Both projection refusals report 1.0, and the additive path never
        // projects at all — there the rivals pay their own offsets.
        assertEquals(0.375f, DimensionManager.defaultBandOffset(1.0), 1e-6);
    }

    @Test
    void aLargerFactorMeansALargerDefault() {
        assertTrue(DimensionManager.defaultBandOffset(1.978)
                > DimensionManager.defaultBandOffset(0.313));
    }

    @Test
    void stampingTheDefaultChangesNothingButTheOffset() {
        // A rebuilt hypercube that dropped or reordered an axis would move the
        // band without any config saying so.
        MultiNoiseUtil.NoiseHypercube before = parse(null).cube();
        MultiNoiseUtil.NoiseHypercube after =
                DimensionManager.withDefaultOffset(parse(null), 0.25f);

        assertEquals(before.temperature(), after.temperature());
        assertEquals(before.humidity(), after.humidity());
        assertEquals(before.continentalness(), after.continentalness());
        assertEquals(before.erosion(), after.erosion());
        assertEquals(before.depth(), after.depth());
        assertEquals(before.weirdness(), after.weirdness());
        assertNotEquals(before.offset(), after.offset());
    }

    @Test
    void anUnparseableBandIsStillRejected() {
        // The default must not resurrect a band whose axes do not parse: its
        // biome falls back to plain-listed placement rather than being
        // withdrawn from the native tier.
        JsonObject bad = new JsonObject();
        bad.addProperty("weirdness", 9.0);

        assertNull(DimensionManager.bandCubeFrom(bad, "a_dimension", "minecraft:taiga"));
    }
}
