package com.customdimensions.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "consumesIgniter" is how a dimension asks for eye-of-ender semantics: the
 * item itself, not a point of durability. It is opt-in because vanilla does
 * not consume an igniter, and nothing shipped opts in.
 */
class ConsumesIgniterConfigTest {

    private static final Gson GSON = new Gson();

    /** The real dimension configs, relative to this Gradle project. */
    private static final Path SHIPPED_DIMENSIONS =
            Path.of("../../config/custom-dimensions/dimensions");

    private DimensionConfig parse(String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName("the_test");
        return config;
    }

    private PortalDefinition portal(String portalJson) {
        return parse("{\"portal\":" + portalJson + "}").toPortalDefinition();
    }

    @Test
    void anIgniterSurvivesUnlessTheDimensionAsksForIt() {
        assertFalse(portal("{\"frameBlock\":\"minecraft:stone\","
                + "\"igniterItem\":\"minecraft:flint_and_steel\"}").consumesIgniter(),
                "absent must mean no: vanilla damages an igniter, it does not eat one");
    }

    @Test
    void aDimensionCanAskForTheItemItself() {
        assertTrue(portal("{\"frameBlock\":\"minecraft:stone\","
                + "\"igniterItem\":\"minecraft:ender_eye\",\"consumesIgniter\":true}")
                .consumesIgniter());
    }

    @Test
    void anExplicitFalseReadsAsFalse() {
        assertFalse(portal("{\"frameBlock\":\"minecraft:stone\","
                + "\"igniterItem\":\"minecraft:ender_eye\",\"consumesIgniter\":false}")
                .consumesIgniter());
    }

    @Test
    void aSecondPortalKeepsItsOwnAnswer() {
        // getPortalsByIgniter hands every matching definition to ignition, so
        // one entry's opt-in must not spend the next entry's igniter.
        List<PortalDefinition> defs = parse("{\"portal\":["
                + "{\"frameBlock\":\"minecraft:stone\",\"igniterItem\":\"minecraft:ender_eye\","
                + "\"consumesIgniter\":true},"
                + "{\"frameBlock\":\"minecraft:copper_block\","
                + "\"igniterItem\":\"minecraft:flint_and_steel\"}]}")
                .toPortalDefinitions();

        assertEquals(2, defs.size());
        assertTrue(defs.get(0).consumesIgniter());
        assertFalse(defs.get(1).consumesIgniter());
    }

    @Test
    void noShippedDimensionConsumesItsIgniter() throws IOException {
        // The ruling is that consumption is opt-in and nothing has opted in.
        // The denominator is asserted because a scan of zero files reads
        // exactly like a pass (TROUBLESHOOTING.md#t63).
        List<String> opted = new ArrayList<>();
        int scanned = 0;
        int withPortal = 0;
        try (Stream<Path> files = Files.list(SHIPPED_DIMENSIONS)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".json") || name.endsWith("_thumb.json")) {
                    continue;
                }
                scanned++;
                DimensionConfig config = GSON.fromJson(Files.readString(file), DimensionConfig.class);
                config.setName(name.substring(0, name.length() - ".json".length()));
                for (DimensionConfig.Portal entry : config.getPortals()) {
                    withPortal++;
                    if (entry.consumesIgniter()) {
                        opted.add(config.getName());
                    }
                }
            }
        }

        assertTrue(scanned > 60, "expected the shipped dimension set, scanned " + scanned);
        assertTrue(withPortal >= scanned,
                "expected at least one portal per dimension, found " + withPortal
                + " across " + scanned + " configs");
        assertEquals(List.of(), opted,
                "consumption is opt-in and nothing ships opted in; these do: " + opted);
    }
}
