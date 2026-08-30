package com.customdimensions.dimension;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-robin filler must not outrank an author's band inside the band's own
 * window, run through vanilla's real lookup rather than argued.
 *
 * <p>Inside a band's cube both the band and any filler cell containing the
 * same point are at axis distance 0, and {@code getSquaredDistance} adds
 * {@code square(offset)} — so the winner is whichever offset is smaller, and
 * filler's was zero. A band could not win its own territory.
 *
 * <p>The discrimination is the point: natives and natural cells are declared
 * placements and keep the handicap that lets a closer one take a cell off a
 * band. That is the balance {@code BAND_OFFSET_BASE} was measured against and
 * it must not move.
 */
class FillerFloorTest {

    private static MultiNoiseUtil.ParameterRange range(double lo, double hi) {
        return MultiNoiseUtil.ParameterRange.of((float) lo, (float) hi);
    }

    private static final MultiNoiseUtil.ParameterRange OPEN = range(-2.0, 2.0);

    /** The band under test: distance zero anywhere inside it, so it pays only its offset. */
    private static final DimensionManager.BandCube BAND = new DimensionManager.BandCube(
            MultiNoiseUtil.createNoiseHypercube(OPEN, OPEN, OPEN, OPEN, OPEN,
                    range(-0.9, -0.3), 0.0f), false);

    /** Wide open on every axis: it contains the band's window whole, as a dealt pool cell does. */
    private static Pair<MultiNoiseUtil.NoiseHypercube, String> cell(String name) {
        return Pair.of(MultiNoiseUtil.createNoiseHypercube(
                OPEN, OPEN, OPEN, OPEN, OPEN, OPEN, 0.0f), name);
    }

    private static final MultiNoiseUtil.NoiseValuePoint INSIDE_THE_BAND =
            MultiNoiseUtil.createNoiseValuePoint(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.6f);

    private static final float BAND_OFFSET = 0.45f;

    /**
     * The real pipeline: stamp the band, assemble declared in
     * native-natural-filler order, floor the filler tier, bands first.
     */
    private static String winnerAt(boolean withNative, boolean withFloor) {
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> bandCells =
                List.of(Pair.of(DimensionManager.withDefaultOffset(BAND, BAND_OFFSET), "band"));

        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> declared = new ArrayList<>();
        if (withNative) {
            declared.add(cell("native"));
        }
        int fillerFrom = declared.size();
        declared.add(cell("filler"));

        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> placed = withFloor
                ? DimensionManager.withFillerFloor(declared, fillerFrom,
                        DimensionManager.fillerFloor(DimensionManager.maxOffsetOf(bandCells)))
                : declared;

        return new MultiNoiseUtil.Entries<>(DimensionManager.bandsBeforeDeclared(bandCells, placed))
                .getValueSimple(INSIDE_THE_BAND);
    }

    @Test
    void withoutTheFloorFillerTakesTheBandsOwnWindow() {
        // The defect, executed. Without this the next test passes on a fixture
        // where the band would have won anyway and reads exactly like a pass.
        assertEquals("filler", winnerAt(false, false),
                "the defect is that a zero-offset filler cell beats the band at distance zero");
    }

    @Test
    void aFillerCellNeverOutranksABandInsideItsOwnWindow() {
        assertEquals("band", winnerAt(false, true));
    }

    @Test
    void aNativeStillTakesACellOffABandAndFillerStillDoesNot() {
        // The discrimination, at one point, in one table: the native is a
        // declared placement and keeps its zero offset, so it outranks the
        // band exactly as before; the filler beside it does not.
        assertEquals("native", winnerAt(true, true));
        assertEquals("native", winnerAt(true, false),
                "flooring filler must not change what a native wins");
    }

    @Test
    void everyCellBelowTheFillerIndexIsReturnedByIdentity() {
        // Identity, not equality: a rebuilt native would be a silent change to
        // the tier this fix must not touch.
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> declared =
                List.of(cell("native"), cell("natural"), cell("filler"));

        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> out =
                DimensionManager.withFillerFloor(declared, 2, 4501L);

        assertSame(declared.get(0), out.get(0));
        assertSame(declared.get(1), out.get(1));
        assertNotSame(declared.get(2), out.get(2));
        assertEquals(4501L, out.get(2).getFirst().offset());
        assertEquals(0L, out.get(0).getFirst().offset());
        assertEquals(0L, out.get(1).getFirst().offset());
    }

    @Test
    void aFillerCellAlreadyHeavierThanTheFloorKeepsItsOwnOffset() {
        // The floor is a floor, not an assignment: a projected filler cell
        // whose own offset already exceeds it must not be lowered.
        Pair<MultiNoiseUtil.NoiseHypercube, String> heavy = Pair.of(
                MultiNoiseUtil.createNoiseHypercube(OPEN, OPEN, OPEN, OPEN, OPEN, OPEN, 0.9f),
                "filler");

        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> out =
                DimensionManager.withFillerFloor(List.of(heavy), 0, 4501L);

        assertSame(heavy, out.get(0));
        assertEquals(9000L, out.get(0).getFirst().offset());
    }

    @Test
    void theFloorIsOneFixedPointUnitAboveTheHeaviestBand() {
        assertEquals(4501L, DimensionManager.fillerFloor(4500L));
        assertEquals(1L, DimensionManager.fillerFloor(0L));
    }

    @Test
    void theFloorNeverExceedsWhatVanillaWillEncode() {
        // Same ceiling as the two band-offset paths: past 1.0 the hypercube's
        // codec refuses and level.dat saves with no WorldGenSettings.
        assertEquals(DimensionManager.OFFSET_MAX,
                DimensionManager.fillerFloor(DimensionManager.OFFSET_MAX));
        assertEquals(DimensionManager.OFFSET_MAX,
                DimensionManager.fillerFloor(DimensionManager.OFFSET_MAX - 1L));
    }

    @Test
    void theHeaviestBandIsWhatTheFloorIsBuiltOn() {
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> bands = List.of(
                Pair.of(MultiNoiseUtil.createNoiseHypercube(
                        OPEN, OPEN, OPEN, OPEN, OPEN, OPEN, 0.2f), "a"),
                Pair.of(MultiNoiseUtil.createNoiseHypercube(
                        OPEN, OPEN, OPEN, OPEN, OPEN, OPEN, 0.4f), "b"));

        assertEquals(4000L, DimensionManager.maxOffsetOf(bands),
                "an authored offset can exceed the default, so the floor reads the cells");
        assertEquals(0L, DimensionManager.maxOffsetOf(List.of()));
    }

    @Test
    void anAuthoredBandHeavierThanTheDefaultStillOutranksFiller() {
        // the_frozen_strait authors 0.40 where its default is 0.234. A floor
        // built on the DEFAULT would sit under the authored band and filler
        // would take that band's window.
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> bandCells = List.of(
                Pair.of(MultiNoiseUtil.createNoiseHypercube(OPEN, OPEN, OPEN, OPEN, OPEN,
                        range(-0.9, -0.3), 0.40f), "authored"));
        List<Pair<MultiNoiseUtil.NoiseHypercube, String>> placed =
                DimensionManager.withFillerFloor(List.of(cell("filler")), 0,
                        DimensionManager.fillerFloor(DimensionManager.maxOffsetOf(bandCells)));

        assertTrue(placed.get(0).getFirst().offset() > 4000L,
                "the floor must clear the heaviest AUTHORED band, not the default");
        assertEquals("authored",
                new MultiNoiseUtil.Entries<>(
                        DimensionManager.bandsBeforeDeclared(bandCells, placed))
                        .getValueSimple(INSIDE_THE_BAND));
    }
}
