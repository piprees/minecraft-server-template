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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creation-time worldgen fingerprints (config/custom-dimensions-fingerprints.json,
 * sibling of portal_links.json). A dimension's generator is baked into
 * level.dat at creation and NEVER re-read from config — registerDimensions
 * skips keys already in the registry, and vanilla re-persists the stored
 * generator on every save. Deleting a dimension's region files (or even
 * `customdim destroy`) does not touch that entry, so a config type/noise/
 * biome change silently produces a world that no longer matches its config
 * (see D2).
 *
 * Policy: NEVER delete or regenerate someone's world because the config
 * changed. Warn and keep the world as generated; regeneration is an
 * operator decision (full world wipe). Seed-only drift logs at INFO —
 * the seed roller re-pins winner seeds constantly, and a seed change is
 * routine tuning rather than a structural mismatch.
 */
public final class DimensionFingerprints {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // The fields the mod stamps at creation time. "seed" is creation-time too,
    // but a re-roll is a deliberate act (see checkExisting) and is reported
    // separately from worldgen drift.
    private static final String[] WORLDGEN_FIELDS = {
            "type", "noiseSettings", "biomes", "checkerboardScale",
            "layers", "flatBiome", "settingsOverrides", "biomeParameters", "biomePatches"
    };
    private static Map<String, Map<String, String>> cache;
    private static Path storePath;

    private DimensionFingerprints() {
    }

    /**
     * The worldgen fields that differ between a stored fingerprint and a
     * dimension's current config fields. Pure and boot-independent — the
     * comparison a checker would run offline, kept in the mod instead.
     */
    public static List<String> driftedFields(Map<String, String> stored, Map<String, String> current) {
        List<String> drifted = new ArrayList<>();
        for (String field : WORLDGEN_FIELDS) {
            if (!String.valueOf(stored.get(field)).equals(current.get(field))) {
                drifted.add(field);
            }
        }
        return drifted;
    }

    /** Fingerprinted dimension names with no matching entry in {@code configuredNames}. */
    public static List<String> orphans(Collection<String> fingerprintedNames, Collection<String> configuredNames) {
        List<String> found = new ArrayList<>();
        for (String name : fingerprintedNames) {
            if (!configuredNames.contains(name)) {
                found.add(name);
            }
        }
        return found;
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
        List<String> drifted = driftedFields(stored, current);
        boolean seedDrift = !String.valueOf(stored.get("seed")).equals(current.get("seed"));
        if (!drifted.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (String field : drifted) {
                if (detail.length() > 0) {
                    detail.append(", ");
                }
                detail.append(field).append(": '").append(stored.get(field))
                        .append("' -> '").append(current.get(field)).append("'");
            }
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: worldgen config changed since this world was created ({}) — "
                    + "KEEPING the world as generated; worldgen changes never apply to existing "
                    + "dimensions. Regenerating requires a full world wipe (the generator is "
                    + "baked into level.dat).", def.getName(), detail);
        } else if (seedDrift) {
            MultiverseServer.LOGGER.info(
                    "Dimension {}: configured seed changed ({} -> {}) — existing world keeps its "
                    + "creation-time seed.", def.getName(), stored.get("seed"), current.get("seed"));
        }
    }

    /**
     * A fingerprinted world with no matching config entry: the config was
     * deleted (or renamed) but the world — and its level.dat entry — still
     * exists. Fingerprint-tone: WARN, never delete or unload (the mod's own
     * orphan reconciliation, driven by config presence, already handles
     * unloading; this only reports the fingerprint-level mismatch).
     */
    public static synchronized void warnOrphans(Collection<String> configuredNames) {
        load();
        for (String name : orphans(cache.keySet(), configuredNames)) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: fingerprint recorded but no config entry exists for it — the "
                    + "world still exists on disk with no config describing it.", name);
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
