package com.customdimensions.compiler.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackDriftTest {

    private static DatapackCompiler.Output compile() {
        return DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), VersionTable.V26_3);
    }

    private static String emitted(DatapackCompiler.Output output, String slug, String folder) {
        return output.report().entry(slug).emitted().stream().filter(p -> p.contains(folder)).findFirst().orElseThrow();
    }

    @Test
    void anIdenticalRecompileChangesNothing() {
        DatapackCompiler.Output output = compile();
        assertEquals(List.of(), PackDrift.changed(output.files(), output));
    }

    @Test
    void noPackBeforeIsNotDrift() {
        assertEquals(List.of(), PackDrift.changed(Map.of(), compile()));
    }

    @Test
    void noiseSettingsAndGeneratorChangesAreDriftAndOtherFilesAreNot() {
        DatapackCompiler.Output output = compile();
        Map<String, String> before = new TreeMap<>(output.files());
        String noise = emitted(output, "the_basalt_spires", "/worldgen/noise_settings/");
        JsonObject settings = JsonParser.parseString(before.get(noise)).getAsJsonObject();
        settings.addProperty("sea_level", settings.get("sea_level").getAsInt() + 1);
        before.put(noise, settings.toString());

        String stem = emitted(output, "the_blackstone_keep", "/" + DatapackCompiler.LAZY_FOLDER + "/");
        JsonObject lazy = JsonParser.parseString(before.get(stem)).getAsJsonObject();
        lazy.getAsJsonObject("generator").addProperty("seed", 1L);
        before.put(stem, lazy.toString());

        String type = emitted(output, "the_boneyard", "/dimension_type/");
        JsonObject dimensionType = JsonParser.parseString(before.get(type)).getAsJsonObject();
        dimensionType.addProperty("ambient_light", 0.5);
        before.put(type, dimensionType.toString());

        String reformatted = emitted(output, "the_canvas", "/" + DatapackCompiler.LAZY_FOLDER + "/");
        before.put(reformatted, JsonParser.parseString(before.get(reformatted)).toString());

        assertEquals(List.of("the_basalt_spires", "the_blackstone_keep"), PackDrift.changed(before, output));
    }

    @Test
    void thePackReadsBackAsWritten(@TempDir Path dir) {
        DatapackCompiler.Output output = compile();
        DatapackCompiler.write(output, dir.resolve("pack"), dir.resolve("report.md"));
        Map<String, String> read = PackDrift.read(dir.resolve("pack"));
        Map<String, String> data = new TreeMap<>(output.files());
        data.remove("pack.mcmeta");
        assertEquals(data, read);
        assertTrue(PackDrift.read(dir.resolve("absent")).isEmpty());
    }
}
