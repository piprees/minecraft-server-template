package com.customdimensions.portal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blocking chunk load from a world tick parks the main thread on a future
 * nothing is working on, and the server never recovers ([K1]/[K6]). These two
 * classes run from {@code ServerWorldMixin.onTick} and must probe with
 * {@code getWorldChunk(x, z, false)} or register a ticket instead.
 */
class TickPathChunkLoadTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "customdimensions");

    private static final List<Path> TICK_PATH_SOURCES = List.of(
            SOURCE_ROOT.resolve(Path.of("portal", "ExitPortalManager.java")),
            SOURCE_ROOT.resolve(Path.of("immersive", "ImmersivePreloader.java")));

    // world.getChunk(...) and chunkManager.getChunk(x, z, status, true) both
    // generate. The two-arg getWorldChunk(x, z, false) probe does not.
    private static final Pattern BLOCKING_GET_CHUNK =
            Pattern.compile("(?<!getWorld)(?<!\\w)getChunk\\s*\\(");

    @Test
    void tickPathSourcesNeverForceGenerateAChunk() throws IOException {
        for (Path source : TICK_PATH_SOURCES) {
            assertTrue(Files.exists(source), "missing source: " + source);
            List<String> lines = Files.readAllLines(source);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
                    continue;
                }
                assertTrue(!BLOCKING_GET_CHUNK.matcher(line).find(),
                        source + ":" + (i + 1) + " force-generates a chunk from a tick path: " + line.strip());
            }
        }
    }
}
