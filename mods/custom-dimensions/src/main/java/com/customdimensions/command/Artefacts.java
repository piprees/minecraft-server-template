package com.customdimensions.command;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * RCON truncates and concatenates response lines, so diagnostic commands
 * write results to a file here instead of returning them inline — a checker
 * in {@code scripts/} reads the file offline.
 *
 * Writes go through a temp file and an atomic rename so a reader never sees
 * a half-written file. Every artefact is stamped with {@code stackVersion}
 * and {@code generatedAt} so a checker can detect stale data.
 */
public final class Artefacts {

    /**
     * The release that built this jar, from {@code -Pmod_version} (release.yml);
     * a local build reports the gradle.properties dev default, which checkers
     * treat as "unknown, don't compare".
     */
    public static String stackVersion() {
        return FabricLoader.getInstance()
                .getModContainer("customdimensions")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /**
     * Whether a stamp names a release rather than a local build.
     *
     * <p>Local builds all share the same dev version, so a stamp match
     * between two dev builds proves nothing — anything reusing an artefact
     * rather than re-deriving it must ask this first.
     */
    public static boolean isRelease(String version) {
        return version != null && !version.isBlank()
                && !"unknown".equals(version) && !version.contains("local");
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
     * {@code .seed-rolling/} in the CONSUMER directory — never under
     * {@code data/}, which {@code ./dev clean} wipes and which is the mc
     * container's own volume, not the consumer's. The container has no
     * filesystem path back up to the consumer root, so this relies on a
     * dedicated bind mount sibling to the data mount: docker-compose.yml
     * mounts {@code ${SEED_ROLLING_DIR:-./.seed-rolling}} to
     * {@code /.seed-rolling}, a sibling of the run directory ({@code /data})
     * inside the container — the same one-hop relationship this resolves.
     */
    public static Path rollingDir() {
        return FabricLoader.getInstance().getGameDir().resolveSibling(".seed-rolling");
    }

    /**
     * The consumer's committed {@code overlay/config/custom-dimensions/dimensions/},
     * bind-mounted as a sibling of the run directory. A winner seed written
     * anywhere else does not reach git and does not survive
     * {@code ./dev refresh-config}, so a caller that finds this absent must
     * refuse rather than fall back to {@link #dir()}.
     */
    public static Path overlayDimensionsDir() {
        return FabricLoader.getInstance().getGameDir().resolveSibling("overlay-dimensions");
    }

    /** Whether the mount exists, i.e. whether durable output can be written. */
    public static boolean canWriteDurably() {
        return Files.isDirectory(rollingDir().getParent());
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
            // Some bind-mounted filesystems refuse ATOMIC_MOVE; plain replace
            // still narrows the partial-read window to the rename itself.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
