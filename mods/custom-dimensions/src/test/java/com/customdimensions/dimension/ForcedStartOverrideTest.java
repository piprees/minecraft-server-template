package com.customdimensions.dimension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The registry behind {@code structures.force}'s start override.
 * ForcedStartOverride is deliberately Minecraft-free (same reason
 * FixedStructurePlacement.Index is split out — StructurePlacement's static
 * init needs Bootstrap), so this exercises the real class, not a stand-in.
 */
class ForcedStartOverrideTest {

    // ChunkPos.toLong without the Minecraft class: x | z << 32, both masked.
    private static long chunkKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    @BeforeEach
    void reset() {
        ForcedStartOverride.resetForTests();
    }

    @Test
    void isForcedMatchesExactTripleOnly() {
        ForcedStartOverride.install("adventure:the_crimson_nexus", "the_crimson_nexus",
                ForcedStartOverride.byChunk(List.of(
                        new ForcedStartOverride.ForcedEntry("minecraft:fortress", chunkKey(17, -4)))));

        assertTrue(ForcedStartOverride.isForced(
                "adventure:the_crimson_nexus", chunkKey(17, -4), "minecraft:fortress"));
        // Different chunk, structure, or world is not forced.
        assertFalse(ForcedStartOverride.isForced(
                "adventure:the_crimson_nexus", chunkKey(17, -5), "minecraft:fortress"));
        assertFalse(ForcedStartOverride.isForced(
                "adventure:the_crimson_nexus", chunkKey(17, -4), "minecraft:igloo"));
        assertFalse(ForcedStartOverride.isForced(
                "adventure:the_gauntlet", chunkKey(17, -4), "minecraft:fortress"));
        assertFalse(ForcedStartOverride.isForced(
                "adventure:the_crimson_nexus", chunkKey(17, -4), null));
    }

    /** Two structures forced at one chunk, one structure forced at two chunks. */
    @Test
    void multipleForcesShareChunksAndStructures() {
        ForcedStartOverride.install("adventure:the_wuthering_wisteria", "the_wuthering_wisteria",
                ForcedStartOverride.byChunk(List.of(
                        new ForcedStartOverride.ForcedEntry("mns:copper_tower", chunkKey(3, 3)),
                        new ForcedStartOverride.ForcedEntry("mns:nether_tower", chunkKey(3, 3)),
                        new ForcedStartOverride.ForcedEntry("mns:copper_tower", chunkKey(-8, 1)))));

        assertTrue(ForcedStartOverride.isForced(
                "adventure:the_wuthering_wisteria", chunkKey(3, 3), "mns:copper_tower"));
        assertTrue(ForcedStartOverride.isForced(
                "adventure:the_wuthering_wisteria", chunkKey(3, 3), "mns:nether_tower"));
        assertTrue(ForcedStartOverride.isForced(
                "adventure:the_wuthering_wisteria", chunkKey(-8, 1), "mns:copper_tower"));
        assertFalse(ForcedStartOverride.isForced(
                "adventure:the_wuthering_wisteria", chunkKey(-8, 1), "mns:nether_tower"));
    }

    /** A rebuild with no forces clears the previous install — no stale triples. */
    @Test
    void emptyInstallClearsPreviousForces() {
        ForcedStartOverride.install("adventure:the_slatemouth", "the_slatemouth",
                ForcedStartOverride.byChunk(List.of(
                        new ForcedStartOverride.ForcedEntry("minecraft:fortress", chunkKey(10, 10)))));
        assertTrue(ForcedStartOverride.isForced(
                "adventure:the_slatemouth", chunkKey(10, 10), "minecraft:fortress"));

        ForcedStartOverride.install("adventure:the_slatemouth", "the_slatemouth", Map.of());
        assertFalse(ForcedStartOverride.isForced(
                "adventure:the_slatemouth", chunkKey(10, 10), "minecraft:fortress"));
        assertEquals(Set.of(), ForcedStartOverride.forcedChunks("adventure:the_slatemouth"));
    }

    /** A re-install replaces, never merges — the config is the whole truth. */
    @Test
    void reinstallReplacesRatherThanMerges() {
        ForcedStartOverride.install("adventure:d", "d", ForcedStartOverride.byChunk(List.of(
                new ForcedStartOverride.ForcedEntry("minecraft:igloo", chunkKey(1, 1)))));
        ForcedStartOverride.install("adventure:d", "d", ForcedStartOverride.byChunk(List.of(
                new ForcedStartOverride.ForcedEntry("minecraft:igloo", chunkKey(2, 2)))));

        assertFalse(ForcedStartOverride.isForced("adventure:d", chunkKey(1, 1), "minecraft:igloo"));
        assertTrue(ForcedStartOverride.isForced("adventure:d", chunkKey(2, 2), "minecraft:igloo"));
    }

    @Test
    void dimensionNameFallsBackToWorldId() {
        ForcedStartOverride.install("adventure:d", "the_display_name",
                ForcedStartOverride.byChunk(List.of(
                        new ForcedStartOverride.ForcedEntry("minecraft:igloo", chunkKey(0, 0)))));
        assertEquals("the_display_name", ForcedStartOverride.dimensionName("adventure:d"));
        assertEquals("adventure:nowhere", ForcedStartOverride.dimensionName("adventure:nowhere"));
    }

    /** One log line per (dimension, structure, chunk); success and failure dedupe apart. */
    @Test
    void logDedupeIsPerPositionAndOutcome() {
        assertTrue(ForcedStartOverride.firstSighting("d", "minecraft:ancient_city", 75, -50));
        assertFalse(ForcedStartOverride.firstSighting("d", "minecraft:ancient_city", 75, -50));
        assertTrue(ForcedStartOverride.firstSighting("d", "minecraft:ancient_city", 75, -49));

        // A failure at the same position is its own first, and dedupes itself.
        assertTrue(ForcedStartOverride.firstFailure("d", "minecraft:ancient_city", 75, -50));
        assertFalse(ForcedStartOverride.firstFailure("d", "minecraft:ancient_city", 75, -50));
    }

    /** A y is carried per exact triple; an entry without one answers null. */
    @Test
    void forcedYIsPerTripleAndOptional() {
        ForcedStartOverride.install("adventure:the_red_monument", "the_red_monument",
                ForcedStartOverride.byChunk(List.of(
                        new ForcedStartOverride.ForcedEntry("mes:monolith", chunkKey(0, 0), 96),
                        new ForcedStartOverride.ForcedEntry("mes:mythic_garden", chunkKey(-6, 10)))),
                ForcedStartOverride.heightsByChunk(List.of(
                        new ForcedStartOverride.ForcedEntry("mes:monolith", chunkKey(0, 0), 96),
                        new ForcedStartOverride.ForcedEntry("mes:mythic_garden", chunkKey(-6, 10)))));

        assertEquals(96, ForcedStartOverride.forcedY(
                "adventure:the_red_monument", chunkKey(0, 0), "mes:monolith"));
        // Same chunk, different structure; and an entry that named no y.
        assertNull(ForcedStartOverride.forcedY(
                "adventure:the_red_monument", chunkKey(0, 0), "mes:mythic_garden"));
        assertNull(ForcedStartOverride.forcedY(
                "adventure:the_red_monument", chunkKey(-6, 10), "mes:mythic_garden"));
        assertNull(ForcedStartOverride.forcedY(
                "adventure:nowhere", chunkKey(0, 0), "mes:monolith"));
        assertNull(ForcedStartOverride.forcedY(
                "adventure:the_red_monument", chunkKey(0, 0), null));
        // Forcing still works for both, y or no y.
        assertTrue(ForcedStartOverride.isForced(
                "adventure:the_red_monument", chunkKey(-6, 10), "mes:mythic_garden"));
    }

    /** heightsByChunk carries only the entries that named a y. */
    @Test
    void heightsByChunkSkipsEntriesWithoutY() {
        var heights = ForcedStartOverride.heightsByChunk(List.of(
                new ForcedStartOverride.ForcedEntry("a:one", chunkKey(1, 1), 40),
                new ForcedStartOverride.ForcedEntry("a:two", chunkKey(1, 1)),
                new ForcedStartOverride.ForcedEntry("a:three", chunkKey(2, 2))));

        assertEquals(Map.of(chunkKey(1, 1), Map.of("a:one", 40)), heights);
    }

    /** A negative y is a real height in a -64-floored world, not a "no value". */
    @Test
    void negativeYIsCarried() {
        ForcedStartOverride.install("adventure:d", "d",
                ForcedStartOverride.byChunk(List.of(
                        new ForcedStartOverride.ForcedEntry("a:deep", chunkKey(0, 0), -48))),
                ForcedStartOverride.heightsByChunk(List.of(
                        new ForcedStartOverride.ForcedEntry("a:deep", chunkKey(0, 0), -48))));
        assertEquals(-48, ForcedStartOverride.forcedY("adventure:d", chunkKey(0, 0), "a:deep"));
    }

    /** The dedupe set is capped so a pathological config cannot grow it forever. */
    @Test
    void logDedupeStopsAtTheCap() {
        for (int i = 0; i < ForcedStartOverride.LOG_CAP; i++) {
            assertTrue(ForcedStartOverride.firstSighting("d", "minecraft:igloo", i, 0),
                    "position " + i + " should be a first sighting");
        }
        assertFalse(ForcedStartOverride.firstSighting("d", "minecraft:igloo", 999999, 0));
    }
}
