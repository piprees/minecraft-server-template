package com.customdimensions.core.firewall;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bridges in {@code bridges/} against {@code schema/bridge.schema.json} and the reader,
 * and against what the firewall switches off or removes on the 26.3 list without them
 * ({@code firewall/unbridged-26.3.txt}).
 */
class ShippedBridgesTest {

    private static final Path BRIDGES = Path.of("../bridges");
    private static final Set<String> OWN = Set.of("minecraft", "adventure", "customdimensions", "fantasy");

    private static Schema schema;
    private static Map<String, Set<String>> unbridged;

    @BeforeAll
    static void load() throws IOException {
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("../schema/bridge.schema.json")), InputFormat.JSON);
        unbridged = new TreeMap<>();
        try (InputStream in = ShippedBridgesTest.class.getResourceAsStream("/firewall/unbridged-26.3.txt")) {
            assertNotNull(in, "firewall/unbridged-26.3.txt");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.trim().split(" ");
                assertEquals(2, parts.length, line);
                unbridged.computeIfAbsent(parts[0], k -> new TreeSet<>()).add(parts[1]);
            }
        }
    }

    private static List<Path> files() throws IOException {
        try (Stream<Path> files = Files.list(BRIDGES)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Set<String> union(Map<String, Bridge> bridges, Function<Bridge, List<String>> field) {
        Set<String> out = new TreeSet<>();
        bridges.values().stream().filter(Bridge::reviewed).forEach(b -> out.addAll(field.apply(b)));
        return out;
    }

    @Test
    void everyBridgeValidatesLoadsAndIsReviewed() throws IOException {
        List<Path> files = files();
        assertEquals(12, files.size(), "bridges in bridges/");
        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file);
            schema.validate(text, InputFormat.JSON).forEach(e -> failures.add(file.getFileName() + ": " + e));
            Bridge bridge = BridgeReader.read(file);
            assertTrue(bridge.reviewed(), file.toString());
            assertEquals(text, BridgeReader.write(bridge), file + " is not in the writer's form");
            assertTrue(bridge.mixins().isEmpty() && bridge.registries().isEmpty(), file + " adopts mixins or registries");
            for (Bridge.Structure s : bridge.structures()) {
                assertTrue(s.set() == null || bridge.structureSets().contains(s.set()), file + ": " + s.id() + " names a set the bridge does not adopt");
            }
        }
        assertEquals(List.of(), failures);
        assertEquals(files.size(), BridgeReader.readAll(BRIDGES).size());
    }

    @Test
    void theBridgesCoverEverythingTheFirewallTakesFromTheList() {
        Map<String, Bridge> bridges = BridgeReader.readAll(BRIDGES);
        assertEquals(Set.of("feature", "spawn", "structure_set"), unbridged.keySet());
        assertEquals(271, unbridged.get("structure_set").size());
        assertEquals(Set.of(), Firewall.switchedOff(unbridged.get("structure_set"), bridges, OWN));
        Set<String> features = new TreeSet<>(unbridged.get("feature"));
        features.removeAll(union(bridges, Bridge::features));
        assertEquals(Set.of(), features, "removed features no bridge adopts");
        Set<String> spawns = new TreeSet<>(unbridged.get("spawn"));
        spawns.removeAll(union(bridges, Bridge::spawns));
        assertEquals(Set.of(), spawns, "removed spawns no bridge adopts");
    }

    @Test
    void withoutABridgeItsSetsAreSwitchedOffAgain() {
        Map<String, Bridge> bridges = new HashMap<>(BridgeReader.readAll(BRIDGES));
        Bridge mvs = bridges.remove("mvs");
        Set<String> off = Firewall.switchedOff(unbridged.get("structure_set"), bridges, OWN);
        assertEquals(116, off.size());
        assertTrue(off.stream().allMatch(id -> id.startsWith("mvs:")), off.toString());
        bridges.put("mvs", new Bridge("mvs", false, mvs.structureSets(), mvs.structures(), mvs.features(), mvs.spawns(), mvs.mixins(), mvs.registries()));
        assertEquals(off, Firewall.switchedOff(unbridged.get("structure_set"), bridges, OWN), "an unreviewed bridge adopts nothing");
    }

    @Test
    void aBridgeForAModThatIsNotInstalledChangesNothing() {
        Map<String, Bridge> bridges = BridgeReader.readAll(BRIDGES);
        Set<String> vanillaOnly = Set.of("minecraft:villages", "minecraft:strongholds");
        assertEquals(Set.of(), Firewall.switchedOff(vanillaOnly, bridges, OWN));
        Set<String> foreign = Set.of("minecraft:villages", "other_mod:tower");
        assertEquals(Set.of("other_mod:tower"), Firewall.switchedOff(foreign, bridges, OWN));
        assertFalse(union(bridges, Bridge::structureSets).contains("other_mod:tower"));
    }
}
