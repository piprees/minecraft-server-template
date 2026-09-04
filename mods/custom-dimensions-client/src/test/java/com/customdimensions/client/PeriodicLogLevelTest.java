package com.customdimensions.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * INFO is for what happens a bounded number of times in a session. A line on a
 * frame counter or a wall-clock interval belongs on DEBUG, behind
 * {@link Repeated}, or the log fills with it.
 *
 * <p>Two guards, and neither is a proof. The first reads the source for a
 * modulo or elapsed-time gate within {@link #WINDOW} lines above an INFO call,
 * so a gate written some other way, or far from the call it governs, gets
 * past it. The second names the files whose every path runs per frame and
 * allows them no INFO call at all, which is exact for those two files and says
 * nothing about any other.
 */
class PeriodicLogLevelTest {

    /** How far above a log call a gate still governs it. */
    private static final int WINDOW = 12;

    private static final Pattern INFO_CALL = Pattern.compile("\\bLOGGER\\s*\\.\\s*info\\s*\\(");

    /** {@code passes % REPORT_EVERY == 1}, and every counter shaped like it. */
    private static final Pattern MODULO_GATE = Pattern.compile("%\\s*[A-Za-z0-9_]+\\s*[=<>]=");

    /** {@code System.currentTimeMillis() - lastSampleAt >= SAMPLE_INTERVAL_MS}. */
    private static final Pattern ELAPSED_GATE =
            Pattern.compile("(currentTimeMillis|nanoTime)\\s*\\(\\)\\s*-.*[<>]=");

    @Test
    void noPeriodicLineIsLoggedAtInfo() throws IOException {
        Path root = sourceRoot();
        List<Path> sources = javaSources(root);
        assertTrue(sources.size() > 20,
                "read " + sources.size() + " sources under " + root + "; the scan found nothing");

        List<String> offences = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source);
            for (int index = 0; index < lines.size(); index++) {
                if (!INFO_CALL.matcher(lines.get(index)).find()) {
                    continue;
                }
                String gate = gateAbove(lines, index);
                if (gate != null) {
                    offences.add(root.relativize(source) + ":" + (index + 1)
                            + " logs at INFO under the gate `" + gate.trim() + "`");
                }
            }
        }
        if (!offences.isEmpty()) {
            fail("periodic lines must go through Repeated.log, not LOGGER.info:\n  "
                    + String.join("\n  ", offences));
        }
    }

    /**
     * Every path in these two runs once per frame, so no line either of them
     * writes can be bounded per session. Repeating diagnostics go through
     * {@link Repeated}, which routes the first to INFO and the rest to DEBUG.
     */
    @Test
    void perFrameFilesLogNothingAtInfo() throws IOException {
        Path root = sourceRoot();
        List<Path> perFrame = List.of(
                root.resolve("com/customdimensions/client/realtime/SpectatorPass.java"),
                root.resolve("com/customdimensions/client/render/ProjectionRenderer.java"));
        for (Path source : perFrame) {
            assertTrue(Files.isRegularFile(source), "missing " + source);
            List<String> lines = Files.readAllLines(source);
            for (int index = 0; index < lines.size(); index++) {
                if (INFO_CALL.matcher(lines.get(index)).find()) {
                    fail(root.relativize(source) + ":" + (index + 1)
                            + " logs at INFO from a per-frame path");
                }
            }
        }
    }

    private static String gateAbove(List<String> lines, int index) {
        for (int back = Math.max(0, index - WINDOW); back < index; back++) {
            String line = lines.get(back);
            if (MODULO_GATE.matcher(line).find() || ELAPSED_GATE.matcher(line).find()) {
                return line;
            }
        }
        return null;
    }

    /** The mod's own sources, whether the run starts here or at the repo root. */
    private static Path sourceRoot() {
        Path here = Path.of("src", "main", "java");
        if (Files.isDirectory(here)) {
            return here;
        }
        Path fromRepo = Path.of("mods", "custom-dimensions-client", "src", "main", "java");
        assertTrue(Files.isDirectory(fromRepo),
                "no source tree at " + here.toAbsolutePath() + " or " + fromRepo.toAbsolutePath());
        return fromRepo;
    }

    private static List<Path> javaSources(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".java")).toList();
        }
    }
}
