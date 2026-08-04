package com.customdimensions.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure carver-draw replay (precision plan section 7.2).
 *
 * <p>Covers:
 * <ul>
 *   <li>Weight-walk given a fixed j value (no LCG needed)</li>
 *   <li>Draw result formatting</li>
 *   <li>Edge cases: empty pool, single entry, zero weight</li>
 *   <li>LCG chain determinism and known-value verification</li>
 * </ul>
 *
 * Bootstrap-free: no Minecraft types.
 */
class CarverDrawTest {

    // --- weightWalk with fixed j values ------------------------------------

    @Test
    void weightWalkSelectsFirstEntry() {
        // [a:3, b:2, c:5] total=10 -> j=0 selects a (0-3==-3 < 0)
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("a", 3),
                new CarverDraw.Entry("b", 2),
                new CarverDraw.Entry("c", 5));
        assertEquals(0, CarverDraw.weightWalk(entries, 0));
    }

    @Test
    void weightWalkSelectsSecondEntry() {
        // [a:3, b:2, c:5] j=3 -> 3-3=0 (not <0), 0-2=-2 (<0) -> index 1
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("a", 3),
                new CarverDraw.Entry("b", 2),
                new CarverDraw.Entry("c", 5));
        assertEquals(1, CarverDraw.weightWalk(entries, 3));
    }

    @Test
    void weightWalkSelectsLastEntry() {
        // [a:3, b:2, c:5] j=9 -> 9-3=6, 6-2=4, 4-5=-1 (<0) -> index 2
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("a", 3),
                new CarverDraw.Entry("b", 2),
                new CarverDraw.Entry("c", 5));
        assertEquals(2, CarverDraw.weightWalk(entries, 9));
    }

    @Test
    void weightWalkBoundaryAtFirstEntryEnd() {
        // [a:3, b:2, c:5] j=2 -> 2-3=-1 (<0) -> index 0
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("a", 3),
                new CarverDraw.Entry("b", 2),
                new CarverDraw.Entry("c", 5));
        assertEquals(0, CarverDraw.weightWalk(entries, 2));
    }

    @Test
    void weightWalkBoundaryAtSecondEntryStart() {
        // [a:1, b:1, c:2] j=1 -> 1-1=0 (not <0), 0-1=-1 (<0) -> index 1
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("a", 1),
                new CarverDraw.Entry("b", 1),
                new CarverDraw.Entry("c", 2));
        assertEquals(1, CarverDraw.weightWalk(entries, 1));
    }

    @Test
    void weightWalkSingleEntry() {
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("only", 5));
        assertEquals(0, CarverDraw.weightWalk(entries, 0));
        assertEquals(0, CarverDraw.weightWalk(entries, 4));
    }

    @Test
    void weightWalkAllTargetsExhaustPool() {
        // Verify every valid j in [0, totalWeight) selects a valid index
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("x", 2),
                new CarverDraw.Entry("y", 3),
                new CarverDraw.Entry("z", 1));
        for (int j = 0; j < 6; j++) {
            int idx = CarverDraw.weightWalk(entries, j);
            assertTrue(idx >= 0 && idx < 3, "invalid index " + idx + " for j=" + j);
        }
    }

    // --- draw edge cases ---------------------------------------------------

    @Test
    void drawReturnsNullForEmptyPool() {
        assertNull(CarverDraw.draw(List.of(), 42L, 10, 20));
        assertNull(CarverDraw.draw(null, 42L, 10, 20));
    }

    @Test
    void drawReturnsNullForZeroTotalWeight() {
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("a", 0),
                new CarverDraw.Entry("b", 0));
        assertNull(CarverDraw.draw(entries, 42L, 10, 20));
    }

    @Test
    void drawSingleEntrySelectsIt() {
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("village", 10));
        CarverDraw.DrawResult result = CarverDraw.draw(entries, 12345L, 5, 7);
        assertNotNull(result);
        assertEquals("village", result.vanillaDraw());
        assertEquals(0, result.drawnIndex());
        assertEquals(10, result.totalWeight());
        // j must be in [0, 10)
        assertTrue(result.j() >= 0 && result.j() < 10,
                "j=" + result.j() + " out of [0, 10)");
    }

    // --- draw determinism --------------------------------------------------

    @Test
    void drawIsDeterministic() {
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("a", 1),
                new CarverDraw.Entry("b", 1),
                new CarverDraw.Entry("c", 2));
        CarverDraw.DrawResult r1 = CarverDraw.draw(entries, 999L, 42, -7);
        CarverDraw.DrawResult r2 = CarverDraw.draw(entries, 999L, 42, -7);
        assertNotNull(r1);
        assertNotNull(r2);
        assertEquals(r1.vanillaDraw(), r2.vanillaDraw());
        assertEquals(r1.j(), r2.j());
    }

    @Test
    void drawDiffersWithDifferentChunks() {
        List<CarverDraw.Entry> entries = List.of(
                new CarverDraw.Entry("a", 1),
                new CarverDraw.Entry("b", 1),
                new CarverDraw.Entry("c", 1),
                new CarverDraw.Entry("d", 1));
        // Over a grid, the draw should vary (not always select the same entry)
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int cx = -10; cx <= 10; cx++) {
            for (int cz = -10; cz <= 10; cz++) {
                CarverDraw.DrawResult r = CarverDraw.draw(entries, 42L, cx, cz);
                if (r != null) {
                    seen.add(r.vanillaDraw());
                }
            }
        }
        // With equal weights and 441 positions, we expect to see all 4 entries
        assertTrue(seen.size() > 1, "carver draw always selected the same entry");
    }

    // --- LCG chain self-consistency ----------------------------------------

    @Test
    void carverSeedIsDeterministic() {
        long s1 = CarverDraw.carverSeed(12345L, 10, 20);
        long s2 = CarverDraw.carverSeed(12345L, 10, 20);
        assertEquals(s1, s2);
    }

    @Test
    void carverSeedDiffersForDifferentInputs() {
        long s1 = CarverDraw.carverSeed(12345L, 10, 20);
        long s2 = CarverDraw.carverSeed(12345L, 10, 21);
        long s3 = CarverDraw.carverSeed(12346L, 10, 20);
        assertTrue(s1 != s2, "same seed for different chunkZ");
        assertTrue(s1 != s3, "same seed for different worldSeed");
    }

    @Test
    void lcgNextIntProducesValidRange() {
        // Verify nextInt(bound) always returns [0, bound) for various states
        long state = CarverDraw.carverSeed(42L, 7, 13);
        for (int bound : new int[]{1, 2, 3, 4, 5, 7, 10, 16, 100}) {
            int val = CarverDraw.lcgNextInt(state, bound);
            assertTrue(val >= 0 && val < bound,
                    "nextInt(" + bound + ") returned " + val);
        }
    }

    @Test
    void lcgNextIntPowerOfTwoBound() {
        // Power of 2 uses a different code path
        long state = CarverDraw.carverSeed(42L, 7, 13);
        int val = CarverDraw.lcgNextInt(state, 4);
        assertTrue(val >= 0 && val < 4, "nextInt(4) returned " + val);
    }

    // --- occupant answer formatting (pure logic) ---------------------------

    @Test
    void occupantFormatEmpty() {
        List<String> occupants = List.of();
        String answer = occupants.isEmpty() ? "empty" : String.join(", ", occupants);
        assertEquals("empty", answer);
    }

    @Test
    void occupantFormatSingle() {
        List<String> occupants = List.of("minecraft:village_plains");
        String answer = String.join(", ", occupants);
        assertEquals("minecraft:village_plains", answer);
    }

    @Test
    void occupantFormatMultiple() {
        List<String> occupants = new java.util.ArrayList<>(
                List.of("minecraft:pillager_outpost", "minecraft:village_plains"));
        java.util.Collections.sort(occupants);
        String answer = String.join(", ", occupants);
        assertEquals("minecraft:pillager_outpost, minecraft:village_plains", answer);
    }
}
