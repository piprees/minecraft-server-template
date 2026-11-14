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
    void theCommittedSetCarriesTheWholeTable() {
        int withBiomes = 0;
        int distinct = 0;
        for (IdMappingApplier.Result r : run.results()) {
            assertFalse(r.changed(), r.file());
            if (r.biomesBefore() == 0) continue;
            withBiomes++;
            distinct += r.biomesAfter();
        }
        assertEquals(77, withBiomes);
        assertEquals(733, distinct);
        assertTrue(readReport().contains(": 733 → 733\n"));
    }

    @Test
    void noRowIsLeftToApplyAndNoneIsMissing() {
        List<String> notApplied = new ArrayList<>();
        run.results().forEach(r -> r.notApplied().forEach(n -> notApplied.add(r.file() + " " + n.id())));
        assertEquals(List.of(), notApplied);
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
