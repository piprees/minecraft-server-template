package com.customdimensions.core.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shipped definition in {@code config/custom-dimensions/dimensions/}
 * loads through the core loader: none is skipped as invalid, and each one's
 * portals and the cross-dimension validation run without throwing. Warnings
 * are allowed. The {@code <slug>_thumb.json} files beside them are roller
 * thumbnail records, not definitions.
 */
class ShippedDefinitionsLoadTest {

    private static final Path CONFIG_ROOT = Path.of("..", "..", "..", "config", "custom-dimensions");
    private static final int SHIPPED_DEFINITIONS = 82;

    private static Set<String> definitionSlugs() throws IOException {
        Set<String> slugs = new TreeSet<>();
        try (Stream<Path> files = Files.list(CONFIG_ROOT.resolve("dimensions"))) {
            files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && !name.endsWith("_thumb.json"))
                    .forEach(name -> slugs.add(name.substring(0, name.length() - ".json".length())));
        }
        return slugs;
    }

    @Test
    void everyShippedDefinitionLoads() throws IOException {
        Set<String> slugs = definitionSlugs();
        assertEquals(SHIPPED_DEFINITIONS, slugs.size(), "shipped definition count");

        DimensionConfigLoader.LoadResult result =
                DimensionConfigLoader.loadAllWithSettings(CONFIG_ROOT, CONFIG_ROOT.resolve("overlay"));
        assertEquals(slugs, result.dimensions().keySet(),
                "a definition the loader skipped is invalid; the log names it");

        for (DimensionConfig config : result.dimensions().values()) {
            assertDoesNotThrow(config::toPortalDefinitions, config.getName());
            assertDoesNotThrow(config::getDimensionIdentifier, config.getName());
        }
        assertDoesNotThrow(() -> PortalSafetyValidator.validate(result.dimensions().values()));
        assertDoesNotThrow(() -> PortalSafetyValidator.frameCollisions(result.dimensions().values()));

        MultiverseConfig multiverse = new MultiverseConfig();
        assertDoesNotThrow(() -> multiverse.applyLoadResult(result));
        assertEquals(SHIPPED_DEFINITIONS, multiverse.getAllDimensions().size());
        assertTrue(multiverse.getPortals().size() >= 1);
    }
}
