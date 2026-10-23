package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackInstallerTest {
    @TempDir
    Path dir;

    @Test
    void levelNameComesFromServerProperties() throws IOException {
        Path file = Files.writeString(dir.resolve("server.properties"), "online-mode=false\nlevel-name=adventure\n");
        assertEquals("adventure", PackInstaller.levelName(file));
        assertEquals(dir.resolve("adventure/datapacks/customdimensions"), PackInstaller.packDir(dir, "adventure"));
    }

    @Test
    void levelNameDefaultsToWorld() throws IOException {
        assertEquals("world", PackInstaller.levelName(dir.resolve("missing.properties")));
        Path blank = Files.writeString(dir.resolve("server.properties"), "level-name=\nmotd=x\n");
        assertEquals("world", PackInstaller.levelName(blank));
    }
}
