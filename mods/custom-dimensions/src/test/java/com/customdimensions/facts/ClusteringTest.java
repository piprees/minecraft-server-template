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
        // Above the sampling cap the input is strided, not randomly drawn —
        // a fact must not depend on which run computed it.
        List<long[]> many = grid(80, 3);   // 6400 points, over the 4000 cap
        double a = FactsEngine.clustering(many, 200).orThrow();
        double b = FactsEngine.clustering(many, 200).orThrow();
        assertEquals(a, b, 0.0, "the same layout gave two different answers");
    }
}
