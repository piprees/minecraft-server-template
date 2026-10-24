package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void regionFoldersFollowTheLevelIds() {
        Path world = dir.resolve("world");
        assertEquals(world.resolve("dimensions/minecraft/overworld/region"), PackInstaller.regionDir(world, "adventure", "overworld"));
        assertEquals(world.resolve("dimensions/minecraft/the_nether/region"), PackInstaller.regionDir(world, "adventure", "the_nether"));
        assertEquals(world.resolve("dimensions/adventure/the_aviary/region"), PackInstaller.regionDir(world, "adventure", "the_aviary"));
        assertEquals(world.resolve("dimensions/x/the_canvas/region"), PackInstaller.regionDir(world, "x", "the_canvas"));
    }

    @Test
    void driftWarnsOnlyForDimensionsWithChunks() throws IOException {
        Path world = dir.resolve("world");
        Path generated = PackInstaller.regionDir(world, "adventure", "the_ashgrove");
        Files.createDirectories(generated);
        Files.write(generated.resolve("r.0.0.mca"), new byte[(int) PackInstaller.REGION_HEADER + 4096]);
        Path headerOnly = PackInstaller.regionDir(world, "adventure", "the_canvas");
        Files.createDirectories(headerOnly);
        Files.write(headerOnly.resolve("r.0.0.mca"), new byte[(int) PackInstaller.REGION_HEADER]);
        Path overworld = PackInstaller.regionDir(world, "adventure", "overworld");
        Files.createDirectories(overworld);
        Files.write(overworld.resolve("r.-1.0.mca"), new byte[(int) PackInstaller.REGION_HEADER * 2]);

        assertTrue(PackInstaller.holdsChunks(generated));
        assertFalse(PackInstaller.holdsChunks(headerOnly));
        assertFalse(PackInstaller.holdsChunks(PackInstaller.regionDir(world, "adventure", "the_boneyard")));

        List<String> warnings = PackInstaller.driftWarnings(world, "adventure",
                List.of("overworld", "the_ashgrove", "the_boneyard", "the_canvas"));
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).startsWith("overworld: ") && warnings.get(0).contains("D2"), warnings.get(0));
        assertTrue(warnings.get(1).startsWith("the_ashgrove: ") && warnings.get(1).contains("D2"), warnings.get(1));
        assertEquals(List.of(), PackInstaller.driftWarnings(world, "adventure", List.of()));
    }
}
