package com.customdimensions.facts;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clark-Evans clustering: mean nearest-neighbour distance over the distance a
 * uniform scatter of the same count in the same area would give.
 *
 * <p>Below 1 means the placements sit in pockets — a PLACE a player finds and
 * explores. At or above 1 means an even spread — scenery. It is the one
 * structure fact whose value is not obvious from reading the code, so it is
 * pinned here against hand-worked layouts rather than against itself.
 */
class ClusteringTest {

    private static List<long[]> grid(int side, int spacing) {
        List<long[]> out = new ArrayList<>();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                out.add(new long[] {(long) x * spacing, (long) z * spacing});
            }
        }
        return out;
    }

    @Test
    void anEvenLatticeScoresAtOrAboveOne() {
        // A perfect lattice is the MOST even layout there is, so it must not
        // read as clustered. Every nearest neighbour is exactly `spacing`.
        List<long[]> lattice = grid(20, 8);   // 400 points, spacing 8
        // Radius chosen so the lattice fills the disc: 20*8 across is 160,
        // so a radius of ~90 chunks contains it.
        double v = FactsEngine.clustering(lattice, 90).orThrow();
        assertTrue(v >= 1.0, "an even lattice scored " + v + ", which would read as clustered");
    }

    @Test
    void tightPocketsScoreWellBelowAnEvenLayoutOfTheSameCount() {
        // Same count, same disc — only the arrangement differs. This is the
        // comparison the fact exists to make, and it is the one a position
        // COUNT cannot make at all.
        List<long[]> even = grid(10, 20);            // 100 points, far apart
        List<long[]> pockets = new ArrayList<>();
        for (int p = 0; p < 4; p++) {                // 4 tight pockets of 25
            long ox = (p % 2) * 150L;
            long oz = (p / 2) * 150L;
            for (int i = 0; i < 25; i++) {
                pockets.add(new long[] {ox + i % 5, oz + i / 5});
            }
        }
        assertEquals(even.size(), pockets.size(), "the comparison only means "
                + "something at equal counts");
        double evenScore = FactsEngine.clustering(even, 120).orThrow();
        double pocketScore = FactsEngine.clustering(pockets, 120).orThrow();
        assertTrue(pocketScore < evenScore,
                "pockets scored " + pocketScore + ", even scored " + evenScore);
        assertTrue(pocketScore < 1.0,
                "pockets scored " + pocketScore + ", which does not read as clustered");
    }

    @Test
    void fewerThanTwoPlacementsIsAbsentNotZero() {
        // Zero would say "perfectly clustered", which is a claim about a
        // layout that does not exist.
        assertFalse(FactsEngine.clustering(List.of(), 100).isPresent());
        assertFalse(FactsEngine.clustering(
                List.of(new long[] {0, 0}), 100).isPresent());
        assertTrue(FactsEngine.clustering(List.of(), 100).reason()
                .contains("fewer than two"));
    }

    @Test
    void aZeroRadiusIsAbsentRatherThanADivisionByZero() {
        // The expected spacing divides by the area; a zero radius would
        // otherwise produce infinity, which is not a measurement.
        Measured<Double> m = FactsEngine.clustering(grid(4, 4), 0);
        assertFalse(m.isPresent());
        assertTrue(m.reason().contains("radius"));
    }

    @Test
    void theResultIsDeterministicForTheSameInput() {
        // A fact must not depend on which run computed it.
        List<long[]> many = grid(80, 3);   // 6400 points
        double a = FactsEngine.clustering(many, 200).orThrow();
        double b = FactsEngine.clustering(many, 200).orThrow();
        assertEquals(a, b, 0.0, "the same layout gave two different answers");
    }

    @Test
    void theBucketedSearchAgreesWithBruteForceOnEveryPoint() {
        // The bucketed search is only worth having if it is exact, so it is
        // checked against the O(n^2) definition on layouts that stress the ring
        // bound: a lattice (every neighbour at the same distance), pockets (empty
        // rings between clusters), a single line (one cell row), and coincident
        // points (distance zero).
        List<List<long[]>> layouts = List.of(
                grid(12, 7),
                pockets(4, 25, 150),
                line(50, 9),
                coincident());
        for (List<long[]> layout : layouts) {
            double bucketed = FactsEngine.clustering(layout, 200).orThrow();
            double brute = bruteForceClarkEvans(layout, 200);
            assertEquals(brute, bucketed, 0.0,
                    "bucketed and brute-force disagree on a " + layout.size()
                    + "-point layout");
        }
    }

    @Test
    void poolingGroupsHidesAPocketedGroupInsideADispersedOne() {
        // The reason clustering is measured per group. Two groups, measured
        // apart, are plainly different: one sits in four tight pockets, the other
        // is a wide lattice. Pooled into one statistic the answer reads as
        // dispersed and the pocketed group is invisible.
        List<long[]> pocketed = pockets(4, 25, 150);
        List<long[]> dispersed = grid(10, 20);
        List<long[]> pooled = new ArrayList<>(pocketed);
        pooled.addAll(dispersed);

        double perGroupPocketed = FactsEngine.clustering(pocketed, 120).orThrow();
        double perGroupDispersed = FactsEngine.clustering(dispersed, 120).orThrow();
        double pooledValue = FactsEngine.clustering(pooled, 120).orThrow();

        assertTrue(perGroupPocketed < 1.0,
                "the pocketed group must read as pocketed on its own, got "
                + perGroupPocketed);
        assertTrue(perGroupDispersed >= 1.0,
                "the dispersed group must read as dispersed on its own, got "
                + perGroupDispersed);
        assertTrue(pooledValue > perGroupPocketed,
                "pooling must move the answer away from the pocketed group's own "
                + "value, or there is nothing to correct: pooled " + pooledValue
                + " vs pocketed " + perGroupPocketed);

        var byGroup = FactsEngine.clusteringByGroup(java.util.Map.of(
                "dungeons", pocketed, "deco", dispersed), 120).orThrow();
        assertEquals(2, byGroup.size());
        assertEquals(perGroupPocketed, byGroup.get("dungeons"), 0.0);
        assertEquals(perGroupDispersed, byGroup.get("deco"), 0.0);
    }

    @Test
    void aGroupWithOnePlacementIsOmittedRatherThanFilledIn() {
        // A group's absence from the map is the honest report. A filler value
        // would be indistinguishable from a measurement and would drag any
        // average computed over the map.
        var byGroup = FactsEngine.clusteringByGroup(java.util.Map.of(
                "dungeons", grid(6, 5),
                "endgame", List.of(new long[] {0, 0}),
                "loot", List.<long[]>of()), 120).orThrow();
        assertEquals(1, byGroup.size(), byGroup.toString());
        assertTrue(byGroup.containsKey("dungeons"));

        Measured<java.util.Map<String, Double>> none = FactsEngine.clusteringByGroup(
                java.util.Map.of("endgame", List.of(new long[] {0, 0})), 120);
        assertFalse(none.isPresent());
        assertTrue(none.reason().contains("two placements"), none.reason());
    }

    // ------------------------------------------------------------------ rigs

    private static List<long[]> pockets(int count, int per, int apart) {
        List<long[]> out = new ArrayList<>();
        int side = (int) Math.ceil(Math.sqrt(per));
        for (int p = 0; p < count; p++) {
            long ox = (long) (p % 2) * apart;
            long oz = (long) (p / 2) * apart;
            for (int i = 0; i < per; i++) {
                out.add(new long[] {ox + i % side, oz + i / side});
            }
        }
        return out;
    }

    private static List<long[]> line(int count, int spacing) {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new long[] {(long) i * spacing, 0});
        }
        return out;
    }

    /** Two placements in the same chunk: a real case across two groups. */
    private static List<long[]> coincident() {
        return List.of(new long[] {5, 5}, new long[] {5, 5},
                new long[] {40, 40}, new long[] {41, 44});
    }

    /** Clark-Evans straight from the definition, O(n^2), no bucketing. */
    private static double bruteForceClarkEvans(List<long[]> positions, int radiusChunks) {
        int n = positions.size();
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double best = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                double dx = positions.get(i)[0] - positions.get(j)[0];
                double dz = positions.get(i)[1] - positions.get(j)[1];
                best = Math.min(best, dx * dx + dz * dz);
            }
            sum += Math.sqrt(best);
        }
        double observed = sum / n;
        double area = Math.PI * (double) radiusChunks * radiusChunks;
        return observed / (0.5 / Math.sqrt(n / area));
    }
}
