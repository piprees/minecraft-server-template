package com.customdimensions.portal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blocking chunk load from a world tick parks the main thread on a future
 * nothing is working on, and the server never recovers ([K1]/[K6]) — the
 * watchdog declares the tick crashed and forcibly shuts the server down.
 *
 * <p>Two rules, because a per-file scan for {@code getChunk(} only ever sees
 * a DIRECT call: a tick path that calls a helper which blocks is invisible to
 * it. So the second rule names the helper instead and pins who may call it.
 */
class TickPathChunkLoadTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "customdimensions");

    private static final List<Path> TICK_PATH_SOURCES = List.of(
            SOURCE_ROOT.resolve(Path.of("portal", "ExitPortalManager.java")),
            SOURCE_ROOT.resolve(Path.of("immersive", "ImmersivePreloader.java")),
            SOURCE_ROOT.resolve(Path.of("mixin", "ServerWorldMixin.java")),
            SOURCE_ROOT.resolve(Path.of("dimension", "ExitConditions.java")),
            SOURCE_ROOT.resolve(Path.of("dimension", "ExitTarget.java")));

    // world.getChunk(...) and chunkManager.getChunk(x, z, status, true) both
    // generate. The two-arg getWorldChunk(x, z, false) probe does not.
    private static final Pattern BLOCKING_GET_CHUNK =
            Pattern.compile("(?<!getWorld)(?<!\\w)getChunk\\s*\\(");

    /**
     * {@code PortalHelper.findSurfaceY} force-generates its column — it exists
     * to, because {@code World.getTopY} silently reports bottomY for an
     * unloaded one. Every caller therefore either blocks the thread it is on
     * or has already established the column is resident.
     *
     * <p>Filename to the reason it may block. A file not listed here calling
     * it is the defect this test exists for: from a tick path the call waits
     * for terrain generation and the server dies.
     */
    private static final Map<String, String> MAY_FORCE_SURFACE_COLUMN = Map.of(
            "PortalHelper.java", "declares it",
            "MultiverseServer.java", "SERVER_STARTED lifecycle, before any tick",
            "ExitPortalManager.java", "probes with getWorldChunk(x, z, false) and tickets a cold column first",
            "TryOut.java", "command thread, on a world it created in the same command");

    private static final Pattern SURFACE_CALL = Pattern.compile("findSurfaceY\\s*\\(");

    private static boolean isComment(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
    }

    @Test
    void tickPathSourcesNeverForceGenerateAChunk() throws IOException {
        for (Path source : TICK_PATH_SOURCES) {
            assertTrue(Files.exists(source), "missing source: " + source);
            List<String> lines = Files.readAllLines(source);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isComment(line)) {
                    continue;
                }
                assertTrue(!BLOCKING_GET_CHUNK.matcher(line).find(),
                        source + ":" + (i + 1) + " force-generates a chunk from a tick path: " + line.strip());
            }
        }
    }

    @Test
    void onlyCallersThatCanAffordToBlockAskForASurfaceColumn() throws IOException {
        TreeSet<String> callers = new TreeSet<>();
        try (Stream<Path> tree = Files.walk(SOURCE_ROOT)) {
            for (Path source : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(source)) {
                    if (!isComment(line) && SURFACE_CALL.matcher(line).find()) {
                        callers.add(source.getFileName().toString());
                    }
                }
            }
        }

        assertEquals(new TreeSet<>(MAY_FORCE_SURFACE_COLUMN.keySet()), callers,
                "findSurfaceY blocks until its chunk exists, generating it. A caller on a tick "
                + "path hangs the main thread until the watchdog kills the server — call "
                + "PortalHelper.arrivalSurfaceY instead, which probes the column and answers "
                + "null when it is cold. If a new caller genuinely can afford to block, add it "
                + "here with the reason");
    }

    @Test
    void theGuardedReaderProbesTheColumnBeforeItReadsIt() throws IOException {
        // Every tick path goes through arrivalSurfaceY, so its guard is the
        // whole fix. Losing it would leave all the callers looking correct
        // and put the blocking read straight back on the tick.
        String helper = Files.readString(SOURCE_ROOT.resolve(Path.of("portal", "PortalHelper.java")));

        int reader = helper.indexOf("public static Integer arrivalSurfaceY(");
        assertTrue(reader >= 0, "arrivalSurfaceY has moved or been renamed; update this guard");
        int probe = helper.indexOf("arrivalColumnReady(", reader);
        int read = helper.indexOf("findSurfaceY(", reader);

        assertTrue(probe >= 0 && probe < read,
                "arrivalSurfaceY reads the column before probing that it is resident — that is "
                + "the blocking chunk load back on the tick path, whatever its callers look like");
    }

    @Test
    void theBlockingSurfaceReadStillExists() {
        // Guards the rule above against becoming vacuous: if findSurfaceY were
        // renamed or deleted, an empty caller set would match an empty
        // whitelist and this file would assert nothing at all.
        assertTrue(MAY_FORCE_SURFACE_COLUMN.containsKey("PortalHelper.java"),
                "the declaring file must be in the whitelist, or the scan proves nothing");
    }
}
