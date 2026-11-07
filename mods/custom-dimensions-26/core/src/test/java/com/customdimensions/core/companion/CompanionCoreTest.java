package com.customdimensions.core.companion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.companion.EntitySnapshot.Seen;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompanionCoreTest {

    // --- FeedBudget -------------------------------------------------------------------------

    @Test
    void budgetCapsChunksPerPass() {
        FeedBudget b = new FeedBudget(4, 1_000_000);
        b.startPass(0);
        int sent = 0;
        for (int i = 0; i < 10; i++) {
            if (b.trySpendChunk(1000)) sent++;
        }
        assertEquals(4, sent);
        b.startPass(4);
        assertTrue(b.trySpendChunk(1000), "a new pass resets the chunk count");
    }

    @Test
    void budgetDefersAChunkTheBucketCannotCover() {
        FeedBudget b = new FeedBudget(100, 20_000);
        b.startPass(0);
        assertTrue(b.trySpendChunk(15_000));
        assertFalse(b.trySpendChunk(15_000), "5,000 bytes left cannot cover 15,000");
        assertEquals(5_000, b.tokens());
        b.startPass(10); // half a second refills 10,000
        assertTrue(b.trySpendChunk(15_000));
    }

    @Test
    void budgetRefillNeverExceedsOneSecond() {
        FeedBudget b = new FeedBudget(4, 10_000);
        b.startPass(0);
        b.startPass(10_000);
        assertEquals(10_000, b.tokens());
    }

    @Test
    void undeferredPayloadsAreChargedAndCanOverdraw() {
        FeedBudget b = new FeedBudget(4, 1_000);
        b.startPass(0);
        b.charge(1_500);
        assertEquals(-500, b.tokens());
        assertFalse(b.trySpendChunk(1), "an overdrawn bucket sends no chunk");
        assertEquals(1_500, b.spent());
        b.charge(-5);
        assertEquals(1_500, b.spent(), "a negative charge is ignored");
    }

    @Test
    void sustainedSendingStaysUnderTheCeiling() {
        FeedBudget b = new FeedBudget(4, 50_000);
        long sent = 0;
        for (long tick = 0; tick < 200; tick += 4) {
            b.startPass(tick);
            while (b.trySpendChunk(9_000)) sent += 9_000;
        }
        assertTrue(sent <= FeedBudget.ceiling(50_000, 200), sent + " over " + FeedBudget.ceiling(50_000, 200));
        assertTrue(sent >= 400_000, "the bucket is spent, not idle: " + sent);
    }

    @Test
    void budgetClampsNonsenseConstruction() {
        FeedBudget b = new FeedBudget(0, 0);
        assertEquals(1, b.chunksPerPass());
        assertEquals(1, b.bytesPerSecond());
    }

    // --- ViewPreference ---------------------------------------------------------------------

    @Test
    void preferenceClampsAndFeedsOnlyALocalRenderer() {
        ViewPreference p = new ViewPreference(true, false, 99, 5);
        assertEquals(ViewPreference.MAX_RENDER_DISTANCE, p.maxRenderDistance());
        assertEquals(ViewPreference.MIN_VIEW_DEPTH, p.viewDepth());
        assertTrue(p.feeds());
        assertFalse(new ViewPreference(false, true, 8, 64).feeds());
        assertFalse(ViewPreference.UNDECLARED.feeds());
    }

    @Test
    void handshakeAdmitsOnlyTheSameRelease() {
        assertTrue(ViewPreference.admits("6.0.0", "6.0.0"));
        assertFalse(ViewPreference.admits("6.0.0", "6.0.1"));
        assertFalse(ViewPreference.admits("6.0.0", null));
        assertFalse(ViewPreference.admits(null, null));
    }

    // --- FeedPlan ---------------------------------------------------------------------------

    @Test
    void chunkKeysRoundTripNegatives() {
        long k = FeedPlan.chunkKey(-3, 7);
        assertEquals(-3, FeedPlan.chunkX(k));
        assertEquals(7, FeedPlan.chunkZ(k));
        long m = FeedPlan.chunkKey(12, -40);
        assertEquals(12, FeedPlan.chunkX(m));
        assertEquals(-40, FeedPlan.chunkZ(m));
    }

    @Test
    void throughOpeningSeesOnlyBeyondThePlaneAndInsideTheGap() {
        // Opening spans a = [0, 2] at n = 10; eye at a = 1, n = 0.
        assertTrue(FeedPlan.throughOpening(1, 0, 0, 2, 10, 1, 30));
        assertFalse(FeedPlan.throughOpening(1, 0, 0, 2, 10, 1, 5), "short of the plane");
        assertFalse(FeedPlan.throughOpening(1, 0, 0, 2, 10, 1, -5), "behind the eye");
        assertFalse(FeedPlan.throughOpening(1, 0, 0, 2, 10, 50, 30), "outside the gap");
        assertFalse(FeedPlan.throughOpening(1, 10, 0, 2, 10, 1, 30), "eye in the plane");
    }

    @Test
    void coreRunsForwardAlongTheFarSide() {
        assertEquals(5, FeedPlan.coreDepth(64));
        assertEquals(1, FeedPlan.coreDepth(-4));
        assertTrue(FeedPlan.inCore(0, 4, FeedPlan.Normal.Z, true, 5));
        assertFalse(FeedPlan.inCore(0, 5, FeedPlan.Normal.Z, true, 5));
        assertTrue(FeedPlan.inCore(0, -2, FeedPlan.Normal.Z, true, 5));
        assertFalse(FeedPlan.inCore(0, -3, FeedPlan.Normal.Z, true, 5));
        assertTrue(FeedPlan.inCore(-4, 0, FeedPlan.Normal.X, false, 5));
        assertFalse(FeedPlan.inCore(3, 0, FeedPlan.Normal.Y, false, 5));
        assertEquals(11.0, FeedPlan.surface(10, true));
        assertEquals(10.0, FeedPlan.surface(10, false));
    }

    @Test
    void nextChunksStartsAtTheArrivalAndSkipsWhatWasSent() {
        FeedPlan.Sight sight = new FeedPlan.Sight(1, -4, 0, 2, 1, 0, 0, FeedPlan.Normal.Z, true);
        Set<Long> sent = new HashSet<>();
        List<Long> first = FeedPlan.nextChunks(0, 0, 8, sight, 5, sent, 4);
        assertEquals(4, first.size());
        assertEquals(FeedPlan.chunkKey(0, 0), first.get(0), "the arrival chunk goes first");
        sent.addAll(first);
        List<Long> second = FeedPlan.nextChunks(0, 0, 8, sight, 5, sent, 4);
        assertEquals(4, second.size());
        for (Long k : second) assertFalse(first.contains(k));
    }

    @Test
    void nextChunksDrainsToNothingAndRespectsTheRadius() {
        FeedPlan.Sight sight = new FeedPlan.Sight(1, -4, 0, 2, 1, 0, 0, FeedPlan.Normal.Z, true);
        Set<Long> sent = new HashSet<>();
        int rounds = 0;
        while (rounds < 1000) {
            List<Long> next = FeedPlan.nextChunks(0, 0, 4, sight, 2, sent, 16);
            if (next.isEmpty()) break;
            sent.addAll(next);
            rounds++;
        }
        assertTrue(rounds < 1000);
        for (long k : sent) {
            int x = FeedPlan.chunkX(k);
            int z = FeedPlan.chunkZ(k);
            boolean core = FeedPlan.inCore(x, z, FeedPlan.Normal.Z, true, 2);
            assertTrue(core || x * x + z * z <= 16, "outside the radius: " + x + "," + z);
            assertTrue(core || z >= 0, "a wedge chunk behind the opening: " + x + "," + z);
        }
        assertTrue(FeedPlan.nextChunks(0, 0, 0, sight, 2, sent, 4).isEmpty());
        assertTrue(FeedPlan.nextChunks(0, 0, 4, sight, 2, new HashSet<>(), 0).isEmpty());
        assertTrue(FeedPlan.nextChunks(0, 0, 4, null, 2, new HashSet<>(), 4).isEmpty());
    }

    @Test
    void aFlatOpeningIsFedAsADisc() {
        FeedPlan.Sight flat = new FeedPlan.Sight(0, 100, 0, 3, 64, 0, 0, FeedPlan.Normal.Y, false);
        Set<Long> sent = new HashSet<>();
        sent.addAll(FeedPlan.nextChunks(0, 0, 3, flat, 1, sent, 1000));
        int disc = 0;
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) if (x * x + z * z <= 9) disc++;
        assertEquals(disc, sent.size());
    }

    // --- EntitySnapshot ---------------------------------------------------------------------

    @Test
    void dueHonoursTheIntervalAndNeverFed() {
        assertTrue(EntitySnapshot.due(5, Long.MIN_VALUE, 4));
        assertFalse(EntitySnapshot.due(5, 3, 4));
        assertTrue(EntitySnapshot.due(7, 3, 4));
        assertTrue(EntitySnapshot.due(4, 3, 0), "a non-positive interval is every tick");
    }

    @Test
    void selectKeepsTheDiscNearestFirstAndCaps() {
        List<Seen> all = new ArrayList<>();
        all.add(new Seen(1, 10, 64, 0, 0, 0, 0));
        all.add(new Seen(2, 2, 64, 0, 0, 0, 0));
        all.add(new Seen(3, 100, 64, 0, 0, 0, 0)); // outside the radius
        all.add(new Seen(4, 0, 200, 0, 0, 0, 0)); // outside the height
        all.add(new Seen(5, 2, 64, 0, 0, 0, 0)); // ties with 2, larger id
        List<Seen> picked = EntitySnapshot.select(all, 0, 64, 0, 48, 32, 2);
        assertEquals(List.of(2, 5), picked.stream().map(Seen::id).toList());
        assertTrue(EntitySnapshot.select(null, 0, 0, 0, 48, 32, 5).isEmpty());
        assertTrue(EntitySnapshot.select(all, 0, 0, 0, 48, 32, 0).isEmpty());
    }

    @Test
    void changedIgnoresMovesBelowWireResolution() {
        List<Seen> a = List.of(new Seen(1, 0, 64, 0, 10, 0, 10));
        List<Seen> tiny = List.of(new Seen(1, 0.0001, 64, 0, 10.1f, 0, 10));
        List<Seen> moved = List.of(new Seen(1, 0.5, 64, 0, 10, 0, 10));
        assertFalse(EntitySnapshot.changed(a, tiny));
        assertTrue(EntitySnapshot.changed(a, moved));
        assertTrue(EntitySnapshot.changed(a, List.of()));
        assertTrue(EntitySnapshot.changed(null, a));
    }

    @Test
    void departedNamesOnlyTheGone() {
        List<Seen> before = List.of(new Seen(1, 0, 0, 0, 0, 0, 0), new Seen(2, 0, 0, 0, 0, 0, 0));
        List<Seen> after = List.of(new Seen(2, 1, 0, 0, 0, 0, 0), new Seen(3, 0, 0, 0, 0, 0, 0));
        assertArrayEquals(new int[]{1}, EntitySnapshot.departed(before, after));
        assertArrayEquals(new int[]{1, 2}, EntitySnapshot.departed(before, null));
        assertArrayEquals(new int[0], EntitySnapshot.departed(List.of(), after));
    }
}
