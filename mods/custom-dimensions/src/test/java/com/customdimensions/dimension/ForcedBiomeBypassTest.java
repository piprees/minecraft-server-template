package com.customdimensions.dimension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The arm/apply hand-off behind {@code structures.force}'s biome bypass.
 * ForcedBiomeBypass is deliberately Minecraft-free (same reason
 * FixedStructurePlacement.Index is split out — StructurePlacement's static
 * init needs Bootstrap), so this exercises the real class, not a stand-in.
 */
class ForcedBiomeBypassTest {

    @BeforeEach
    void reset() {
        ForcedBiomeBypass.resetForTests();
    }

    /**
     * The arm is rewritten by every placement, not only forced ones. This is
     * the property that stops a /locate probe of a forced chunk leaking a
     * bypass into the next unrelated structure start on the same thread.
     */
    @Test
    void armIsOverwrittenByEveryPlacement() {
        assertNull(ForcedBiomeBypass.armed());

        ForcedBiomeBypass.arm("the_boneyard");
        assertEquals("the_boneyard", ForcedBiomeBypass.armed());

        ForcedBiomeBypass.arm(null);   // an ordinary placement answering next
        assertNull(ForcedBiomeBypass.armed());
    }

    /** consumeApplied reads once and clears, so a stale value cannot log twice. */
    @Test
    void appliedIsConsumedExactlyOnce() {
        ForcedBiomeBypass.markApplied("the_gilded_pit");
        assertEquals("the_gilded_pit", ForcedBiomeBypass.consumeApplied());
        assertNull(ForcedBiomeBypass.consumeApplied());

        // Every start attempt writes, including the non-forced ones.
        ForcedBiomeBypass.markApplied("the_gilded_pit");
        ForcedBiomeBypass.markApplied(null);
        assertNull(ForcedBiomeBypass.consumeApplied());
    }

    /**
     * Chunk generation runs on the worker pool while /locate runs on the
     * server thread; one thread's arm must never be visible to another.
     */
    @Test
    void stateIsPerThread() throws Exception {
        ForcedBiomeBypass.arm("the_boneyard");

        AtomicReference<String> seen = new AtomicReference<>("unset");
        CountDownLatch done = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            seen.set(ForcedBiomeBypass.armed());
            ForcedBiomeBypass.arm("somewhere_else");
            done.countDown();
        });
        other.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "worker thread did not finish");
        other.join();

        assertNull(seen.get(), "the worker thread saw another thread's arm");
        assertEquals("the_boneyard", ForcedBiomeBypass.armed(),
                "the worker thread overwrote this thread's arm");
    }

    /** One log line per (dimension, structure, chunk), not one per chunk load. */
    @Test
    void firstSightingDedupesPerForcedPosition() {
        assertTrue(ForcedBiomeBypass.firstSighting("the_boneyard", "minecraft:ancient_city", 75, -50));
        assertFalse(ForcedBiomeBypass.firstSighting("the_boneyard", "minecraft:ancient_city", 75, -50));

        // A different position, structure or dimension is a different sighting.
        assertTrue(ForcedBiomeBypass.firstSighting("the_boneyard", "minecraft:ancient_city", 75, -49));
        assertTrue(ForcedBiomeBypass.firstSighting("the_boneyard", "minecraft:igloo", 75, -50));
        assertTrue(ForcedBiomeBypass.firstSighting("the_gilded_pit", "minecraft:ancient_city", 75, -50));
    }

    /** The dedupe set is capped so a pathological config cannot grow it forever. */
    @Test
    void firstSightingStopsAtTheCap() {
        for (int i = 0; i < ForcedBiomeBypass.LOG_CAP; i++) {
            assertTrue(ForcedBiomeBypass.firstSighting("d", "minecraft:igloo", i, 0),
                    "position " + i + " should be a first sighting");
        }
        assertFalse(ForcedBiomeBypass.firstSighting("d", "minecraft:igloo", 999999, 0));
    }
}
