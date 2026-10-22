package com.customdimensions.compiler.convert;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.DefinitionSettings;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The converter over the real 1.21.1 definitions in {@code config/custom-dimensions/}. */
class ConvertDefinitionsTest {

    private static final Path REPO = Path.of("../../..");
    private static final Path CONFIG = REPO.resolve("config/custom-dimensions");
    private static final Path DESIGN = REPO.resolve("docs/design/rebuild-26");
    private static final Path SCHEMA = Path.of("../schema/definition.schema.json");

    private static ConvertDefinitions.Result result;

    @BeforeAll
    static void convertOnce() {
        result = ConvertDefinitions.convert(paths(Path.of("unused")));
    }

    private static ConvertDefinitions.Paths paths(Path out) {
        return new ConvertDefinitions.Paths(CONFIG, DESIGN.resolve("mod-register.csv"),
                DESIGN.resolve("namespaces.csv"), out);
    }

    private static List<String> v1Slugs() throws IOException {
        try (Stream<Path> files = Files.list(CONFIG.resolve("dimensions"))) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json") && !n.endsWith("_thumb.json"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        }
    }

    private static Map<String, String> definitionFiles() {
        Map<String, String> definitions = new TreeMap<>(result.files());
        definitions.remove("settings.json");
        definitions.remove(ConvertDefinitions.REPORT_FILE);
        return definitions;
    }

    @Test
    void writesOneV2FilePerV1DefinitionWithParadiseLostAsTheAviary() throws IOException {
        List<String> slugs = v1Slugs();
        Map<String, String> definitions = definitionFiles();
        assertEquals(slugs.size(), definitions.size());
        assertTrue(definitions.containsKey("the_aviary.json"));
        assertFalse(definitions.containsKey("paradise_lost.json"));
        for (String slug : slugs) {
            assertTrue(definitions.containsKey(DefinitionConverter.targetSlug(slug) + ".json"), slug);
        }
        assertTrue(result.files().containsKey("settings.json"));
        assertTrue(result.files().containsKey(ConvertDefinitions.REPORT_FILE));
    }

    @Test
    void everyConvertedFileValidatesAgainstTheSchema() throws IOException {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(SCHEMA), InputFormat.JSON);
        List<String> failures = new ArrayList<>();
        definitionFiles().forEach((name, text) -> schema.validate(text, InputFormat.JSON)
                .forEach(error -> failures.add(name + ": " + error)));
        assertEquals(List.of(), failures);
    }

    @Test
    void everyConvertedFileLoadsThroughTheV2Model() {
        definitionFiles().forEach((name, text) -> {
            Definition definition = DefinitionReader.read(text, name);
            assertEquals(2, definition.schemaVersion(), name);
            assertTrue(definition.portals().size() >= 1, name + " keeps its portal");
        });
        DefinitionSettings settings = DefinitionReader.readSettings(result.files().get("settings.json"), "settings.json");
        assertEquals("adventure", settings.namespace());
        assertTrue(settings.defaults().has("frameBlock"));
    }

    @Test
    void reportFlagCountsMatchARegexOverTheRawV1Text() throws IOException {
        Map<String, Set<String>> decisions = new HashMap<>();
        List<String> rows = Files.readAllLines(DESIGN.resolve("namespaces.csv"));
        for (String line : rows.subList(1, rows.size())) {
            String[] cols = line.split(",");
            decisions.computeIfAbsent(cols[0], k -> new HashSet<>()).add(cols[2]);
        }
        Pattern quoted = Pattern.compile("\"#?([a-z0-9_.-]+):[a-z0-9_./-]+\"");
        List<Path> v1Files = new ArrayList<>();
        for (String slug : v1Slugs()) {
            v1Files.add(CONFIG.resolve("dimensions").resolve(slug + ".json"));
        }
        v1Files.add(CONFIG.resolve("settings.json"));
        Map<String, Integer> grep = new TreeMap<>();
        for (Path file : v1Files) {
            Matcher m = quoted.matcher(Files.readString(file));
            while (m.find()) {
                String ns = m.group(1);
                boolean flagged = !ns.equals("minecraft") && !ns.equals("adventure")
                        && decisions.containsKey(ns) && !decisions.get(ns).contains("kept");
                if (flagged) {
                    grep.merge(ns, 1, Integer::sum);
                }
            }
        }
        assertFalse(grep.isEmpty());
        assertEquals(grep, reportFlagTable(result.files().get(ConvertDefinitions.REPORT_FILE)));
    }

    /** Namespace to occurrences, read back from the report's totals table. */
    private static Map<String, Integer> reportFlagTable(String report) {
        Map<String, Integer> counts = new TreeMap<>();
        String section = report.substring(report.indexOf(ConversionReport.FLAGGED_HEADING));
        section = section.substring(0, section.indexOf("\n### ", 1));
        for (String line : section.split("\n")) {
            String[] cells = line.split("\\|");
            if (cells.length == 5 && cells[3].trim().matches("\\d+")) {
                counts.put(cells[1].trim(), Integer.parseInt(cells[3].trim()));
            }
        }
        return counts;
    }

    @Test
    void reportTotalsComeFirstThenOneSectionPerFile() throws IOException {
        String report = result.files().get(ConvertDefinitions.REPORT_FILE);
        assertTrue(report.indexOf("## Totals") < report.indexOf("## overworld.json"));
        assertTrue(report.contains("## paradise_lost.json → the_aviary.json"));
        for (String slug : v1Slugs()) {
            assertTrue(report.contains("\n## " + slug + ".json"), slug);
        }
        assertTrue(report.contains("\n## settings.json"));
        assertTrue(report.endsWith("\n") && !report.endsWith("\n\n"));
    }

    @Test
    void rerunningIsByteIdenticalAndRemovesFilesItNoLongerWrites(@TempDir Path tmp) throws IOException {
        Path first = tmp.resolve("first");
        Path second = tmp.resolve("second");
        ConvertDefinitions.write(ConvertDefinitions.convert(paths(first)), first);
        Files.writeString(Files.createDirectories(second).resolve("stale.json"), "{}");
        ConvertDefinitions.write(ConvertDefinitions.convert(paths(second)), second);
        assertFalse(Files.exists(second.resolve("stale.json")));
        List<String> names;
        try (Stream<Path> files = Files.list(first)) {
            names = files.map(p -> p.getFileName().toString()).sorted().toList();
        }
        try (Stream<Path> files = Files.list(second)) {
            assertEquals(names, files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        for (String name : names) {
            assertArrayEquals(Files.readAllBytes(first.resolve(name)), Files.readAllBytes(second.resolve(name)), name);
        }
        ConvertDefinitions.write(ConvertDefinitions.convert(paths(first)), first);
        for (String name : names) {
            assertArrayEquals(Files.readAllBytes(first.resolve(name)), Files.readAllBytes(second.resolve(name)), name);
            assertTrue(new String(Files.readAllBytes(first.resolve(name)), StandardCharsets.UTF_8).endsWith("\n"), name);
        }
    }
}
