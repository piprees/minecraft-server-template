package com.customdimensions.command;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Diagnostic artefacts — the answer goes in a file, not down the wire.
 *
 * RCON concatenates feedback lines with no separator and truncates the
 * response at a few KB, so any command that iterates a registry or a world
 * comes back as one unreadable, half-missing string — which looks like a
 * working command until you try to use it. Every diagnostic command therefore
 * answers with a one-line summary plus a path, and writes the real answer
 * here for a checker in {@code scripts/} to assert over offline.
 *
 * Two properties this class exists to guarantee:
 *
 * <ul>
 *   <li><b>Atomic.</b> A large census is tens of thousands of positions and
 *       takes real time to serialise. A checker reading the file while it is
 *       being written would see truncated JSON and report a fault that does
 *       not exist. Writes go to a sibling {@code .tmp} and are renamed into
 *       place, so a reader sees either the old file or the new one.</li>
 *   <li><b>Stamped.</b> Every artefact carries the {@code stackVersion} that
 *       produced it and {@code generatedAt}. A checker meeting a stamp other
 *       than its own says so instead of silently reading stale data — this
 *       codebase's worst failure mode is a green run over an artefact nobody
 *       has re-dumped. The stamp is derived, never hand-set.</li>
 * </ul>
 */
public final class Artefacts {

    /**
     * The release that built this jar. release.yml passes the release tag as
     * {@code -Pmod_version}, so a shipped artefact is stamped with the stack
     * version that wrote it; a local build reports the dev default from
     * gradle.properties, which the checkers treat as "unknown, don't compare".
     */
    public static String stackVersion() {
        return FabricLoader.getInstance()
                .getModContainer("customdimensions")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private Artefacts() {
    }

    /** {@code config/custom-dimensions/} inside the server data directory. */
    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("custom-dimensions");
    }

    public static Path dir(String subdirectory) {
        return dir().resolve(subdirectory);
    }

    /**
     * The JSON header every artefact opens with, including the trailing
     * comma — callers append their own fields straight after it.
     */
    public static String jsonHeader(String kind) {
        return "{\n \"stackVersion\": \"" + stackVersion() + "\""
                + ",\n \"kind\": \"" + kind + "\""
                + ",\n \"generatedAt\": \"" + Instant.now() + "\",\n";
    }

    /** The same header as a comment block, for the text/CSV artefacts. */
    public static String textHeader(String kind) {
        return "# stackVersion=" + stackVersion()
                + " kind=" + kind
                + " generatedAt=" + Instant.now() + "\n";
    }

    /**
     * Write an artefact atomically. Creates parent directories; leaves the
     * previous file untouched if serialisation or the write fails.
     */
    public static void write(Path target, String body) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, body);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some bind-mounted filesystems refuse ATOMIC_MOVE. A plain
            // replace still beats writing in place: the window where a reader
            // can see a partial file shrinks to the rename itself.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
