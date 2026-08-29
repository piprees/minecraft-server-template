package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Structure-set id -> classification, generated from
 * scripts/data/structure-dials.json + structure-sets-extracted.json by
 * scripts/gen-structure-groups.py and baked into the jar as
 * structure_themes.json.
 *
 * Three fields per set:
 * - theme  (dungeon / settlement / maritime / landmark / deco / loot):
 *   drives the legacy density path — peaceful dimensions drop dungeon-theme
 *   sets, dense dimensions boost dungeon+loot.
 * - group  (deco / settlements / dungeons / landmarks / maritime / endgame /
 *   loot): the noise meta-group. One NoiseStructurePlacement is built per
 *   active group.
 * - rarity (common / uncommon / rare / endgame): the set's share of its
 *   group's noise band, and the per-dimension structures.rarity override
 *   target.
 *
 * Two on-disk shapes are accepted, per entry:
 * <pre>
 *   "ns:set": {"theme": "dungeon", "group": "dungeons", "rarity": "rare"}
 *   "ns:set": "dungeon"                        // legacy, theme only
 * </pre>
 * The bare-string form is what the consumer-overlay documentation has always
 * described, so it keeps working; group and rarity are then derived from the
 * theme (and left null for rarity, so callers fall back to inference).
 *
 * Consumer extension: an optional config/structure_themes.json (either shape,
 * delivered via overlay/config/) is merged OVER the jar map at load, so
 * consumer-added structure mods can be classified too.
 */
public final class StructureThemes {

    /** One classified structure set. Any field may be null when unknown. */
    public record Classification(String theme, String group, String rarity) {
    }

    private static Map<String, Classification> entries;

    private StructureThemes() {
    }

    public static synchronized String themeOf(String structureSetId) {
        Classification c = classificationOf(structureSetId);
        return c != null ? c.theme() : null;
    }

    public static synchronized Classification classificationOf(String structureSetId) {
        if (entries == null) {
            entries = load();
        }
        return entries.get(structureSetId);
    }

    /** Test seam: forces the next lookup to re-read from disk/jar. */
    static synchronized void reset() {
        entries = null;
    }

    /**
     * Theme -> group, for entries that carry only a theme (legacy overlay
     * rows). MIRRORED from THEME_TO_GROUP in scripts/gen-structure-groups.py.
     */
    static String groupForTheme(String theme) {
        if (theme == null) {
            return null;
        }
        return switch (theme) {
            case "deco", "ruins" -> "deco";
            case "settlement" -> "settlements";
            case "dungeon" -> "dungeons";
            case "landmark" -> "landmarks";
            case "maritime" -> "maritime";
            case "loot" -> "loot";
            default -> null;
        };
    }

    private static Map<String, Classification> load() {
        Map<String, Classification> map = new java.util.HashMap<>();
        try (InputStream in = StructureThemes.class.getResourceAsStream("/structure_themes.json")) {
            if (in == null) {
                MultiverseServer.LOGGER.warn(
                        "structure_themes.json missing from jar — structure classification disabled");
            } else {
                int n = merge(map, new Gson().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, JsonElement>>() {
                        }.getType()));
                MultiverseServer.LOGGER.info("Loaded {} structure-set classifications", n);
            }
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to load structure_themes.json", e);
        }
        // Consumer overlay rows win over the baked map.
        try {
            java.nio.file.Path extra = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("structure_themes.json");
            if (java.nio.file.Files.exists(extra)) {
                int n = merge(map, new Gson().fromJson(
                        com.customdimensions.config.DimensionConfigLoader.stripJsonComments(
                                java.nio.file.Files.readString(extra)),
                        new TypeToken<Map<String, JsonElement>>() {
                        }.getType()));
                if (n > 0) {
                    MultiverseServer.LOGGER.info(
                            "Merged {} consumer structure-set classifications from "
                            + "config/structure_themes.json", n);
                }
            }
        } catch (Exception e) {
            MultiverseServer.LOGGER.error(
                    "Failed to merge config/structure_themes.json — using baked classifications only", e);
        }
        return map.isEmpty() ? Collections.emptyMap() : map;
    }

    /** Parses either entry shape into the map. Returns the number merged. */
    private static int merge(Map<String, Classification> into, Map<String, JsonElement> raw) {
        if (raw == null) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
            Classification c = parseEntry(e.getKey(), e.getValue());
            if (c != null) {
                into.put(e.getKey(), c);
                n++;
            }
        }
        return n;
    }

    private static Classification parseEntry(String setId, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            // Legacy shape: a bare theme string. Group derives from it;
            // rarity is unknown and left to the caller's inference.
            String theme = value.getAsString();
            return new Classification(theme, groupForTheme(theme), null);
        }
        if (value.isJsonObject()) {
            JsonObject o = value.getAsJsonObject();
            String theme = optString(o, "theme");
            String group = optString(o, "group");
            String rarity = optString(o, "rarity");
            if (group == null) {
                group = groupForTheme(theme);
            }
            return new Classification(theme, group, rarity);
        }
        MultiverseServer.LOGGER.warn(
                "structure_themes.json entry {} is neither a string nor an object — ignored", setId);
        return null;
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        String s = e.getAsString();
        return s == null || s.isEmpty() ? null : s;
    }
}
