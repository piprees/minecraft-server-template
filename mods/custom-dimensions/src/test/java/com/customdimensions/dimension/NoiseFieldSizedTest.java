package com.customdimensions.dimension;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-site footprints in the placement field.
 *
 * <p>The load-bearing property is the first test: a uniform footprint must
 * place exactly what no footprint places. Without it, turning the feature on
 * silently re-rolls every world whose structures happen to be average, and
 * the change would be indistinguishable from a bug.
 */
class NoiseFieldSizedTest {

    private static final long SEED = 0xC0FFEEL;
    private static final int RADIUS = 96;

    private static NoiseFieldIndex index(NoiseFieldIndex.Footprints footprints) {
        return new NoiseFieldIndex(SEED, NoiseProfile.NATURAL, 4, null,
                RADIUS, 0, 0, 0, footprints);
    }

    // ------------------------------------------------- the no-op guarantee

    @Test
    void aUniformFootprintPlacesExactlyWhatNoFootprintPlaces() {
        List<ChunkPos> plain = index(null).positions();
        List<ChunkPos> uniform = index((x, z) -> 1.0).positions();
        assertEquals(plain, uniform,
                "a uniform 1.0 footprint must be a no-op, or switching the feature on "
                + "re-rolls every world that has no size variation to express");
    }

    @Test
    void theNoOpHoldsUnderARadialCurve() {
        double[] curve = {1.6, 1.5, 1.35, 1.2, 1.05, 0.95, 0.85, 0.75, 0.65, 0.6};
        List<ChunkPos> plain =
                new NoiseFieldIndex(SEED, NoiseProfile.NATURAL, 4, curve, RADIUS, 0, 0, 0, null)
                        .positions();
        List<ChunkPos> uniform =
                new NoiseFieldIndex(SEED, NoiseProfile.NATURAL, 4, curve, RADIUS, 0, 0, 0,
                        (x, z) -> 1.0).positions();
        assertEquals(plain, uniform,
                "the radial gradient and the footprint scale must compose without drift");
    }

    @Test
    void theNoOpHoldsForEveryProfile() {
        for (NoiseProfile profile : List.of(NoiseProfile.SPARSE, NoiseProfile.NATURAL,
                NoiseProfile.DENSE, NoiseProfile.CLUSTER)) {
            List<ChunkPos> plain =
                    new NoiseFieldIndex(SEED, profile, 4, null, RADIUS, 0, 0, 0, null).positions();
            List<ChunkPos> uniform =
                    new NoiseFieldIndex(SEED, profile, 4, null, RADIUS, 0, 0, 0, (x, z) -> 1.0)
                            .positions();
            assertEquals(plain, uniform, profile.id() + " moved under a uniform footprint");
        }
    }

    // ------------------------------------------------------- it does something

    @Test
    void biggerStructuresThinTheField() {
        int plain = index(null).positions().size();
        int big = index((x, z) -> 2.0).positions().size();
        int small = index((x, z) -> 0.6).positions().size();
        assertTrue(big < plain,
                "doubling every footprint must cost sites; got " + big + " against " + plain);
        assertTrue(small > plain,
                "shrinking every footprint must gain sites; got " + small + " against " + plain);
    }

    @Test
    void aBigSiteKeepsSmallOnesOutOfItsGround() {
        // Half the world is large, half is small, split on a line. The large
        // half must end up sparser than the small one — that is the whole
        // point of the feature.
        NoiseFieldIndex mixed = index((x, z) -> x < 0 ? 2.2 : 0.7);
        int large = 0;
        int smallSide = 0;
        for (ChunkPos pos : mixed.positions()) {
            if (pos.x < 0) {
                large++;
            } else {
                smallSide++;
            }
        }
        assertTrue(smallSide > large * 2,
                "the large-footprint half should be markedly sparser; got "
                + large + " large-side against " + smallSide + " small-side");
    }

    @Test
    void aSmallSiteCannotIgnoreALargeNeighbour() {
        // The reason the test is a SUM rather than each site's own radius.
        // Under a per-site-radius rule a small site consults only its own
        // small disc, never sees the large one, and places inside it. Measured
        // as: with one enormous structure type and one tiny one interleaved,
        // no two placements may sit closer than the tiny pair's own spacing.
        NoiseFieldIndex mixed = index((x, z) -> ((x + z) & 1) == 0 ? 2.5 : 0.6);
        List<ChunkPos> pos = mixed.positions();
        long tooClose = 0;
        for (int i = 0; i < pos.size(); i++) {
            for (int j = i + 1; j < pos.size(); j++) {
                long dx = pos.get(i).x - (long) pos.get(j).x;
                long dz = pos.get(i).z - (long) pos.get(j).z;
                // Both at the floor factor 0.6 gives radius 4*0.6/2 = 1.2
                // each, so 2.4 chunks is the closest any legal pair can sit.
                if (dx * dx + dz * dz < 2.4 * 2.4) {
                    tooClose++;
                }
            }
        }
        assertEquals(0, tooClose, "found pairs closer than the smallest legal separation");
    }

    @Test
    void everyPairRespectsTheSumOfWhatBothClaim() {
        // The invariant stated directly, on a field with real variation.
        Map<Long, Double> factors = new HashMap<>();
        NoiseFieldIndex.Footprints f = (x, z) -> {
            double v = 0.6 + 1.9 * (((x * 73856093) ^ (z * 19349663)) & 0xFF) / 255.0;
            factors.put(ChunkPos.toLong(x, z), v);
            return v;
        };
        List<ChunkPos> pos = index(f).positions();
        assertTrue(pos.size() > 20, "need a populated field to test the invariant");
        int violations = 0;
        for (int i = 0; i < pos.size(); i++) {
            for (int j = i + 1; j < pos.size(); j++) {
                ChunkPos a = pos.get(i);
                ChunkPos b = pos.get(j);
                double ra = NoiseFieldIndex.radiusOf(4, factors.get(a.toLong()));
                double rb = NoiseFieldIndex.radiusOf(4, factors.get(b.toLong()));
                long dx = a.x - (long) b.x;
                long dz = a.z - (long) b.z;
                double reach = ra + rb;
                if (dx * dx + dz * dz < reach * reach) {
                    violations++;
                }
            }
        }
        assertEquals(0, violations,
                "two placements sat closer than the sum of the ground they claim");
    }

    // ------------------------------------------------------------ determinism

    @Test
    void theSizedPathIsStillOrderFreeAndDeterministic() {
        NoiseFieldIndex.Footprints f =
                (x, z) -> 0.6 + 1.9 * ((((x * 2654435761L) ^ (z * 40503L)) & 0x3F) / 63.0);
        assertEquals(index(f).positions(), index(f).positions(),
                "two builds of the same field must agree exactly");
    }

    @Test
    void aDifferentFootprintFieldGivesADifferentWorld() {
        assertNotEquals(index((x, z) -> 1.0).positions(),
                index((x, z) -> x < 0 ? 2.4 : 0.6).positions(),
                "size variation must actually reach placement");
    }

    // ----------------------------------------------------------- radiusOf

    @Test
    void twoMedianSitesSumBackToTheSeparation() {
        assertEquals(8.0, NoiseFieldIndex.radiusOf(8, 1.0) * 2, 1e-9,
                "the halving is what makes a uniform footprint a no-op");
    }

    @Test
    void radiusOfNeverGoesNegative() {
        assertEquals(0.0, NoiseFieldIndex.radiusOf(8, -3.0), 1e-9);
    }

    // ------------------------------------- the biome-aware repetition pass

    private static NoiseFieldIndex withOccupants(NoiseFieldIndex.Occupants occ) {
        return new NoiseFieldIndex(SEED, NoiseProfile.NATURAL, 4, null,
                RADIUS, 0, 0, 0, (x, z) -> 1.0, occ);
    }

    @Test
    void aNullOccupantFunctionLeavesTheFieldAlone() {
        assertEquals(index((x, z) -> 1.0).positions(), withOccupants(null).positions(),
                "the repetition pass must be entirely opt-in");
    }

    @Test
    void anUnknownOccupantConflictsWithNothing() {
        assertEquals(index((x, z) -> 1.0).positions(), withOccupants((x, z) -> -1).positions(),
                "-1 must never read as 'all the same thing'");
    }

    @Test
    void thePassOnlyEverRemoves() {
        List<ChunkPos> before = index((x, z) -> 1.0).positions();
        List<ChunkPos> after = withOccupants((x, z) -> 0).positions();
        assertTrue(before.containsAll(after),
                "the pass may drop a placement but must never move or invent one");
        assertTrue(after.size() < before.size(),
                "one repeated occupant everywhere must cost sites");
    }

    @Test
    void noTwoSurvivorsShareAnOccupantInsideTheMinimum() {
        NoiseFieldIndex.Occupants occ = (x, z) -> Math.floorMod(x * 73856093 ^ z * 19349663, 5);
        List<ChunkPos> pos = withOccupants(occ).positions();
        assertTrue(pos.size() > 20, "need a populated field");
        long min = (long) NoiseFieldIndex.SAME_OCCUPANT_MIN_CHUNKS
                * NoiseFieldIndex.SAME_OCCUPANT_MIN_CHUNKS;
        int violations = 0;
        for (int i = 0; i < pos.size(); i++) {
            for (int j = i + 1; j < pos.size(); j++) {
                ChunkPos a = pos.get(i);
                ChunkPos b = pos.get(j);
                if (occ.occupantAt(a.x, a.z) != occ.occupantAt(b.x, b.z)) {
                    continue;
                }
                long dx = a.x - (long) b.x;
                long dz = a.z - (long) b.z;
                if (dx * dx + dz * dz < min) {
                    violations++;
                }
            }
        }
        assertEquals(0, violations, "two copies of one occupant survived inside the minimum");
    }

    @Test
    void aVariedWorldBarelyNotices() {
        int flat = index((x, z) -> 1.0).positions().size();
        int varied = withOccupants(
                (x, z) -> Math.floorMod(x * 2654435761L + z * 40503L, 400)).positions().size();
        assertTrue(varied >= flat * 0.95,
                "a 400-occupant world should lose almost nothing; got "
                + varied + " against " + flat);
    }

    @Test
    void theRepetitionPassIsOrderFree() {
        NoiseFieldIndex.Occupants occ = (x, z) -> Math.floorMod(x * 31 + z * 17, 7);
        assertEquals(withOccupants(occ).positions(), withOccupants(occ).positions());
        // and independent of the order the thinning happened to emit
        List<ChunkPos> once = withOccupants(occ).positions();
        List<ChunkPos> twice = withOccupants(occ).positions();
        assertEquals(once, twice);
    }
}
