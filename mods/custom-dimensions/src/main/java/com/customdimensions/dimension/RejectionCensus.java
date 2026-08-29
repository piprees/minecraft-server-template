package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.Artefacts;
import net.minecraft.server.world.ServerWorld;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records noise sites that ended up empty: every candidate in the site's
 * chain declined the position. One record per SITE, not per attempt — the
 * chain tries up to {@code NoiseStructureSelectionMixin}'s cap per site, and
 * a per-attempt record makes chunk generation pay a file rewrite per attempt.
 *
 * <p>Written to {@code <world save root>/customdimensions/census/rejections__<dim>.json}.
 * A chunk generates once, so the file accumulates across restarts: prior
 * sessions' entries are read once per world and carried verbatim.
 *
 * <p>Chunk generation runs from several c2me workers at once, so a buffer is
 * its own lock and the file is written from inside it. Writes are batched by
 * {@link #FLUSH_INTERVAL_MS} and {@link #FLUSH_PENDING}, and forced by
 * {@link #flush} on world unload.
 */
public final class RejectionCensus {

    private RejectionCensus() {
    }

    /** A site nothing could occupy, and how many candidates were tried. */
    public record EmptySite(String group, String structureId, int chunkX, int chunkZ,
                            int candidates, int biomeRejections) {
    }

    static final long FLUSH_INTERVAL_MS = 2000L;
    static final int FLUSH_PENDING = 128;

    private static final Map<String, Buffer> BUFFERS = new ConcurrentHashMap<>();

    /**
     * Records a site left empty. The log line is one per site and deduped by
     * the buffer, so a regenerated chunk does not double-count.
     */
    public static void siteEmpty(ServerWorld world, String worldId, String group,
                                 String structureId, int chunkX, int chunkZ,
                                 int candidates, int biomeRejections) {
        Path path;
        try {
            path = Artefacts.censusDir(world.getServer())
                    .resolve("rejections__" + worldId.replace(":", "__") + ".json");
        } catch (RuntimeException e) {
            return;
        }
        Buffer buffer = BUFFERS.computeIfAbsent(worldId, id -> new Buffer(id, path));
        buffer.add(new EmptySite(group, structureId, chunkX, chunkZ,
                candidates, biomeRejections));
    }

    /** Forces a world's pending records to disk. Called on world unload. */
    public static void flush(String worldId) {
        Buffer buffer = BUFFERS.remove(worldId);
        if (buffer != null) {
            buffer.flush();
        }
    }

    /** Test seam. */
    static void resetForTests() {
        BUFFERS.clear();
    }

    /**
     * One world's pending records plus the entries earlier sessions wrote.
     * Every method holds the buffer's monitor: the work under it is a map put,
     * and only a due flush touches the filesystem.
     */
    static final class Buffer {

        private final String worldId;
        private final Path path;
        private final Map<String, EmptySite> sites = new LinkedHashMap<>();
        private String carried;
        private int pending;
        /** Zero so the first record of a session reaches disk immediately. */
        private long lastFlushMs;

        Buffer(String worldId, Path path) {
            this.worldId = worldId;
            this.path = path;
        }

        synchronized void add(EmptySite site) {
            String key = site.group() + '@' + site.chunkX() + ',' + site.chunkZ();
            if (sites.putIfAbsent(key, site) != null) {
                return;
            }
            pending++;
            MultiverseServer.LOGGER.info(
                    "Noise pick: site [{}, {}] in group {} (world {}) left empty after {} "
                    + "candidate(s), {} refused by biome, assigned {}",
                    site.chunkX(), site.chunkZ(), site.group(), worldId,
                    site.candidates(), site.biomeRejections(), site.structureId());
            if (pending >= FLUSH_PENDING
                    || System.currentTimeMillis() - lastFlushMs >= FLUSH_INTERVAL_MS) {
                flush();
            }
        }

        synchronized void flush() {
            if (pending == 0) {
                return;
            }
            if (carried == null) {
                carried = readCarried(path);
            }
            try {
                Artefacts.write(path, render(worldId, carried, sites.values()));
                pending = 0;
                lastFlushMs = System.currentTimeMillis();
            } catch (IOException | RuntimeException e) {
                MultiverseServer.LOGGER.debug(
                        "Failed to write rejection artefact: {}", e.getMessage());
            }
        }
    }

    /** Entries an earlier session wrote, verbatim, or "" when there are none. */
    static String readCarried(Path path) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            String existing = Files.readString(path);
            int open = existing.indexOf('[');
            int close = existing.lastIndexOf(']');
            return open < 0 || close <= open ? "" : existing.substring(open + 1, close).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** The whole file, header included. Pure, so the format is testable. */
    static String render(String worldId, String carried, Iterable<EmptySite> sites) {
        List<String> entries = new ArrayList<>();
        if (carried != null && !carried.isEmpty()) {
            entries.add(carried);
        }
        for (EmptySite site : sites) {
            entries.add("{\"group\": \"" + site.group()
                    + "\", \"structure\": \"" + site.structureId()
                    + "\", \"chunkX\": " + site.chunkX()
                    + ", \"chunkZ\": " + site.chunkZ()
                    + ", \"candidates\": " + site.candidates()
                    + ", \"biomeRejections\": " + site.biomeRejections() + '}');
        }
        return Artefacts.jsonHeader("structure-rejections")
                + " \"dimension\": \"" + worldId + "\",\n"
                + " \"emptySites\": [\n  "
                + String.join(",\n  ", entries)
                + "\n ]\n}\n";
    }
}
