package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

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
 *
 * <p>A missing store file is only silent for a dimension that has never
 * generated a chunk — nothing has been baked yet, so there is nothing to
 * compare against. For a dimension whose world already exists on disk,
 * an absent fingerprint is worth a WARN naming what cannot be verified
 * (see {@link #checkExisting}): without it, a lost store file makes every
 * such dimension silently agree with whatever config is running now, and
 * a real drift never surfaces again.
 */
public final class DimensionFingerprints {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // The fields the mod stamps at creation time. "seed" is creation-time too,
    // but a re-roll is a deliberate act (see checkExisting) and is reported
    // separately from worldgen drift.
    private static final String[] WORLDGEN_FIELDS = {
            "type", "noiseSettings", "biomes", "checkerboardScale",
            "layers", "flatBiome", "settingsOverrides", "biomeParameters", "biomePatches",
            "structureWants", "structureShuns"
    };

    /**
     * Of {@link #WORLDGEN_FIELDS}, the ones NOT baked into level.dat. Wants and
     * shuns weight the noise pool, which is rebuilt from config every boot, so
     * drift in one reaches newly generated chunks on its own. Saying "wipe the
     * world" for those would send an operator to reset-seed for nothing.
     */
    private static final java.util.Set<String> NEW_CHUNKS_ONLY_FIELDS =
            java.util.Set.of("structureWants", "structureShuns");
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
            // A record written before a field joined this list knows nothing
            // about it. That is unknown, not changed — comparing anyway would
            // report drift on every dimension the first time the list grows.
            // {@link #checkExisting} adopts the current value instead.
            if (!stored.containsKey(field)) {
                continue;
            }
            if (!String.valueOf(stored.get(field)).equals(current.get(field))) {
                drifted.add(field);
            }
        }
        return drifted;
    }

    /**
     * Whether any drifted field is one baked into level.dat, so only a wipe can
     * apply it. False means every drifted field rebuilds from config at boot
     * and reaches newly generated chunks by itself.
     */
    static boolean needsWipe(List<String> drifted) {
        for (String field : drifted) {
            if (!NEW_CHUNKS_ONLY_FIELDS.contains(field)) {
                return true;
            }
        }
        return false;
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
        // A record that predates any of these keys is backfilled rather than
        // compared — see driftedFields.
        f.put("checkerboardScale", String.valueOf(def.getCheckerboardScale()));
        f.put("layers", String.valueOf(def.getLayersFingerprint()));
        f.put("flatBiome", String.valueOf(def.getFlatBiome()));
        f.put("settingsOverrides", String.valueOf(def.getSettingsOverridesFingerprint()));
        f.put("biomeParameters", String.valueOf(def.getBiomeParametersFingerprint()));
        f.put("biomePatches", String.valueOf(def.getBiomePatchesFingerprint()));
        // The RESOLVED ids, not the config block: aliases are not identity
        // ("fortress" is betterfortresses:fortress), so two configs naming one
        // structure two ways describe the same pool, and a band word or a
        // min/max never reaches the pool at all.
        f.put("structureWants", sortedIds(NoisePoolBuilder.wantedStructureIds(def)));
        f.put("structureShuns", sortedIds(NoisePoolBuilder.shunnedStructureIds(def)));
        return f;
    }

    /** A resolved id set in a stable order, so the record is comparable. */
    private static String sortedIds(java.util.Set<String> ids) {
        return String.valueOf(new java.util.TreeSet<>(ids));
    }

    /**
     * Every creation-time worldgen field of a config in one string, keys
     * sorted. A measurement's identity: two records carrying the same value
     * measured the same generator, and a differing one names the field that
     * moved rather than only asserting that something did.
     *
     * <p>Sorted rather than listed, so the order cannot drift when a field is
     * added and a new field is covered without a second place to update. Same
     * reason {@link #sortedIds} exists.
     *
     * <p>It carries {@code biomeParameters}, which is where the band default's
     * {@code |defaultOffset=} term lives — so a change to
     * {@link DimensionConfig#BAND_OFFSET_BASE} moves it, and a sweep of that
     * constant writes a different value into every record.
     */
    public static String canonical(DimensionConfig def) {
        if (def == null) {
            return "";
        }
        Map<String, String> f = fields(def);
        StringBuilder sb = new StringBuilder();
        for (String key : new java.util.TreeSet<>(f.keySet())) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(key).append('=').append(f.get(key));
        }
        return sb.toString();
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
     * Existing registry entry seen at boot: resolves whether this dimension's
     * world has already generated chunks, then defers to {@link
     * #checkExisting(DimensionConfig, boolean)}. Split so the decision core
     * is testable without a {@code MinecraftServer} — this harness cannot
     * construct one.
     */
    public static synchronized void checkExisting(DimensionConfig def, MinecraftServer server) {
        checkExisting(def, worldGenerated(def, server));
    }

    /**
     * Compare config vs creation-time fingerprint. Worldgen drift
     * (type/noiseSettings/biomes) warns; seed-only drift is an INFO. No
     * stored baseline adopts the current config as the new one to compare
     * against — silently for a dimension with no world on disk yet (nothing
     * was ever baked, so nothing was ever lost), but with a WARN for one
     * that already has generated chunks: the store was lost, not the
     * dimension, and every drift check before this boot is now
     * unrecoverable. Either way the baseline is adopted, so drift detection
     * resumes from this boot rather than nagging forever.
     */
    static synchronized void checkExisting(DimensionConfig def, boolean worldHasGeneratedChunks) {
        load();
        Map<String, String> current = fields(def);
        Map<String, String> stored = cache.get(def.getName());
        if (stored == null) {
            if (worldHasGeneratedChunks) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: no fingerprint on record, but its world already has "
                        + "generated chunks on disk — the fingerprints store was lost (deleted, or "
                        + "never carried over), so any worldgen drift before this boot cannot be "
                        + "verified. Adopting the current config as the new baseline from here.",
                        def.getName());
            }
            cache.put(def.getName(), current);
            save();
            return;
        }
        List<String> drifted = driftedFields(stored, current);
        // Adopt every field this record predates, so the next boot compares it
        // for real instead of skipping it forever.
        boolean backfilled = false;
        for (String field : WORLDGEN_FIELDS) {
            if (!stored.containsKey(field)) {
                stored.put(field, current.get(field));
                backfilled = true;
            }
        }
        if (backfilled) {
            save();
        }
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
            if (needsWipe(drifted)) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: worldgen config changed since this world was created ({}) — "
                        + "KEEPING the world as generated; worldgen changes never apply to existing "
                        + "dimensions. Regenerating requires a full world wipe (the generator is "
                        + "baked into level.dat).", def.getName(), detail);
            } else {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: structure wants/shuns changed since this world was created "
                        + "({}) — no wipe needed, the noise pool is rebuilt every boot, but chunks "
                        + "already generated keep the weighting they were built with.",
                        def.getName(), detail);
            }
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

    /**
     * Whether this dimension's world has ever generated and saved a chunk,
     * independent of whether it is currently loaded. A dimension can be
     * registered (present in level.dat's dimension registry, which is why
     * {@link #checkExisting} was reached at all) with nothing on disk yet —
     * created but never visited — and that case has nothing to lose, so it
     * stays silent.
     */
    private static boolean worldGenerated(DimensionConfig def, MinecraftServer server) {
        if (server == null) {
            return false;
        }
        return Files.isDirectory(
                regionDirFor(server.getSavePath(WorldSavePath.ROOT), def.getDimensionIdentifier()));
    }

    /**
     * The region directory vanilla creates once a dimension's first chunk
     * actually saves — pure path arithmetic over the save root, kept
     * separate from {@link #worldGenerated} so it is testable without a
     * {@code MinecraftServer}.
     */
    static Path regionDirFor(Path saveRoot, Identifier dimensionId) {
        return saveRoot.resolve("dimensions").resolve(dimensionId.getNamespace())
                .resolve(dimensionId.getPath()).resolve("region");
    }

    /** Test seam: the fingerprint currently on record for a dimension, or null. */
    static synchronized Map<String, String> storedFieldsFor(String name) {
        load();
        return cache.get(name);
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
