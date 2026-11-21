package com.customdimensions.server.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.portal.Aura;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The aura's data against the extracted 26.3 registries: the protected tag, tree features and definition trees. */
class AuraDataTest {
    private static final Path VANILLA = Path.of(System.getProperty("customdimensions.vanilla"));
    private static final Path DEFINITIONS = Path.of(System.getProperty("customdimensions.definitions"));

    private static Set<String> blocks() throws IOException {
        JsonObject report = JsonParser.parseString(Files.readString(VANILLA.resolve("generated/reports/blocks.json")))
                .getAsJsonObject();
        return new HashSet<>(report.keySet());
    }

    private static boolean featureExists(String id) {
        String[] parts = id.split(":", 2);
        return Files.isRegularFile(VANILLA.resolve("data").resolve(parts[0]).resolve("worldgen/feature")
                .resolve(parts[1] + ".json"));
    }

    private static JsonObject tag() throws IOException {
        try (InputStream in = AuraDataTest.class.getResourceAsStream("/data/adventure/tags/block/aura_protected.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void everyProtectedBlockExistsIn26_3() throws IOException {
        Set<String> blocks = blocks();
        List<String> missing = new ArrayList<>();
        int plain = 0;
        for (JsonElement value : tag().getAsJsonArray("values")) {
            String id = value.isJsonPrimitive() ? value.getAsString() : value.getAsJsonObject().get("id").getAsString();
            if (id.startsWith("#")) {
                continue;
            }
            plain++;
            if (!blocks.contains(id)) {
                missing.add(id);
            }
        }
        assertEquals(List.of(), missing);
        assertTrue(plain > 400, "tag lists " + plain + " blocks");
    }

    @Test
    void theTagCoversTheCraftedBlocksAddedSince1_21_1() throws IOException {
        Set<String> listed = new HashSet<>();
        tag().getAsJsonArray("values").forEach(v -> listed.add(v.isJsonPrimitive() ? v.getAsString()
                : v.getAsJsonObject().get("id").getAsString()));
        for (String id : List.of("minecraft:oak_shelf", "minecraft:pale_oak_shelf", "minecraft:copper_chest",
                "minecraft:waxed_oxidized_copper_golem_statue", "minecraft:copper_bulb", "minecraft:copper_grate",
                "minecraft:copper_door", "minecraft:weathered_copper_trapdoor", "minecraft:copper_lantern",
                "minecraft:copper_chain", "minecraft:copper_bars", "minecraft:lightning_rod", "minecraft:resin_bricks",
                "minecraft:stripped_pale_oak_log", "minecraft:poplar_wood", "minecraft:cinnabar_bricks",
                "minecraft:sulfur_bricks", "minecraft:cobblestone")) {
            assertTrue(listed.contains(id), id);
        }
        for (String natural : List.of("minecraft:shelf_mushroom", "minecraft:sulfur", "minecraft:cinnabar",
                "minecraft:pale_oak_log", "minecraft:resin_clump")) {
            assertTrue(!listed.contains(natural), natural);
        }
    }

    @Test
    void everyLogMapsToARealTreeFeature() throws IOException {
        Set<String> blocks = blocks();
        Aura.LOG_TO_TREE.forEach((log, tree) -> {
            assertTrue(blocks.contains(log), log);
            assertTrue(featureExists(tree), tree);
        });
    }

    @Test
    void everyConfiguredAuraTreeIsARealFeature() throws IOException {
        List<String> missing = new ArrayList<>();
        int trees = 0;
        try (Stream<Path> files = Files.list(DEFINITIONS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonElement root = JsonParser.parseString(Files.readString(file));
                if (!root.isJsonObject() || !root.getAsJsonObject().has("portals")) {
                    continue;
                }
                for (JsonElement portal : root.getAsJsonObject().getAsJsonArray("portals")) {
                    JsonObject aura = portal.getAsJsonObject().getAsJsonObject("aura");
                    if (aura == null || !aura.has("trees")) {
                        continue;
                    }
                    for (JsonElement tree : aura.getAsJsonArray("trees")) {
                        trees++;
                        if (!featureExists(tree.getAsString())) {
                            missing.add(file.getFileName() + ": " + tree.getAsString());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
        assertTrue(trees > 0);
    }
}
