package com.customdimensions.server.structure;

import com.customdimensions.core.firewall.Bridge;
import com.customdimensions.core.firewall.BridgeReader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureSizesFileTest {

    private static JsonObject sizes() throws Exception {
        var in = StructureSizesFileTest.class.getResourceAsStream("/structure-sizes.json");
        assertNotNull(in, "structure-sizes.json is in the mod's resources");
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Test
    void everyVanillaStructureHasASize() throws Exception {
        JsonObject sizes = sizes();
        Path dir = Path.of(System.getProperty("customdimensions.vanilla"), "data/minecraft/worldgen/structure");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String id = "minecraft:" + f.getFileName().toString().replace(".json", "");
                if (!sizes.has(id)) missing.add(id);
            }
        }
        assertTrue(missing.isEmpty(), "no size for " + missing);
    }

    @Test
    void everyBridgedStructureMatchesTheFile() throws Exception {
        JsonObject sizes = sizes();
        List<String> mismatched = new ArrayList<>();
        for (Bridge bridge : BridgeReader.readAll(Path.of("../bridges")).values()) {
            for (Bridge.Structure s : bridge.structures()) {
                JsonObject m = sizes.getAsJsonObject(s.id());
                if (m == null || !Integer.valueOf(m.get("x").getAsInt()).equals(s.size().x())
                        || !Integer.valueOf(m.get("z").getAsInt()).equals(s.size().z())) {
                    mismatched.add(s.id());
                }
            }
        }
        assertTrue(mismatched.isEmpty(), "bridge sizes differ from structure-sizes.json for " + mismatched);
    }

    @Test
    void everySizeIsPositive() throws Exception {
        sizes().entrySet().forEach(e -> {
            JsonObject s = e.getValue().getAsJsonObject();
            assertTrue(s.get("x").getAsInt() > 0 && s.get("y").getAsInt() > 0 && s.get("z").getAsInt() > 0, e.getKey());
        });
    }
}
