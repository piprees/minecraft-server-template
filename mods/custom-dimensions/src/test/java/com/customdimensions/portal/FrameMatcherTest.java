package com.customdimensions.portal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registry-free half of FrameMatcher: form parsing/classification and the
 * plain-id fast path. Block/tag RESOLUTION needs a live registry and is
 * covered by the bot recipes in the local verification loop.
 */
class FrameMatcherTest {

    @Test
    void classifiesPlainIdsAndTags() {
        FrameMatcher m = FrameMatcher.of(List.of(
                "minecraft:oak_planks", "#minecraft:logs", " minecraft:stone "));
        assertEquals(List.of("minecraft:oak_planks", "minecraft:stone"), m.getBlockIds());
        assertEquals(List.of("minecraft:logs"), m.getTagIds());
        assertTrue(m.getMalformed().isEmpty());
        assertFalse(m.isEmpty());
    }

    @Test
    void malformedFormsAreCollectedNotFatal() {
        FrameMatcher m = FrameMatcher.of(List.of(
                "minecraft:Bad Caps", "#not a tag", "minecraft:ok"));
        assertEquals(List.of("minecraft:ok"), m.getBlockIds());
        assertEquals(List.of("minecraft:Bad Caps", "#not a tag"), m.getMalformed());
        assertFalse(m.isEmpty());
    }

    @Test
    void emptyAndNullFormsYieldEmptyMatcher() {
        assertTrue(FrameMatcher.of(List.of()).isEmpty());
        assertTrue(FrameMatcher.of(null).isEmpty());
        assertTrue(FrameMatcher.of(List.of("  ", "not an id at all!")).isEmpty());
    }

    @Test
    void acceptsBlockIdPlainPathIsRegistryFree() {
        // No tags configured: the plain-id comparison must answer without
        // touching the block registry (unit tests have no bootstrap).
        FrameMatcher m = FrameMatcher.of(List.of("minecraft:oak_planks", "minecraft:stone"));
        assertTrue(m.acceptsBlockId("minecraft:stone"));
        assertFalse(m.acceptsBlockId("minecraft:dirt"));
        assertFalse(m.acceptsBlockId(null));
    }
}
