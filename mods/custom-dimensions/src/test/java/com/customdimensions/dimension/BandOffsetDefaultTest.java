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
    void anOffsetAboveOneIsClampedAndStaysTheAuthors() {
        // Laundering an author error into the default would make a typo
        // indistinguishable from writing nothing, and the fingerprint —
        // which keys on the offset being STATED — would then record no
        // default in use while the band was using one.
        DimensionManager.BandCube band = parse(5.0);

        assertTrue(band.offsetAuthored());
        assertEquals(Math.round(1.0 * SCALE), band.cube().offset());
        assertEquals(Math.round(1.0 * SCALE),
                DimensionManager.withDefaultOffset(band, 0.25f).offset());
    }

    @Test
    void anOffsetBelowZeroIsClampedAndStaysTheAuthors() {
        DimensionManager.BandCube band = parse(-3.0);

        assertTrue(band.offsetAuthored());
        assertEquals(0L, band.cube().offset());
        assertEquals(0L, DimensionManager.withDefaultOffset(band, 0.25f).offset());
    }

    @Test
    void anOffsetThatIsNotANumberIsUnauthoredSoTheDefaultApplies() {
        // Nothing to clamp, so this is the one invalid form that does take the
        // default — and it warns rather than passing silently.
        JsonObject params = params(null);
        params.addProperty("offset", "quite a lot");
        DimensionManager.BandCube band =
                DimensionManager.bandCubeFrom(params, "a_dimension", "minecraft:taiga");

        assertFalse(band.offsetAuthored());
        assertEquals(Math.round(0.25 * SCALE),
                DimensionManager.withDefaultOffset(band, 0.25f).offset());
    }

    @Test
    void theFingerprintAndTheCubeAgreeOnWhoAuthored() {
        // One rule, two callers. If they ever disagree, a band the fingerprint
        // calls authored takes the default anyway and the drift term is a lie.
        for (Object value : new Object[]{null, 0.0, 0.5, 5.0, -3.0}) {
            JsonObject params = params(value == null ? null : (Double) value);
            boolean fromConfig = DimensionConfig.bandAuthorsOffset(params);
            boolean fromCube = DimensionManager
                    .bandCubeFrom(params, "a_dimension", "minecraft:taiga").offsetAuthored();
            assertEquals(fromConfig, fromCube, "disagreed on offset=" + value);
        }
    }

    @Test
    void anUnauthoredBandTakesTheDefault() {
        assertEquals(Math.round(0.25 * SCALE),
                DimensionManager.withDefaultOffset(parse(null), 0.25f).offset());
    }

    @Test
    void theDefaultIsNeverAboveWhatVanillaWillEncode() {
        // MultiNoiseUtil$NoiseHypercube binds offset to Codec.floatRange(0, 1)
        // and the encode failure is SILENT: level.dat saves WITHOUT
        // WorldGenSettings and the next boot dies at
        // "No key dimensions in MapLike[{}]". Observed live at BASE 1.00 with a
        // measured factor of 1.978.
        // Factors far above any measured g, so this holds whatever BASE ships.
        for (double factor : new double[] {1.978, 10.0, 100.0, 1000.0}) {
            float offset = DimensionManager.defaultBandOffset(factor);
            assertTrue(offset <= 1.0f,
                    "offset " + offset + " at factor " + factor
                            + " is outside [0,1] and cannot be persisted");
        }
        assertEquals(1.0f, DimensionManager.defaultBandOffset(1000.0), 1e-6);
    }

    @Test
    void theClampDoesNotTouchAFactorThatFitsAlready() {
        // The clamp must be a ceiling, not a rescale — every in-range value
        // still comes through as BASE * factor exactly.
        assertEquals(DimensionConfig.BAND_OFFSET_BASE * 0.583f,
                DimensionManager.defaultBandOffset(0.583), 1e-6);
    }

    @Test
    void theBaseIsPinnedAsALiteralSoAChangeIsVisible() {
        // basalt_deltas, in MultiNoiseBiomeSourceParameterList$Preset$1;
        // warped_forest's 0.375 is the other and is the top of the range.
        // Pinned as a literal on purpose: a test written against the constant
        // moves with it and cannot see it change.
        assertEquals(0.175f, DimensionConfig.BAND_OFFSET_BASE);
    }

    @Test
    void theDefaultScalesWithTheFactorTheProjectionApplied() {
        // the_lantern_pools measures 0.583, so its bands are sized against
        // natives whose own offsets were multiplied by that.
        assertEquals(0.175f * 0.583f, DimensionManager.defaultBandOffset(0.583), 1e-6);
    }

    @Test
    void anUnprojectedDimensionGetsTheBaseUnscaled() {
        // Both projection refusals report 1.0, and the additive path never
        // projects at all — there the rivals pay their own offsets.
        assertEquals(0.175f, DimensionManager.defaultBandOffset(1.0), 1e-6);
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
    void bandsComeBeforeDeclaredCells() {
        // Order is behaviour: two entries at equal distance are settled by
        // which the SearchTree traversal reaches first (T59), so a refactor
        // that swaps these groups changes which biome wins tied cells.
        assertEquals(java.util.List.of("band1", "band2", "declared1", "declared2"),
                DimensionManager.bandsBeforeDeclared(
                        java.util.List.of("band1", "band2"),
                        java.util.List.of("declared1", "declared2")));
    }

    @Test
    void eitherSideOfTheOrderMayBeEmpty() {
        assertEquals(java.util.List.of("d"),
                DimensionManager.bandsBeforeDeclared(java.util.List.of(), java.util.List.of("d")));
        assertEquals(java.util.List.of("b"),
                DimensionManager.bandsBeforeDeclared(java.util.List.of("b"), java.util.List.of()));
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
