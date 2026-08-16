package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * SHA-256 identity for everything that could change what a rolled seed
 * measures: one dimension's merged config, the platform's stack version,
 * the measurement-relevant parts of the mod's own bytes, and every loaded
 * mod's id and version. A
 * {@code .seed-rolling/} file keyed by this hash is safe to reuse only as
 * long as none of those four moved — a stale hash is a config that changed
 * underneath the file, not a name collision.
 *
 * <p>{@code def} is null for content that describes no single dimension
 * (a registry-wide check like {@code structure-audit}); the hash then
 * covers only the stack version, mod artefact and mod list.
 *
 * <p>Every loaded mod goes in, not only the ones that ship worldgen data —
 * walking each jar for a {@code data/*&#47;worldgen/} entry is exact but costs
 * a classpath scan per call, and the config, stack version and full mod
 * list already change together at deploy time. Over-inclusion just means an
 * unrelated mod bump mints a new (harmless) cache entry; under-inclusion
 * means a worldgen-relevant change goes unnoticed, which is the direction
 * that actually corrupts a cached result.
 *
 * <p>The mod's own version string ({@link Artefacts#stackVersion()}) is
 * constant across local rebuilds, so two dev builds that measure
 * differently would otherwise collide on the same hash — the mod's own
 * compiled bytes are hashed alongside it to tell them apart.
 */
public final class InputHash {

    private static final Gson GSON = new Gson();

    /** Keyed by dimension name ("" for the null/global case) + config identity. */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** Lazily computed once per JVM — the mod's own bytes don't change mid-run. */
    private static volatile String artefactHashCache;

    private InputHash() {
    }

    public static String of(DimensionConfig def, MinecraftServer server) {
        String cacheKey = (def == null ? "" : def.getName()) + '@' + System.identityHashCode(def);
        return CACHE.computeIfAbsent(cacheKey,
                k -> hashOf(def, Artefacts.stackVersion(), sortedModVersions(), modArtefactHash()));
    }

    /**
     * The pure core: no {@link FabricLoader} or {@link MinecraftServer}
     * touched, so it is exercised directly rather than through {@link #of}.
     * {@code stackVersion}, {@code modVersions} and {@code modArtefactHash}
     * are supplied rather than read live, which is what makes this testable
     * without a running mod loader.
     */
    static String hashOf(DimensionConfig def, String stackVersion, List<String> modVersions,
                          String modArtefactHash) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("stackVersion=").append(stackVersion).append('\n');
        canonical.append("artefact=").append(modArtefactHash).append('\n');
        canonical.append("dimension=").append(def == null ? "null" : def.getName()).append('\n');
        canonical.append("config=");
        canonical.append(def == null ? "null" : canonicaliseJson(measurementConfig(def)));
        canonical.append('\n');
        List<String> sorted = new ArrayList<>(modVersions);
        Collections.sort(sorted);
        canonical.append("mods=[").append(String.join(";", sorted)).append(']');
        return sha256Hex(canonical.toString());
    }

    /**
     * The config as it bears on a measurement — everything except {@code seed}.
     *
     * <p>A candidate is measured at the seed the ROLL drew, never at the one
     * the config names: no criterion and no part of {@link
     * com.customdimensions.facts.FactsEngine} reads it, and its only readers
     * are the viewer's "starting" badge. Hashing it meant picking a winner
     * rewrote the config and re-keyed the dimension's whole bank, so the board
     * the choice was made from vanished at the moment of choosing and a roll
     * for more candidates started from nothing.
     *
     * <p>{@code spawn} stays in: the facts measure at the declared spawn
     * column, so moving it genuinely changes what every candidate says.
     */
    private static JsonElement measurementConfig(DimensionConfig def) {
        JsonElement tree = GSON.toJsonTree(def);
        if (tree.isJsonObject()) {
            tree.getAsJsonObject().remove("seed");
        }
        return tree;
    }

    private static List<String> sortedModVersions() {
        List<String> out = new ArrayList<>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            out.add(mod.getMetadata().getId() + '='
                    + mod.getMetadata().getVersion().getFriendlyString());
        }
        Collections.sort(out);
        return out;
    }

    /**
     * The customdimensions mod's own content hash, computed once and cached
     * for the life of the JVM. A packaged jar is hashed as raw bytes; a
     * Loom dev environment mounts the mod as a directory of loose classes,
     * hashed instead as the sorted list of every {@code .class}'s relative
     * path, size and last-modified time.
     */
    private static String modArtefactHash() {
        String cached = artefactHashCache;
        if (cached != null) {
            return cached;
        }
        synchronized (InputHash.class) {
            if (artefactHashCache == null) {
                artefactHashCache = computeModArtefactHash();
            }
            return artefactHashCache;
        }
    }

    private static String computeModArtefactHash() {
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("customdimensions");
        if (container.isEmpty()) {
            return "no-mod-container";
        }
        ModOrigin origin = container.get().getOrigin();
        List<Path> paths = origin.getKind() == ModOrigin.Kind.PATH
                ? origin.getPaths() : container.get().getRootPaths();
        try {
            List<String> hashes = new ArrayList<>();
            for (Path path : paths) {
                hashes.add(hashArtefactPath(path));
            }
            Collections.sort(hashes);
            return sha256Hex(String.join(";", hashes));
        } catch (IOException e) {
            MultiverseServer.LOGGER.warn("Failed to hash the customdimensions artefact "
                    + "for seed-roll identity; banked results may over-invalidate", e);
            return "artefact-hash-error";
        }
    }

    /**
     * Only the parts of the mod that can change what a measurement SAYS.
     *
     * <p>Hashing the whole artefact meant a stylesheet in the viewer, or a
     * colour in the map renderer, invalidated every banked candidate in the
     * pack — thousands of measurements thrown away for a change that could
     * not move a single number. What can move one is the sampler, the facts
     * engine, the scorer, the search, the worldgen and structure placement,
     * the config parser, and the jar-baked worldgen data.
     *
     * <p>Under-inclusion is the dangerous direction: a measurement change
     * that goes unnoticed reuses a stale number. Anything ambiguous belongs
     * on this list.
     */
    private static final List<String> MEASUREMENT_PATHS = List.of(
            "com/customdimensions/facts/",
            "com/customdimensions/score/",
            "com/customdimensions/dimension/",
            "com/customdimensions/config/",
            "com/customdimensions/mixin/",
            "com/customdimensions/command/SpikeSampler",
            "com/customdimensions/command/ColumnScan",
            "com/customdimensions/command/InputHash",
            "com/customdimensions/roll/Roller",
            "com/customdimensions/roll/SeedBank",
            "data/",
            "structure_themes.json",
            "structure_type_defaults.json",
            "structure_default_wants.json");

    /** Whether a jar entry or class path is one a measurement depends on. */
    static boolean affectsMeasurement(String entry) {
        String normalised = entry.replace('\\', '/');
        for (String prefix : MEASUREMENT_PATHS) {
            if (normalised.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A packaged jar is hashed entry by entry; a dev-environment class
     * directory as a manifest. Either way only the measurement-relevant
     * entries count, so the viewer and the map renderer can change without
     * discarding a bank.
     */
    private static String hashArtefactPath(Path path) throws IOException {
        List<String> entries = new ArrayList<>();
        if (Files.isRegularFile(path)) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path.toFile())) {
                var it = zip.entries();
                while (it.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = it.nextElement();
                    if (entry.isDirectory() || !affectsMeasurement(entry.getName())) {
                        continue;
                    }
                    // The CRC the archive already carries: exact for "did
                    // these bytes change", and free to read.
                    entries.add(entry.getName() + ":" + entry.getCrc());
                }
            }
        } else {
            try (Stream<Path> walk = Files.walk(path)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String relative = path.relativize(p).toString();
                    if (!affectsMeasurement(relative)) {
                        continue;
                    }
                    entries.add(relative + ":" + Files.size(p) + ":"
                            + Files.getLastModifiedTime(p).toMillis());
                }
            }
        }
        Collections.sort(entries);
        return sha256Hex(String.join(";", entries));
    }

    /**
     * A JSON tree rendered with object keys sorted at every level, so two
     * trees with the same key/value pairs in a different insertion order
     * (Gson serialising a {@code Map} field, say) hash identically.
     */
    static String canonicaliseJson(JsonElement element) {
        StringBuilder out = new StringBuilder();
        writeCanonical(element, out);
        return out.toString();
    }

    private static void writeCanonical(JsonElement element, StringBuilder out) {
        if (element == null || element.isJsonNull()) {
            out.append("null");
        } else if (element.isJsonPrimitive()) {
            out.append(element.getAsJsonPrimitive());
        } else if (element.isJsonArray()) {
            out.append('[');
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeCanonical(array.get(i), out);
            }
            out.append(']');
        } else if (element.isJsonObject()) {
            out.append('{');
            JsonObject object = element.getAsJsonObject();
            List<String> keys = new ArrayList<>(object.keySet());
            Collections.sort(keys);
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append('"').append(keys.get(i)).append("\":");
                writeCanonical(object.get(keys.get(i)), out);
            }
            out.append('}');
        }
    }

    private static String sha256Hex(String input) {
        return toHex(newDigest().digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** Test seam only. */
    static void resetCache() {
        CACHE.clear();
    }
}
