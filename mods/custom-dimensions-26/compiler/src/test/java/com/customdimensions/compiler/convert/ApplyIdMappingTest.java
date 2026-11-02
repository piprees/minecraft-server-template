package com.customdimensions.compiler.convert;

import com.customdimensions.compiler.datapack.CompileReport;
import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.VanillaData;
import com.customdimensions.core.definition.DefinitionReader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code definitions/id-mapping.csv} applied to the real {@code definitions/} into a temporary folder. */
class ApplyIdMappingTest {

    private static Path definitions;
    private static Path out;
    private static Map<String, String> sourceBefore;
    private static ApplyIdMapping.Run run;

    @BeforeAll
    static void applyOnce(@TempDir Path temp) {
        definitions = Path.of(System.getProperty("customdimensions.definitions"));
        Path design = definitions.resolve("../../../docs/design/rebuild-26").normalize();
        out = temp;
        sourceBefore = ApplyIdMapping.readFolder(definitions);
        run = ApplyIdMapping.run(new ApplyIdMapping.Paths(definitions, definitions.resolve(IdMapping.FILE),
                design.resolve("mod-register.csv"), design.resolve("namespaces.csv"),
                Path.of(System.getProperty("customdimensions.vanilla")), definitions.resolveSibling("bridges"), out));
    }

    @Test
    void theRewrittenSetCompilesAndTheSourceIsUntouched() {
        assertNull(run.compileError());
        assertEquals(sourceBefore, ApplyIdMapping.readFolder(definitions));
        assertEquals(83, run.results().size());
        assertEquals(82, run.compiledAfter().report().entries().size());
        assertTrue(Files.exists(out.resolve(ApplyIdMapping.REPORT_FILE)));
        assertTrue(Files.exists(out.resolve(ApplyIdMapping.COMPILE_REPORT_FILE)));
    }

    @Test
    void totalsMatchTheCollapseCount() {
        int withBiomes = 0;
        int before = 0;
        int after = 0;
        Set<String> flagged = new TreeSet<>();
        for (IdMappingApplier.Result r : run.results()) {
            if (r.biomesBefore() == 0) continue;
            withBiomes++;
            before += r.biomesBefore();
            after += r.biomesAfter();
            if (r.biomesBefore() - r.biomesAfter() > r.biomesBefore() / 3.0) flagged.add(r.file());
        }
        assertEquals(75, withBiomes);
        assertEquals(927, before);
        assertEquals(745, after);
        assertEquals(Set.of("the_blossom_gardens.json", "the_highland_crossing.json", "the_sun_kingdoms.json"), flagged);
        assertTrue(readReport().contains(": 927 → 745\n"));
    }

    @Test
    void onlyTheTwoRarityKeysAreNotAppliedAndNoRowIsMissing() {
        List<String> notApplied = new ArrayList<>();
        run.results().forEach(r -> r.notApplied().forEach(n -> notApplied.add(r.file() + " " + n.id())));
        assertEquals(List.of("the_darkpine_depths.json explorify:dark_forest_settlements",
                "the_darkpine_depths.json betterdungeons:zombie_dungeons"), notApplied);
        List<String> missing = new ArrayList<>();
        run.results().forEach(r -> r.missing().forEach(m -> missing.add(r.file() + " " + m.id())));
        assertEquals(List.of(), missing);
    }

    @Test
    void everyRewrittenDefinitionValidatesAndLoads() throws IOException {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(definitions.resolveSibling("schema").resolve("definition.schema.json")), InputFormat.JSON);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> file : run.rewritten().entrySet()) {
            if (file.getKey().equals("settings.json")) continue;
            schema.validate(file.getValue(), InputFormat.JSON).forEach(e -> failures.add(file.getKey() + ": " + e));
            DefinitionReader.read(file.getValue(), file.getKey());
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void theSourceIsInWriterFormSoADiffShowsOnlyTheIds() {
        for (Map.Entry<String, String> file : sourceBefore.entrySet()) {
            assertEquals(file.getValue(), JsonWriter.write(JsonParser.parseString(file.getValue())), file.getKey());
        }
    }

    @Test
    void applyingToTheRewrittenSetChangesNothing() {
        VanillaData vanilla = new VanillaData(Path.of(System.getProperty("customdimensions.vanilla")));
        Path design = definitions.resolve("../../../docs/design/rebuild-26").normalize();
        IdMappingApplier again = new IdMappingApplier(IdMapping.read(definitions.resolve(IdMapping.FILE)),
                NamespaceRegister.load(design.resolve("mod-register.csv"), design.resolve("namespaces.csv")),
                ApplyIdMapping.targets(vanilla, definitions.resolveSibling("bridges"), "adventure", run.rewritten().keySet()));
        for (Map.Entry<String, String> file : run.rewritten().entrySet()) {
            IdMappingApplier.Result r = again.apply(file.getKey(), JsonParser.parseString(file.getValue()).getAsJsonObject());
            assertFalse(r.changed(), file.getKey());
        }
    }

    @Test
    void everyBiomeTheRewrittenPackNamesIsVanillaOrInThePack() {
        VanillaData vanilla = new VanillaData(Path.of(System.getProperty("customdimensions.vanilla")));
        Map<String, String> files = run.compiledAfter().files();
        for (Map.Entry<String, String> f : files.entrySet()) {
            if (!f.getKey().contains("/dimension/") && !f.getKey().contains("/lazy_dimension/")) continue;
            JsonObject source = JsonParser.parseString(f.getValue()).getAsJsonObject().getAsJsonObject("generator")
                    .getAsJsonObject("biome_source");
            if (source == null || !source.has("biomes")) continue;
            for (JsonElement e : source.getAsJsonArray("biomes")) {
                String biome = e.isJsonObject() ? e.getAsJsonObject().get("biome").getAsString() : e.getAsString();
                assertTrue(vanilla.biomeIds().contains(biome) || files.containsKey(DatapackCompiler.dataPath(biome, "worldgen/biome")),
                        f.getKey() + " names " + biome);
            }
        }
        for (CompileReport.Entry entry : run.compiledAfter().report().entries().values()) {
            for (String skipped : entry.skippedBiomes()) {
                assertTrue(skipped.contains("a void world takes one biome"), entry.slug() + ": " + skipped);
            }
        }
    }

    private static String readReport() {
        try {
            return Files.readString(out.resolve(ApplyIdMapping.REPORT_FILE));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
