package com.customdimensions.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "portal" as one object or an array of them: both normalise to a list in
 * config order, and the first entry is the dimension's primary portal.
 */
class MultiPortalConfigTest {
    private static final Gson GSON = new Gson();

    /** The real dimension configs, relative to this Gradle project. */
    private static final Path SHIPPED_DIMENSIONS =
            Path.of("../../config/custom-dimensions/dimensions");

    private DimensionConfig parse(String slug, String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName(slug);
        return config;
    }

    private MultiverseConfig fromDirectory(Path dir) {
        MultiverseConfig config = new MultiverseConfig();
        config.applyLoadResult(DimensionConfigLoader.loadAllWithSettings(dir, dir.resolve("overlay")));
        return config;
    }

    // --- normalisation --------------------------------------------------------

    @Test
    void objectFormParsesToOnePortal() {
        DimensionConfig config = parse("the_test", """
                {"portal":{"frameBlock":"minecraft:copper_block","igniterItem":"minecraft:diamond","scale":4.0}}
                """);
        assertEquals(1, config.getPortals().size());
        assertTrue(config.hasPortal());
        assertEquals("minecraft:copper_block", config.getPortal().getFrameBlockId());
        assertEquals(4.0, config.getScale());

        List<PortalDefinition> defs = config.toPortalDefinitions();
        assertEquals(1, defs.size());
        assertEquals("the_test", defs.get(0).getId());
        assertEquals("the_test", config.toPortalDefinition().getId());
    }

    @Test
    void arrayFormParsesToOneDefinitionPerEntry() {
        DimensionConfig config = parse("the_test", """
                {"portal":[
                  {"frameBlock":"minecraft:copper_block","igniterItem":"minecraft:diamond","scale":4.0},
                  {"frameBlock":"minecraft:mud_bricks","igniterItem":"minecraft:stick","scale":4.0},
                  {"frameBlock":"minecraft:bone_block","igniterItem":"minecraft:bone","scale":4.0}
                ]}
                """);
        assertEquals(3, config.getPortals().size());
        List<PortalDefinition> defs = config.toPortalDefinitions();
        assertEquals(3, defs.size());
        assertEquals(List.of("the_test", "the_test#2", "the_test#3"),
                defs.stream().map(PortalDefinition::getId).toList());
        assertEquals(List.of("minecraft:copper_block", "minecraft:mud_bricks", "minecraft:bone_block"),
                defs.stream().map(PortalDefinition::getFrameBlock).toList());
        assertEquals(List.of("minecraft:diamond", "minecraft:stick", "minecraft:bone"),
                defs.stream().map(PortalDefinition::getIgniterItem).toList());
    }

    @Test
    void oneElementArrayIsIdenticalToTheObjectForm() {
        PortalDefinition object = parse("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:copper_block\",\"igniterItem\":\"minecraft:diamond\"}}")
                .toPortalDefinition();
        PortalDefinition array = parse("the_test",
                "{\"portal\":[{\"frameBlock\":\"minecraft:copper_block\",\"igniterItem\":\"minecraft:diamond\"}]}")
                .toPortalDefinition();
        assertEquals(object.getId(), array.getId());
        assertEquals(object.getFrameBlock(), array.getFrameBlock());
        assertEquals(object.getIgniterItem(), array.getIgniterItem());
        assertEquals(GSON.toJson(object), GSON.toJson(array));
    }

    @Test
    void absentAndEmptyPortalFieldsYieldNoPortal() {
        for (String json : List.of("{}", "{\"portal\":null}", "{\"portal\":[]}")) {
            DimensionConfig config = parse("the_test", json);
            assertFalse(config.hasPortal(), json);
            assertNull(config.getPortal(), json);
            assertNull(config.toPortalDefinition(), json);
            assertTrue(config.toPortalDefinitions().isEmpty(), json);
            assertEquals(1.0, config.getScale(), json);
        }
    }

    @Test
    void singlePortalRoundTripsBackAsAnObject() {
        DimensionConfig one = parse("the_test",
                "{\"portal\":{\"frameBlock\":\"minecraft:copper_block\"}}");
        DimensionConfig many = parse("the_test",
                "{\"portal\":[{\"frameBlock\":\"a\"},{\"frameBlock\":\"b\"}]}");
        assertTrue(GSON.toJsonTree(one).getAsJsonObject().get("portal").isJsonObject());
        assertTrue(GSON.toJsonTree(many).getAsJsonObject().get("portal").isJsonArray());

        // TryOut clones a config through Gson; the clone must survive both shapes.
        DimensionConfig clone = GSON.fromJson(GSON.toJsonTree(many), DimensionConfig.class);
        clone.setName("the_test");
        assertEquals(2, clone.getPortals().size());
        assertEquals(List.of("the_test", "the_test#2"),
                clone.toPortalDefinitions().stream().map(PortalDefinition::getId).toList());
    }

    // --- identity and precedence ---------------------------------------------

    @Test
    void idsFollowConfigPositionEvenWhenAnEntryIsNotIgnitable() {
        // An entry with no frame and no gateway shape can never ignite; it is
        // skipped, and the entries after it keep their positional ids.
        DimensionConfig config = parse("the_test", """
                {"portal":[
                  {"igniterItem":"minecraft:diamond"},
                  {"frameBlock":"minecraft:mud_bricks","igniterItem":"minecraft:stick"}
                ]}
                """);
        assertTrue(config.hasPortal());
        List<PortalDefinition> defs = config.toPortalDefinitions();
        assertEquals(1, defs.size());
        assertEquals("the_test#2", defs.get(0).getId());
    }

    @Test
    void primaryPortalGovernsScaleAndTheRawPortalBlock() {
        DimensionConfig config = parse("the_test", """
                {"portal":[
                  {"frameBlock":"minecraft:copper_block","scale":4.0,"cooldown":80},
                  {"frameBlock":"minecraft:mud_bricks","scale":2.0}
                ]}
                """);
        assertEquals(4.0, config.getScale());
        assertEquals("minecraft:copper_block", config.getPortal().getFrameBlockId());
        // Each definition still carries its own scale — the value the
        // coordinate transform uses when a player enters through THAT portal.
        assertEquals(4.0, config.toPortalDefinitions().get(0).getScale());
        assertEquals(2.0, config.toPortalDefinitions().get(1).getScale());
    }

    @Test
    void sharedIgniterAcrossOneDimensionKeepsConfigOrderAndPrefersTheClickedFrame(@TempDir Path dir)
            throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve("the_test.json"), """
                {"portal":[
                  {"frameBlock":"minecraft:copper_block","igniterItem":"minecraft:diamond"},
                  {"frameBlock":"minecraft:mud_bricks","igniterItem":"minecraft:diamond"},
                  {"frameBlock":"minecraft:copper_block","igniterItem":"minecraft:diamond"}
                ]}
                """);
        MultiverseConfig config = fromDirectory(dir);
        assertEquals(3, config.getPortals().size());

        // No clicked block: pure config order.
        assertEquals(List.of("the_test", "the_test#2", "the_test#3"),
                config.getPortalsByIgniter("minecraft:diamond", null).stream()
                        .map(PortalDefinition::getId).toList());

        // Clicked mud_bricks: the only frame match leads, the rest keep order.
        assertEquals(List.of("the_test#2", "the_test", "the_test#3"),
                config.getPortalsByIgniter("minecraft:diamond", "minecraft:mud_bricks").stream()
                        .map(PortalDefinition::getId).toList());

        // Two entries share the frame AND the igniter: config order decides.
        assertEquals("the_test",
                config.getPortalsByIgniter("minecraft:diamond", "minecraft:copper_block")
                        .get(0).getId());
    }

    @Test
    void targetKeyedLookupsResolveToThePrimaryPortal(@TempDir Path dir) throws IOException {
        Path dims = dir.resolve("dimensions");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve("the_test.json"), """
                {"portal":[
                  {"frameBlock":"minecraft:copper_block","color":"8BAF5B"},
                  {"frameBlock":"minecraft:mud_bricks","color":"FF0000"}
                ]}
                """);
        MultiverseConfig config = fromDirectory(dir);
        PortalDefinition primary = config.getPortals().get(0);
        assertEquals("the_test", config.getPortalFor(primary.getTargetKey()).getId());
        assertEquals("the_test", config.getPortal("the_test").getId());
        assertEquals("the_test#2", config.getPortal("the_test#2").getId());
    }

    // --- the shipped configs --------------------------------------------------

    @Test
    void aShippedSinglePortalConfigParsesExactlyAsBefore() throws IOException {
        Path file = SHIPPED_DIMENSIONS.resolve("the_crucible.json");
        assertTrue(Files.exists(file), "shipped config missing: " + file.toAbsolutePath());
        String raw = Files.readString(file);
        JsonObject portal = JsonParser.parseString(raw).getAsJsonObject()
                .getAsJsonObject("portal");

        DimensionConfig config = parse("the_crucible", raw);
        assertEquals(1, config.getPortals().size());
        assertEquals(portal.get("scale").getAsDouble(), config.getScale());

        List<PortalDefinition> defs = config.toPortalDefinitions();
        assertEquals(1, defs.size());
        PortalDefinition def = defs.get(0);
        assertEquals("the_crucible", def.getId());
        assertEquals(portal.get("frameBlock").getAsString(), def.getFrameBlock());
        assertEquals(portal.get("igniterItem").getAsString(), def.getIgniterItem());
        assertEquals(portal.get("color").getAsString(), def.getColor());
        assertEquals(portal.get("lightLevel").getAsInt(), def.getLightLevel());
        assertEquals(portal.get("cooldown").getAsInt(), def.getCooldown());
        assertEquals(portal.get("scale").getAsDouble(), def.getScale());
        // The object form and the no-arg accessor still agree.
        assertEquals(GSON.toJson(def), GSON.toJson(config.toPortalDefinition()));
    }

    @Test
    void everyShippedConfigStillParsesWithExactlyOnePortalOrNone() throws IOException {
        assertTrue(Files.isDirectory(SHIPPED_DIMENSIONS),
                "shipped configs missing: " + SHIPPED_DIMENSIONS.toAbsolutePath());
        int withPortal = 0;
        try (var files = Files.list(SHIPPED_DIMENSIONS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String slug = file.getFileName().toString().replace(".json", "");
                DimensionConfig config = parse(slug, Files.readString(file));
                assertTrue(config.getPortals().size() <= 1, slug + " has more than one portal");
                for (PortalDefinition def : config.toPortalDefinitions()) {
                    assertEquals(slug, def.getId(), slug + " definition id drifted from its slug");
                    withPortal++;
                }
            }
        }
        assertTrue(withPortal > 0, "no shipped dimension config declares a portal");
    }
}
