package com.customdimensions.compiler.datapack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterminismTest {

    @Test
    void repeatRunsAreByteIdentical(@TempDir Path tmp) throws IOException {
        DatapackCompiler.Output first = DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), VersionTable.V26_3);
        DatapackCompiler.Output second = DatapackCompiler.compile(Fixtures.definitions(),
                new VanillaData(Fixtures.vanilla().root()), VersionTable.V26_3);
        assertEquals(first.files(), second.files());
        assertEquals(first.reportText(), second.reportText());

        DatapackCompiler.write(first, tmp.resolve("a/pack"), tmp.resolve("a/" + DatapackCompiler.REPORT_FILE));
        DatapackCompiler.write(second, tmp.resolve("b/pack"), tmp.resolve("b/" + DatapackCompiler.REPORT_FILE));
        Map<String, byte[]> a = tree(tmp.resolve("a"));
        Map<String, byte[]> b = tree(tmp.resolve("b"));
        assertEquals(a.keySet(), b.keySet());
        a.forEach((path, bytes) -> assertArrayEquals(bytes, b.get(path), path));
    }

    @Test
    void filesUseTwoSpaceIndentAndATrailingNewline() {
        DatapackCompiler.Output out = DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), VersionTable.V26_3);
        out.files().forEach((path, text) -> {
            assertTrue(text.endsWith("}\n"), path);
            assertFalse(text.contains("\t"), path);
            assertTrue(text.startsWith("{\n  \""), path);
        });
    }

    @Test
    void rewritingReplacesStaleFiles(@TempDir Path tmp) throws IOException {
        DatapackCompiler.Output out = DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), VersionTable.V26_3);
        Path pack = tmp.resolve("pack");
        DatapackCompiler.write(out, pack, tmp.resolve("report.md"));
        Path stale = pack.resolve("data/adventure/dimension_type/removed.json");
        Files.writeString(stale, "{}");
        DatapackCompiler.write(out, pack, tmp.resolve("report.md"));
        assertFalse(Files.exists(stale));
        assertEquals(out.files().size(), tree(pack).size());
    }

    @Test
    void refusesToEmptyADirectoryThatIsNotAPack(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("precious.txt"), "keep me");
        DatapackCompiler.Output out = DatapackCompiler.compile(Fixtures.definitions(), Fixtures.vanilla(), VersionTable.V26_3);
        assertThrows(java.io.UncheckedIOException.class, () -> DatapackCompiler.write(out, tmp, tmp.resolve("r.md")));
        assertTrue(Files.exists(tmp.resolve("precious.txt")));
    }

    private static Map<String, byte[]> tree(Path root) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString(), Files.readAllBytes(p));
            }
        }
        return out;
    }
}
