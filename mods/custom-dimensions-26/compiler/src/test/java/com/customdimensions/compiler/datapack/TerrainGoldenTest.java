package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.terrain.TerrainFamily;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each library terrain at its default setting, compiled as the only
 * definition, against {@code src/test/resources/golden/26.3/terrain_<name>/}
 * in {@link GoldenForm}. Regenerate with {@code -PupdateGolden=true}.
 */
class TerrainGoldenTest {

    @ParameterizedTest
    @EnumSource(TerrainFamily.class)
    void matchesTheCommittedOutput(TerrainFamily family) throws IOException {
        String slug = "terrain_" + family.id();
        DatapackCompiler.Output out = Fixtures.compile(slug, "\"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}, "
                + "\"terrain\": {\"base\": \"" + family.id() + "\"}");
        Set<String> emitted = new TreeSet<>(out.report().entry(slug).emitted());
        Path dir = Path.of(System.getProperty("customdimensions.golden")).resolve("26.3").resolve(slug);
        if (Boolean.getBoolean("customdimensions.updateGolden")) {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
                }
            }
            for (String path : emitted) {
                Path target = dir.resolve(path);
                Files.createDirectories(target.getParent());
                Files.writeString(target, GoldenForm.of(path, out.files().get(path), out.compiled().get(slug), Fixtures.vanilla()));
            }
        }
        Set<String> golden = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> golden.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(golden, emitted, "files emitted for " + slug);
        for (String path : emitted) {
            assertEquals(Files.readString(dir.resolve(path)),
                    GoldenForm.of(path, out.files().get(path), out.compiled().get(slug), Fixtures.vanilla()), path);
        }
    }
}
