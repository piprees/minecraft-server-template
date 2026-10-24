package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.Definition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Representative definitions compiled from the shipped set and compared, in
 * {@link GoldenForm} (our decisions only, no Mojang data), with the committed
 * files under {@code src/test/resources/golden/<version>/<slug>/}.
 * After an intended change, regenerate with
 * {@code ./gradlew :compiler:test -PupdateGolden=true} and review the diff.
 */
class GoldenFileTest {

    private static final Map<VersionTable.Version, List<String>> SLUGS = Map.of(
            VersionTable.V26_3, List.of("overworld", "the_nether", "the_end", "the_aviary", "the_wuthering_wisteria",
                    "the_basalt_spires", "the_red_monument", "the_tiled_expanse", "the_canvas", "the_abyssal_shrine",
                    "the_violet_spire", "the_endless_expanse", "the_amplified_reaches", "the_underdark", "the_blighted_maw"),
            VersionTable.V26_4, List.of("overworld", "the_basalt_spires"));
    private static final Map<VersionTable.Version, DatapackCompiler.Output> OUTPUTS = new HashMap<>();

    static Stream<Arguments> cases() {
        return SLUGS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> a.id().compareTo(b.id())))
                .flatMap(e -> e.getValue().stream().map(slug -> Arguments.of(e.getKey().id(), slug)));
    }

    private static synchronized DatapackCompiler.Output output(VersionTable.Version version) {
        return OUTPUTS.computeIfAbsent(version, v -> DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), v));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void matchesTheCommittedOutput(String versionId, String slug) throws IOException {
        VersionTable.Version version = VersionTable.forId(versionId);
        DatapackCompiler.Output output = output(version);
        Set<String> emitted = new TreeSet<>(output.report().entry(slug).emitted());
        Path dir = Path.of(System.getProperty("customdimensions.golden")).resolve(versionId).resolve(slug);
        Definition definition = output.compiled().get(slug);
        Map<String, String> forms = new HashMap<>();
        for (String path : emitted) {
            forms.put(path, GoldenForm.of(path, output.files().get(path), definition, Fixtures.vanilla()));
        }
        if (Boolean.getBoolean("customdimensions.updateGolden")) {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            for (String path : emitted) {
                Path target = dir.resolve(path);
                Files.createDirectories(target.getParent());
                Files.writeString(target, forms.get(path));
            }
        }
        Set<String> golden = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> golden.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(golden, emitted, "files emitted for " + slug);
        for (String path : emitted) {
            assertEquals(Files.readString(dir.resolve(path)), forms.get(path), path);
        }
    }
}
