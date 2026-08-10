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
import java.io.InputStream;
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
 * the mod's own compiled bytes, and every loaded mod's id and version. A
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
        canonical.append(def == null ? "null" : canonicaliseJson(GSON.toJsonTree(def)));
        canonical.append('\n');
        List<String> sorted = new ArrayList<>(modVersions);
        Collections.sort(sorted);
        canonical.append("mods=[").append(String.join(";", sorted)).append(']');
        return sha256Hex(canonical.toString());
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

    /** A packaged jar is hashed as bytes; a dev-environment class directory as a manifest. */
    private static String hashArtefactPath(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            MessageDigest digest = newDigest();
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        }
        List<String> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (!p.toString().endsWith(".class")) {
                    continue;
                }
                entries.add(path.relativize(p) + ":" + Files.size(p) + ":"
                        + Files.getLastModifiedTime(p).toMillis());
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
