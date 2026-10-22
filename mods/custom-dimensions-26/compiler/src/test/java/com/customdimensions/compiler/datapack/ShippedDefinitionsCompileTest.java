package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.Generator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every shipped definition compiles, or is a clone and is reported as one. */
class ShippedDefinitionsCompileTest {

    private static List<Path> definitionFiles() throws IOException {
        try (Stream<Path> files = Files.list(Fixtures.definitions())) {
            return files.filter(p -> p.toString().endsWith(".json") && !p.getFileName().toString().equals("settings.json"))
                    .sorted().toList();
        }
    }

    @Test
    void everyDefinitionCompilesOrIsAReportedClone() throws IOException {
        DatapackCompiler.Output out = DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), VersionTable.V26_3);
        List<Path> files = definitionFiles();
        assertEquals(82, files.size());
        assertEquals(files.size(), out.report().entries().size());
        int clones = 0;
        int base = 0;
        for (Path file : files) {
            String slug = file.getFileName().toString().replace(".json", "");
            Definition def = DefinitionReader.read(file);
            CompileReport.Entry entry = out.report().entry(slug);
            assertTrue(out.reportText().contains("### " + slug + "\n"), slug + " missing from the report");
            if (Generator.CLONE.equals(def.generator().type())) {
                clones++;
                assertEquals(CompileReport.Kind.CLONE, entry.kind(), slug);
                assertTrue(entry.emitted().isEmpty(), slug);
                continue;
            }
            String stem = entry.kind() == CompileReport.Kind.BASE_WORLD ? null
                    : "data/customdimensions/lazy_dimension/" + slug + ".json";
            if (entry.kind() == CompileReport.Kind.BASE_WORLD) {
                base++;
            } else {
                assertEquals(CompileReport.Kind.LAZY, entry.kind(), slug);
                assertTrue(entry.emitted().contains(stem), slug);
            }
            assertTrue(entry.emitted().contains("data/adventure/dimension_type/" + slug + ".json"), slug);
        }
        assertEquals(4, clones);
        assertEquals(4, base);
    }

    @Test
    void everyReferenceResolves() {
        DatapackCompiler.Output out = DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), VersionTable.V26_3);
        Map<String, String> files = out.files();
        for (Map.Entry<String, String> f : files.entrySet()) {
            String path = f.getKey();
            if (path.contains("/dimension/") || path.contains("/lazy_dimension/")) {
                JsonObject stem = JsonParser.parseString(f.getValue()).getAsJsonObject();
                assertTrue(files.containsKey(DatapackCompiler.dataPath(stem.get("type").getAsString(), "dimension_type")), path);
                JsonObject generator = stem.getAsJsonObject("generator");
                if (generator.has("settings") && generator.get("settings").isJsonPrimitive()) {
                    String settings = generator.get("settings").getAsString();
                    if (settings.startsWith("adventure:")) {
                        assertTrue(files.containsKey(DatapackCompiler.dataPath(settings, "worldgen/noise_settings")), path);
                    } else {
                        Fixtures.vanilla().noiseSettings(settings.substring("minecraft:".length()));
                    }
                }
                for (String biome : biomes(generator)) {
                    assertTrue(Fixtures.vanilla().biomeIds().contains(biome), path + " names " + biome);
                }
            }
            if (path.contains("/dimension_type/")) {
                JsonElement timelines = JsonParser.parseString(f.getValue()).getAsJsonObject().get("timelines");
                if (timelines != null && timelines.isJsonArray()) {
                    for (JsonElement id : timelines.getAsJsonArray()) {
                        String timeline = id.getAsString();
                        if (timeline.startsWith("adventure:")) {
                            assertTrue(files.containsKey(DatapackCompiler.dataPath(timeline, "timeline")), path);
                        } else {
                            Fixtures.vanilla().timeline(timeline);
                        }
                    }
                }
            }
        }
    }

    @Test
    void minecraft264CompilesEveryDefinitionWithDisjointEntriesAndNoDefaultBlock() {
        DatapackCompiler.Output out = DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), VersionTable.V26_4);
        assertTrue(out.files().get("pack.mcmeta").contains("\"max_format\": 125"));
        for (Map.Entry<String, String> f : out.files().entrySet()) {
            JsonObject json = JsonParser.parseString(f.getValue()).getAsJsonObject();
            if (f.getKey().contains("/noise_settings/")) {
                assertFalse(json.has("default_block"), f.getKey());
            }
            if (f.getKey().contains("dimension/")) {
                JsonObject source = json.getAsJsonObject("generator").getAsJsonObject("biome_source");
                if (source != null && source.has("biomes") && source.get("biomes").getAsJsonArray().get(0).isJsonObject()) {
                    assertNull(MultiNoise.firstOverlap(source.getAsJsonArray("biomes")), f.getKey());
                }
            }
        }
    }

    private static List<String> biomes(JsonObject generator) {
        if (generator.has("settings") && generator.get("settings").isJsonObject()) {
            return List.of(generator.getAsJsonObject("settings").get("biome").getAsString());
        }
        JsonObject source = generator.getAsJsonObject("biome_source");
        if (source.has("biome")) {
            return List.of(source.get("biome").getAsString());
        }
        if (!source.has("biomes")) {
            return List.of();
        }
        return source.getAsJsonArray("biomes").asList().stream()
                .map(e -> e.isJsonObject() ? e.getAsJsonObject().get("biome").getAsString() : e.getAsString())
                .distinct().toList();
    }
}
