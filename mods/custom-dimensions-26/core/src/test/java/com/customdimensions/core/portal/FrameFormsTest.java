package com.customdimensions.core.portal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registry-free half of FrameForms: form parsing/classification and the
 * plain-id fast path. Block/tag RESOLUTION needs a live registry and is
 * covered by the bot recipes in the local verification loop.
 */
class FrameFormsTest {

    @Test
    void classifiesPlainIdsAndTags() {
        FrameForms m = FrameForms.of(List.of(
                "minecraft:oak_planks", "#minecraft:logs", " minecraft:stone "));
        assertEquals(List.of("minecraft:oak_planks", "minecraft:stone"), m.getBlockIds());
        assertEquals(List.of("minecraft:logs"), m.getTagIds());
        assertTrue(m.getMalformed().isEmpty());
        assertFalse(m.isEmpty());
    }

    @Test
    void malformedFormsAreCollectedNotFatal() {
        FrameForms m = FrameForms.of(List.of(
                "minecraft:Bad Caps", "#not a tag", "minecraft:ok"));
        assertEquals(List.of("minecraft:ok"), m.getBlockIds());
        assertEquals(List.of("minecraft:Bad Caps", "#not a tag"), m.getMalformed());
        assertFalse(m.isEmpty());
    }

    @Test
    void emptyAndNullFormsYieldEmptyMatcher() {
        assertTrue(FrameForms.of(List.of()).isEmpty());
        assertTrue(FrameForms.of(null).isEmpty());
        assertTrue(FrameForms.of(List.of("  ", "not an id at all!")).isEmpty());
    }

    @Test
    void acceptsBlockIdPlainPathIsRegistryFree() {
        FrameForms m = FrameForms.of(List.of("minecraft:oak_planks", "minecraft:stone"));
        assertTrue(m.acceptsBlockId("minecraft:stone"));
        assertFalse(m.acceptsBlockId("minecraft:dirt"));
        assertFalse(m.acceptsBlockId(null));
    }

    @Test
    void tagMembershipComesFromTheSuppliedLookup() {
        FrameForms m = FrameForms.of(List.of("minecraft:stone", "#minecraft:logs"));
        FrameForms.TagMembership logs = (block, tag) ->
                tag.equals("minecraft:logs") && block.endsWith("_log");
        assertTrue(m.acceptsBlockId("minecraft:oak_log", logs));
        assertFalse(m.acceptsBlockId("minecraft:dirt", logs));
        assertFalse(m.acceptsBlockId("Not An Id", (block, tag) -> true));
        assertFalse(m.acceptsBlockId("minecraft:oak_log", FrameForms.NO_TAGS));
    }

    @Test
    void theInstalledLookupAnswersTheOneArgumentForm() {
        FrameForms m = FrameForms.of(List.of("#minecraft:logs"));
        try {
            assertFalse(m.acceptsBlockId("minecraft:oak_log"));
            FrameForms.setTagMembership((block, tag) -> block.equals("minecraft:oak_log"));
            assertTrue(m.acceptsBlockId("minecraft:oak_log"));
        } finally {
            FrameForms.setTagMembership(null);
        }
        assertFalse(m.acceptsBlockId("minecraft:oak_log"));
    }

    @Test
    void aCoveredFrameBlockKeepsStandingAfterItDecays() {
        FrameForms nylium = FrameForms.of(List.of("minecraft:warped_nylium"));
        assertFalse(nylium.acceptsBlockId("minecraft:netherrack"));
        assertTrue(nylium.keepsStanding("minecraft:netherrack"));
        assertTrue(nylium.keepsStanding("minecraft:warped_nylium"));
        assertFalse(nylium.keepsStanding("minecraft:dirt"));
        assertTrue(FrameForms.of(List.of("minecraft:grass_block")).keepsStanding("minecraft:dirt"));
        assertFalse(FrameForms.of(List.of("minecraft:stone")).keepsStanding("minecraft:netherrack"));
    }
}
