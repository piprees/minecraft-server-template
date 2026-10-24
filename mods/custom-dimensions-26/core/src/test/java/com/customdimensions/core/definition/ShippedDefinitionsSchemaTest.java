package com.customdimensions.core.definition;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * The converted set in {@code definitions/} against {@code schema/definition.schema.json}
 * and the v2 reader, plus the schema's own rules on hand-written files.
 */
class ShippedDefinitionsSchemaTest {

    private static final Path DEFINITIONS = Path.of("../definitions");
    private static final Path SCHEMA_FILE = Path.of("../schema/definition.schema.json");

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(SCHEMA_FILE), InputFormat.JSON);
    }

    private static List<Path> definitionFiles() throws IOException {
        try (Stream<Path> files = Files.list(DEFINITIONS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("settings.json"))
                    .sorted().toList();
        }
    }

    @Test
    void everyShippedDefinitionValidatesAndLoads() throws IOException {
        List<Path> files = definitionFiles();
        assertEquals(82, files.size(), "82 definitions in definitions/");
        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file);
            schema.validate(text, InputFormat.JSON).forEach(e -> failures.add(file.getFileName() + ": " + e));
            assertEquals(2, DefinitionReader.read(text, file.toString()).schemaVersion());
        }
        assertEquals(List.of(), failures);
        assertTrue(Files.exists(DEFINITIONS.resolve("the_aviary.json")));
        assertFalse(Files.exists(DEFINITIONS.resolve("paradise_lost.json")));
    }

    @Test
    void shippedSettingsLoad() {
        DefinitionSettings settings = DefinitionReader.readSettings(DEFINITIONS.resolve("settings.json"));
        assertEquals(2, settings.schemaVersion());
    }

    private static boolean valid(String text) {
        return schema.validate(text.replace('\'', '"'), InputFormat.JSON).isEmpty();
    }

    @Test
    void theSelfTestFixturesValidateAndLoad() throws IOException {
        List<String> failures = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("../server/src/selftest/definitions"))) {
            for (Path file : files.sorted().toList()) {
                String text = Files.readString(file);
                schema.validate(text, InputFormat.JSON).forEach(e -> failures.add(file.getFileName() + ": " + e));
                DefinitionReader.read(text, file.toString());
            }
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void theSchemaChecksDeathsAndWins() {
        assertTrue(valid("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'deaths': {'inventory': 'keep'},"
                + " 'wins': [{'id': 'a', 'title': 'A', 'trigger': {'type': 'kill', 'entity': '#minecraft:undead', 'count': 3}}]}"));
        assertFalse(valid("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'deaths': {'inventory': 'maybe'}}"));
        assertFalse(valid("{'schemaVersion': 2, 'generator': {'type': 'void'},"
                + " 'wins': [{'id': 'a', 'title': 'A', 'trigger': {'type': 'reach'}}]}"));
        assertFalse(valid("{'schemaVersion': 2, 'generator': {'type': 'void'},"
                + " 'wins': [{'id': 'A b', 'title': 'A', 'trigger': {'type': 'enter'}}]}"));
    }

    @Test
    void theSchemaAcceptsAMinimalDefinition() {
        assertTrue(valid("{'schemaVersion': 2, 'generator': {'type': 'void'}}"));
        assertTrue(valid("{'schemaVersion': 2, 'generator': {'type': 'noise', 'preset': 'end', 'endIsland': false}}"));
        assertTrue(valid("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'structures': {'laterField': 1},"
                + " 'portals': [{'laterField': 1, 'preview': {'previewDepth': 4}}], 'terrain': {'base': null}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{'generator': {'type': 'void'}}",
            "{'schemaVersion': 1, 'generator': {'type': 'void'}}",
            "{'schemaVersion': 2}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'seedRoll': {}}",
            "{'schemaVersion': 2, 'generator': {'type': 'nether'}}",
            "{'schemaVersion': 2, 'generator': {'type': 'noise'}}",
            "{'schemaVersion': 2, 'generator': {'type': 'noise', 'preset': 'tall'}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void', 'preset': 'end'}}",
            "{'schemaVersion': 2, 'generator': {'type': 'clone'}}",
            "{'schemaVersion': 2, 'generator': {'type': 'noise', 'preset': 'overworld', 'cellScale': 2}}",
            "{'schemaVersion': 2, 'generator': {'type': 'noise', 'preset': 'floating_islands', 'endIsland': false}}",
            "{'schemaVersion': 2, 'generator': {'type': 'single_biome'}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void', 'extra': 1}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'liquids': {'aquifers': true}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'rules': {'bedWorks': true}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'atmosphere': {'skyColor': 'blue'}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'dimensionType': {'scale': 8}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'borders': {'player': 1, 'shape': 'square'}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'portals': [{'immersive': true}]}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'roll': {'family': 'end'}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'structures': {'shuns': {'village': {'minDistance': 5}}}}",
            "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'spawn': [0, 64]}"})
    void theSchemaRefuses(String text) {
        assertFalse(valid(text), text);
    }
}
