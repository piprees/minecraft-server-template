package com.customdimensions.core.firewall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeGeneratorTest {

    private static Path zip(Path dir, String name, Map<String, String> files) throws IOException {
        Path out = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream z = new ZipOutputStream(os)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out;
    }

    @Test
    void writesSetsStructuresAndFeaturesUnreviewed(@TempDir Path dir) throws IOException {
        Path jar = zip(dir, "mod.jar", Map.of(
                "fabric.mod.json", "{\"id\": \"mvs\"}",
                "data/mvs/worldgen/structure_set/barns.json", "{\"placement\": {}, \"structures\": [{\"structure\": \"mvs:barn\", \"weight\": 1}, {\"structure\": \"mvs:big/barn\", \"weight\": 1}]}",
                "data/mvs/worldgen/structure/barn.json", "{}",
                "data/mvs/worldgen/structure/big/barn.json", "{}",
                "data/mvs/worldgen/structure/orphan.json", "{}",
                "data/mvs/worldgen/placed_feature/hay.json", "{}",
                "data/minecraft/worldgen/placed_feature/witch_hut_base.json", "{}",
                "data/mvs/structure/barn.nbt", ""));
        Bridge b = BridgeGenerator.generate(jar, Optional.empty());
        assertEquals("mvs", b.mod());
        assertFalse(b.reviewed());
        assertEquals(List.of("mvs:barns"), b.structureSets());
        assertEquals(List.of("mvs:barn", "mvs:big/barn", "mvs:orphan"), b.structures().stream().map(Bridge.Structure::id).toList());
        assertEquals("mvs:barns", b.structures().get(1).set());
        assertNull(b.structures().get(2).set());
        assertTrue(b.structures().stream().allMatch(s -> s.size().equals(Bridge.Size.UNMEASURED) && s.mood().isEmpty() && s.climate().isEmpty() && s.depth().isEmpty()));
        assertEquals(List.of("mvs:hay"), b.features());
        assertTrue(b.mixins().isEmpty() && b.registries().isEmpty() && b.spawns().isEmpty());
        assertEquals(b, BridgeReader.parse(BridgeReader.write(b), null));
    }

    @Test
    void datapackZipTakesItsNameOrTheGivenId(@TempDir Path dir) throws IOException {
        Path zip = zip(dir, "Clucking Ducks v1.zip", Map.of("pack.mcmeta", "{}"));
        assertEquals("clucking_ducks_v1", BridgeGenerator.generate(zip, Optional.empty()).mod());
        assertEquals("ducks", BridgeGenerator.generate(zip, Optional.of("ducks")).mod());
    }

    @Test
    void mainWritesTheFile(@TempDir Path dir) throws IOException {
        Path jar = zip(dir, "x.jar", Map.of("fabric.mod.json", "{\"id\": \"ddd\"}", "data/ddd/worldgen/structure_set/d.json", "{\"structures\": []}"));
        BridgeGenerator.main(new String[] {jar.toString(), dir.resolve("out").toString()});
        assertEquals(List.of("ddd:d"), BridgeReader.read(dir.resolve("out/ddd.json")).structureSets());
    }

    @Test
    void missingArchiveFails(@TempDir Path dir) {
        assertThrows(RuntimeException.class, () -> BridgeGenerator.generate(dir.resolve("none.jar"), Optional.empty()));
    }
}
