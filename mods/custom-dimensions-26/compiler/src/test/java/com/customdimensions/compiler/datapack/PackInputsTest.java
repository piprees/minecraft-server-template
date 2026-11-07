package com.customdimensions.compiler.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackInputsTest {

    private static Path folder(Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"schemaVersion\": 2}");
        Files.writeString(dir.resolve("a.json"), "{\"seed\": 1}");
        Files.writeString(dir.resolve("conversion-report.md"), "ignored");
        return dir;
    }

    @Test
    void theSameInputsGiveTheSameHash(@TempDir Path dir) throws Exception {
        folder(dir);
        String first = PackInputs.hash(dir, "0.1.0", "26.3");
        assertEquals(first, PackInputs.hash(dir, "0.1.0", "26.3"));
        assertEquals(64, first.length());
        Files.writeString(dir.resolve("conversion-report.md"), "changed");
        assertEquals(first, PackInputs.hash(dir, "0.1.0", "26.3"), "only json files are inputs");
    }

    @Test
    void everyInputChangesTheHash(@TempDir Path dir) throws Exception {
        folder(dir);
        String base = PackInputs.hash(dir, "0.1.0", "26.3");
        assertNotEquals(base, PackInputs.hash(dir, "0.1.1", "26.3"), "mod version");
        assertNotEquals(base, PackInputs.hash(dir, "0.1.0", "26.3.1"), "Minecraft version");
        Files.writeString(dir.resolve("a.json"), "{\"seed\": 2}");
        String definition = PackInputs.hash(dir, "0.1.0", "26.3");
        assertNotEquals(base, definition, "a definition's bytes");
        Files.writeString(dir.resolve("settings.json"), "{\"schemaVersion\": 2, \"namespace\": \"x\"}");
        String settings = PackInputs.hash(dir, "0.1.0", "26.3");
        assertNotEquals(definition, settings, "settings.json");
        Files.move(dir.resolve("a.json"), dir.resolve("b.json"));
        assertNotEquals(settings, PackInputs.hash(dir, "0.1.0", "26.3"), "a definition's name");
    }

    @Test
    void theHashRoundTripsThroughThePack(@TempDir Path dir) {
        assertTrue(PackInputs.read(dir).isEmpty());
        PackInputs.write(dir, "abc");
        assertEquals("abc", PackInputs.read(dir).orElseThrow());
    }
}
