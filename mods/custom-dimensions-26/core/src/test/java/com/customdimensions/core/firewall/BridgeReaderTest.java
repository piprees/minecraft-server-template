package com.customdimensions.core.firewall;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeReaderTest {
    private static Schema schema;

    private static final String FULL = """
            {"mod": "mvs", "reviewed": true, "structureSets": ["mvs:barns"],
             "structures": [{"id": "mvs:barn", "set": "mvs:barns", "size": {"x": 21, "y": null, "z": 17},
                             "mood": ["pastoral"], "climate": ["temperate"], "depth": ["surface"]}],
             "features": ["farmersdelight:patch_wild_cabbages"], "spawns": ["clucking_ducks:duck"],
             "mixins": ["mvs.mixins.json"], "registries": ["mvs:sky"]}
            """;

    @BeforeAll
    static void loadSchema() throws IOException {
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("../schema/bridge.schema.json")), InputFormat.JSON);
    }

    @Test
    void readsEveryField() {
        Bridge b = BridgeReader.parse(FULL, null);
        assertEquals("mvs", b.mod());
        assertTrue(b.reviewed());
        assertEquals(List.of("mvs:barns"), b.structureSets());
        Bridge.Structure s = b.structures().getFirst();
        assertEquals(new Bridge.Size(21, null, 17), s.size());
        assertEquals(List.of("pastoral"), s.mood());
        assertEquals(List.of("farmersdelight:patch_wild_cabbages"), b.features());
        assertEquals(List.of("clucking_ducks:duck"), b.spawns());
        assertEquals(List.of("mvs.mixins.json"), b.mixins());
        assertEquals(List.of("mvs:sky"), b.registries());
        assertTrue(schema.validate(FULL, InputFormat.JSON).isEmpty());
    }

    @Test
    void minimalBridgeIsUnreviewedAndEmpty() {
        Bridge b = BridgeReader.parse("{\"mod\": \"ddd\"}", null);
        assertFalse(b.reviewed());
        assertTrue(b.structureSets().isEmpty() && b.structures().isEmpty() && b.features().isEmpty());
        assertEquals(Bridge.Size.UNMEASURED, BridgeReader.parse("{\"mod\":\"a\",\"structures\":[{\"id\":\"a:b\"}]}", null).structures().getFirst().size());
    }

    @Test
    void writeRoundTripsAndValidates() {
        Bridge b = BridgeReader.parse(FULL, null);
        String written = BridgeReader.write(b);
        assertEquals(b, BridgeReader.parse(written, null));
        assertTrue(schema.validate(written, InputFormat.JSON).isEmpty(), schema.validate(written, InputFormat.JSON).toString());
        assertTrue(written.contains("\"y\": null"), written);
    }

    @Test
    void badInputsNameTheProblem() {
        Map<String, String> cases = Map.of(
                "[]", "not a JSON object",
                "{", "not JSON",
                "{\"reviewed\": true}", "mod is missing",
                "{\"mod\": \"Bad Id\"}", "is not a mod id",
                "{\"mod\": \"a\", \"colour\": 1}", "unknown field colour",
                "{\"mod\": \"a\", \"structureSets\": [\"nocolon\"]}", "is not a namespaced id",
                "{\"mod\": \"a\", \"reviewed\": \"yes\"}", "reviewed is not true or false",
                "{\"mod\": \"a\", \"structures\": [{\"id\": \"a:b\", \"mood\": [\"grim\"]}]}", "mood \"grim\" is not one of",
                "{\"mod\": \"a\", \"structures\": [{\"id\": \"a:b\", \"size\": {\"x\": 0}}]}", "size.x is not a positive whole number",
                "{\"mod\": \"a\", \"features\": \"a:b\"}", "features is not an array");
        cases.forEach((text, expected) -> {
            BridgeReader.BridgeFormatException e = assertThrows(BridgeReader.BridgeFormatException.class, () -> BridgeReader.parse(text, Path.of("a.json")));
            assertTrue(e.getMessage().startsWith("a.json: "), e.getMessage());
            assertTrue(e.getMessage().contains(expected), text + " -> " + e.getMessage());
        });
    }

    @Test
    void readAllKeysByModAndChecksFileNames(@TempDir Path dir) throws IOException {
        assertTrue(BridgeReader.readAll(dir.resolve("absent")).isEmpty());
        Files.writeString(dir.resolve("mvs.json"), FULL);
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        assertEquals(List.of("mvs"), List.copyOf(BridgeReader.readAll(dir).keySet()));
        Files.writeString(dir.resolve("other.json"), "{\"mod\": \"ddd\"}");
        BridgeReader.BridgeFormatException e = assertThrows(BridgeReader.BridgeFormatException.class, () -> BridgeReader.readAll(dir));
        assertTrue(e.getMessage().contains("does not match the file name other.json"), e.getMessage());
    }
}
