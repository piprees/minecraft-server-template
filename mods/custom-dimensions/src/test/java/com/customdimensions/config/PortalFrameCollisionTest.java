package com.customdimensions.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortalFrameCollisionTest {
    private static final Gson GSON = new Gson();

    private DimensionConfig parse(String slug, String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName(slug);
        return config;
    }

    private List<PortalSafetyValidator.FrameCollision> collisions(DimensionConfig... configs) {
        return PortalSafetyValidator.frameCollisions(List.of(configs));
    }

    @Test
    void sameFrameAndSameIgniterIsAnError() {
        DimensionConfig a = parse("the_first", """
                {"portal":{"frameBlock":"minecraft:obsidian","igniterItem":"minecraft:diamond"}}
                """);
        DimensionConfig b = parse("the_second", """
                {"portal":{"frameBlock":"minecraft:obsidian","igniterItem":"minecraft:diamond"}}
                """);
        List<PortalSafetyValidator.FrameCollision> found = collisions(a, b);
        assertEquals(1, found.size(), found.toString());
        assertEquals(PortalSafetyValidator.ERROR, found.get(0).severity());
        assertEquals("portal_igniter_collision", found.get(0).check());
        assertEquals("the_second", found.get(0).dimension());
        assertTrue(found.get(0).message().contains("the_first"));
        assertTrue(found.get(0).message().contains("nothing distinguishes"));
    }

    @Test
    void twoIgniterlessDefinitionsOnOneFrameAreAlsoAnError() {
        // Absent igniters match each other: neither ignition nor adoption has
        // anything left to tell the two apart.
        DimensionConfig a = parse("d1", "{\"portal\":{\"frameBlock\":\"minecraft:stone\"}}");
        DimensionConfig b = parse("d2", "{\"portal\":{\"frameBlock\":\"minecraft:stone\"}}");
        List<PortalSafetyValidator.FrameCollision> found = collisions(a, b);
        assertEquals(1, found.size(), found.toString());
        assertEquals(PortalSafetyValidator.ERROR, found.get(0).severity());
        assertTrue(found.get(0).subject().contains("(none)"));
    }

    @Test
    void sharedFrameWithDifferentIgnitersIsAnErrorAndNamesTheWinner() {
        DimensionConfig first = parse("the_crystal_vale", """
                {"portal":{"frameBlock":"minecraft:amethyst_block",
                 "igniterItem":"minecraft:amethyst_shard"}}
                """);
        DimensionConfig second = parse("the_violet_spire", """
                {"portal":{"frameBlock":"minecraft:amethyst_block",
                 "igniterItem":"minecraft:ender_eye"}}
                """);
        List<PortalSafetyValidator.FrameCollision> found = collisions(first, second);
        assertEquals(1, found.size(), found.toString());
        assertEquals(PortalSafetyValidator.ERROR, found.get(0).severity());
        assertEquals("portal_frame_shared", found.get(0).check());
        assertEquals("the_violet_spire", found.get(0).dimension());
        assertTrue(found.get(0).message().contains("adopted by the_crystal_vale"));
        assertTrue(found.get(0).message().contains("first in config order"));
    }

    @Test
    void aVanillaManagedSideReservesTheFrame() {
        DimensionConfig nether = parse("the_nether", """
                {"portal":{"frameBlock":"minecraft:obsidian","vanillaManaged":true,"scale":8.0}}
                """);
        DimensionConfig sanctum = parse("the_obsidian_sanctum", """
                {"portal":{"frameBlock":"minecraft:obsidian",
                 "igniterItem":"minecraft:netherite_ingot"}}
                """);
        List<PortalSafetyValidator.FrameCollision> found = collisions(nether, sanctum);
        assertEquals(1, found.size(), found.toString());
        assertEquals(PortalSafetyValidator.ERROR, found.get(0).severity());
        assertEquals("portal_frame_reserved", found.get(0).check());
        assertEquals("the_obsidian_sanctum", found.get(0).dimension());
        assertTrue(found.get(0).message().contains("vanillaManaged"));
        assertTrue(found.get(0).message().contains("never by adopting"));
    }

    @Test
    void twoEntriesOfOneDimensionCollideLikeTwoDimensions() {
        // Portal ids are positional, so the second entry is named "slug#2".
        DimensionConfig twoDoors = parse("the_twin_gate", """
                {"portal":[{"frameBlock":"minecraft:quartz_block","igniterItem":"minecraft:torch"},
                           {"frameBlock":"minecraft:quartz_block","igniterItem":"minecraft:torch"}]}
                """);
        List<PortalSafetyValidator.FrameCollision> found = collisions(twoDoors);
        assertEquals(1, found.size(), found.toString());
        assertEquals(PortalSafetyValidator.ERROR, found.get(0).severity());
        assertTrue(found.get(0).message().contains("the_twin_gate"));
    }

    @Test
    void distinctFramesAndListFormsAreSilent() {
        DimensionConfig a = parse("d1", """
                {"portal":{"frameBlock":["minecraft:stone","minecraft:andesite"],
                 "igniterItem":"minecraft:torch"}}
                """);
        DimensionConfig b = parse("d2", """
                {"portal":{"frameBlock":["minecraft:diorite","minecraft:granite"],
                 "igniterItem":"minecraft:torch"}}
                """);
        assertTrue(collisions(a, b).isEmpty());
    }

    @Test
    void oneSharedFormInsideTwoListsStillCollides() {
        DimensionConfig a = parse("d1", """
                {"portal":{"frameBlock":["minecraft:stone","minecraft:andesite"],
                 "igniterItem":"minecraft:torch"}}
                """);
        DimensionConfig b = parse("d2", """
                {"portal":{"frameBlock":["minecraft:andesite","minecraft:granite"],
                 "igniterItem":"minecraft:bone"}}
                """);
        List<PortalSafetyValidator.FrameCollision> found = collisions(a, b);
        assertEquals(1, found.size(), found.toString());
        assertEquals("minecraft:andesite", found.get(0).subject());
    }

    /**
     * The shipped set is the acceptance test: a frame block belongs to exactly
     * one portal definition, so any finding here is a config to fix.
     */
    @Test
    void shippedDimensionsShareNoFrameBlock() throws IOException {
        Path dir = Path.of("../../config/custom-dimensions/dimensions");
        assertTrue(Files.isDirectory(dir), "shipped dimension configs not found at " + dir);
        List<DimensionConfig> configs = new ArrayList<>();
        try (var files = Files.list(dir)) {
            // Each dimension also ships a <slug>_thumb.json of seed-roller
            // render metadata, which carries no portal and is not a config.
            for (Path file : files
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.toString().endsWith("_thumb.json"))
                    .sorted().toList()) {
                String slug = file.getFileName().toString().replace(".json", "");
                configs.add(parse(slug, Files.readString(file)));
            }
        }
        assertEquals(82, configs.size(), "expected 82 shipped dimension configs");

        List<PortalSafetyValidator.FrameCollision> found =
                PortalSafetyValidator.frameCollisions(configs);
        assertTrue(found.isEmpty(), "shipped configs must lint clean: " + found);
    }
}
