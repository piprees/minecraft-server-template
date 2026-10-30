package com.customdimensions.core.definition;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The terrain gallery fixtures in {@code server/src/selftest/gallery/} against the schema, and the schema's terrain rules. */
class GalleryFixturesSchemaTest {

    private static final Path GALLERY = Path.of("../server/src/selftest/gallery");

    private static Schema schema() throws IOException {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("../schema/definition.schema.json")), InputFormat.JSON);
    }

    @Test
    void everyGalleryFixtureValidatesAndLoads() throws IOException {
        Schema schema = schema();
        List<String> failures = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> list = Files.list(GALLERY)) {
            files = list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertEquals(38, files.size(), "eleven terrains and scale at three settings each, and continents as v1's wide and compressed");
        for (Path file : files) {
            String text = Files.readString(file);
            schema.validate(text, InputFormat.JSON).forEach(e -> failures.add(file.getFileName() + ": " + e));
            DefinitionReader.read(text, file.toString());
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void theSchemaRefusesAnUnknownTerrainAndAnOutOfRangeParameter() throws IOException {
        Schema schema = schema();
        String head = "{\"schemaVersion\": 2, \"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, \"terrain\": ";
        assertFalse(schema.validate(head + "{\"base\": \"mountains\"}}", InputFormat.JSON).isEmpty());
        assertFalse(schema.validate(head + "{\"base\": \"canyons\", \"params\": {\"depth\": 500}}}", InputFormat.JSON).isEmpty());
        assertFalse(schema.validate(head + "{\"base\": \"canyons\", \"params\": {\"peakHeight\": 50}}}", InputFormat.JSON).isEmpty());
        assertFalse(schema.validate(head + "{\"base\": \"canyons\", \"scale\": 9}}", InputFormat.JSON).isEmpty());
        assertEquals(List.of(), schema.validate(head + "{\"base\": \"ridges\", \"setting\": \"extreme\", \"params\": {\"peakHeight\": 50}}}",
                InputFormat.JSON).stream().map(Object::toString).toList());
    }
}
