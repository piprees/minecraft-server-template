package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What carrying the pool at {@link NoisePoolBuilder#WEIGHT_RESOLUTION} does to
 * placement when nothing is wanted or shunned.
 *
 * <p>It preserves every structure's SHARE of the draw and changes which
 * structure lands on any given chunk. {@code resolveWeighted} takes
 * {@code Long.remainderUnsigned(pickValue, totalWeight)}, and that is not
 * linear in {@code totalWeight}: scaling numerator and denominator together
 * moves every cumulative boundary, so the distribution survives and the
 * assignment does not. Anything reasoning about the scale as a no-op for
 * placement is reasoning about the distribution.
 */
class PoolWeightScalingTest {

    private static final int SITES = 500;

    /** The measured overworld's weight classes, a tenth of its member count. */
    private static List<int[]> SHAPE = List.of(
            new int[] {17, 1}, new int[] {19, 3}, new int[] {13, 8}, new int[] {1, 160});

    private static List<StructurePick.PoolEntry> pool(int unitsPerWeight) {
        String[] names = {"rare", "uncommon", "common", "heavy"};
        List<StructurePick.PoolEntry> out = new ArrayList<>();
        for (int c = 0; c < SHAPE.size(); c++) {
            for (int i = 0; i < SHAPE.get(c)[0]; i++) {
                out.add(new StructurePick.PoolEntry(
                        String.format("test:%s_%02d", names[c], i),
                        SHAPE.get(c)[1] * unitsPerWeight));
            }
        }
        return StructurePick.sortedPool(out);
    }

    private static List<String> assignments(List<StructurePick.PoolEntry> sorted) {
        long pickSeed = StructurePick.pickSeed(0x5eedL);
        List<String> out = new ArrayList<>(SITES * SITES);
        for (int cx = 0; cx < SITES; cx++) {
            for (int cz = 0; cz < SITES; cz++) {
                out.add(StructurePick.resolveWeighted(
                        sorted, StructurePick.pick(pickSeed, cx, cz)));
            }
        }
        return out;
    }

    private static Map<String, Integer> tally(List<String> assignments) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String id : assignments) {
            counts.merge(id, 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void scalingKeepsEveryStructuresShareOfTheDraw() {
        Map<String, Integer> plain = tally(assignments(pool(1)));
        Map<String, Integer> scaled = tally(assignments(
                pool(NoisePoolBuilder.WEIGHT_RESOLUTION)));
        assertEquals(plain.keySet(), scaled.keySet(), "a structure gained or lost every site");
        int total = SITES * SITES;
        for (String id : plain.keySet()) {
            double before = plain.get(id) / (double) total;
            double after = scaled.get(id) / (double) total;
            // Three standard deviations of a binomial at this sample size is
            // well under a tenth of the smallest share in the pool.
            assertTrue(Math.abs(after - before) < 0.004,
                    id + " share moved from " + before + " to " + after);
        }
    }

    @Test
    void scalingDoesNotPreserveTheAssignmentAtAGivenSite() {
        // Stated as an assertion because it is the cost of the resolution and
        // it is easy to assume away: every dimension is re-dealt, including
        // one that names no want and no shun.
        List<String> plain = assignments(pool(1));
        List<String> scaled = assignments(pool(NoisePoolBuilder.WEIGHT_RESOLUTION));
        int differing = 0;
        for (int i = 0; i < plain.size(); i++) {
            if (!plain.get(i).equals(scaled.get(i))) {
                differing++;
            }
        }
        assertTrue(differing > plain.size() / 4,
                "expected the scale to re-deal most sites; only " + differing
                + " of " + plain.size() + " moved");
    }

    @Test
    void aPoolEntryRemembersWhetherItWasWantedOrShunned() {
        // favourWeight folds the flags into an int that cannot be read back:
        // a wanted weight-1 entry and a plain entry at weight 1.2 are the same
        // number. The flags ride on the entry so a diagnostic can still answer.
        StructurePick.PoolEntry wanted = new StructurePick.PoolEntry(
                "test:a", NoisePoolBuilder.favourWeight(1, true, false), false, null, true, false);
        StructurePick.PoolEntry shunned = new StructurePick.PoolEntry(
                "test:b", NoisePoolBuilder.favourWeight(1, false, true), false, null, false, true);
        assertTrue(wanted.wanted() && !wanted.shunned());
        assertTrue(shunned.shunned() && !shunned.wanted());
        assertEquals(18, wanted.weight());
        assertEquals(10, shunned.weight());
    }

    @Test
    void theShortConstructorsLeaveBothFlagsOff() {
        assertTrue(!new StructurePick.PoolEntry("test:a", 15).wanted());
        assertTrue(!new StructurePick.PoolEntry("test:a", 15).shunned());
        assertTrue(!new StructurePick.PoolEntry("test:a", 15, true, null).wanted());
    }
}
