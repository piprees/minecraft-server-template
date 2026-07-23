package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Creation-time worldgen fingerprints (config/custom-dimensions-fingerprints.json,
 * sibling of portal_links.json). A dimension's generator is baked into
 * level.dat at creation and NEVER re-read from config — registerDimensions
 * skips keys already in the registry, and vanilla re-persists the stored
 * generator on every save. Deleting a dimension's region files (or even
 * `customdim destroy`) does not touch that entry, so a config type/noise/
 * biome change silently produces a world that no longer matches its config
 * (discovered 2026-07-22 converting dims to the cave type).
 *
 * Policy: NEVER delete or regenerate someone's world because the config
 * changed. Warn and keep the world as generated; regeneration is an
 * operator decision (full world wipe). Seed-only drift logs at INFO —
 * the seed roller re-pins winner seeds constantly, and a seed change is
 * routine tuning rather than a structural mismatch.
 */
public final class DimensionFingerprints {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Map<String, String>> cache;
    private static Path storePath;

    private DimensionFingerprints() {
    }

    private static Map<String, String> fields(DimensionConfig def) {
        Map<String, String> f = new HashMap<>();
        f.put("type", String.valueOf(def.getType()));
        f.put("noiseSettings", String.valueOf(def.getNoiseSettings()));
        f.put("biomes", String.valueOf(def.getBiome()));
        f.put("seed", String.valueOf(def.getSeed()));
        // Tier 2 creation-time generator knobs. Old fingerprint records lack
        // these keys — stored null vs current "null" compares equal, so
        // pre-Tier-2 worlds never false-positive on drift.
        f.put("checkerboardScale", String.valueOf(def.getCheckerboardScale()));
        f.put("layers", String.valueOf(def.getLayersFingerprint()));
        f.put("flatBiome", String.valueOf(def.getFlatBiome()));
        f.put("settingsOverrides", String.valueOf(def.getSettingsOverridesFingerprint()));
        f.put("biomeParameters", String.valueOf(def.getBiomeParametersFingerprint()));
        f.put("biomePatches", String.valueOf(def.getBiomePatchesFingerprint()));
        return f;
    }

    public static synchronized void init(MinecraftServer server) {
        storePath = server.getRunDirectory().resolve("config").resolve("custom-dimensions-fingerprints.json");
        cache = null; // reload lazily against the new path
    }

    /** New dimension registered: remember what it was created from. */
    public static synchronized void record(DimensionConfig def) {
        load();
        cache.put(def.getName(), fields(def));
        save();
    }

    /** Dimension destroyed at runtime: its next creation is a fresh baseline. */
    public static synchronized void forget(String name) {
        load();
        if (cache.remove(name) != null) {
            save();
        }
    }

    /**
     * Existing registry entry seen at boot: compare config vs creation-time
     * fingerprint. Worldgen drift (type/noiseSettings/biomes) warns; seed-only
     * drift is an INFO. No stored baseline (pre-feature world) adopts the
     * current config silently.
     */
    public static synchronized void checkExisting(DimensionConfig def) {
        load();
        Map<String, String> current = fields(def);
        Map<String, String> stored = cache.get(def.getName());
        if (stored == null) {
            cache.put(def.getName(), current);
            save();
            return;
        }
        StringBuilder worldgenDrift = new StringBuilder();
        for (String k : new String[]{"type", "noiseSettings", "biomes", "checkerboardScale", "layers", "flatBiome", "settingsOverrides", "biomeParameters", "biomePatches"}) {
            if (!String.valueOf(stored.get(k)).equals(current.get(k))) {
                if (worldgenDrift.length() > 0) {
                    worldgenDrift.append(", ");
                }
                worldgenDrift.append(k).append(": '").append(stored.get(k))
                        .append("' -> '").append(current.get(k)).append("'");
            }
        }
        boolean seedDrift = !String.valueOf(stored.get("seed")).equals(current.get("seed"));
        if (worldgenDrift.length() > 0) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: worldgen config changed since this world was created ({}) — "
                    + "KEEPING the world as generated; worldgen changes never apply to existing "
                    + "dimensions. Regenerating requires a full world wipe (the generator is "
                    + "baked into level.dat).", def.getName(), worldgenDrift);
        } else if (seedDrift) {
            MultiverseServer.LOGGER.info(
                    "Dimension {}: configured seed changed ({} -> {}) — existing world keeps its "
                    + "creation-time seed.", def.getName(), stored.get("seed"), current.get("seed"));
        }
    }

    private static void load() {
        if (cache != null) {
            return;
        }
        cache = new HashMap<>();
        if (storePath == null || !Files.exists(storePath)) {
            return;
        }
        try {
            Map<String, Map<String, String>> data = GSON.fromJson(
                    Files.readString(storePath),
                    new TypeToken<Map<String, Map<String, String>>>() { }.getType());
            if (data != null) {
                cache.putAll(data);
            }
        } catch (IOException | JsonSyntaxException e) {
            MultiverseServer.LOGGER.warn("Could not read dimension fingerprints ({}) — starting fresh", storePath, e);
        }
    }

    private static void save() {
        if (storePath == null) {
            return;
        }
        try {
            Files.createDirectories(storePath.getParent());
            Files.writeString(storePath, GSON.toJson(cache));
        } catch (IOException e) {
            MultiverseServer.LOGGER.warn("Could not write dimension fingerprints to {}", storePath, e);
        }
    }
}
