package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Structure-set id -> theme classification (dungeon / settlement / maritime /
 * landmark / deco / loot), generated from mods/.ideas/customising-structures.csv
 * by scripts/gen-structure-presets.py and baked into the jar. Drives the
 * theme-aware parts of per-dimension structure control: peaceful dimensions
 * drop dungeon-theme sets, dense dimensions boost dungeon+loot, etc.
 * Unknown ids have no theme and are only affected by "none".
 *
 * Consumer extension: an optional config/structure_themes.json (same shape,
 * delivered via overlay/config/) is merged OVER the jar map at load, so
 * consumer-added structure mods can be themed too — their sets then respond
 * to structureDensity and the peaceful overlay like platform mods.
 */
public final class StructureThemes {
    private static Map<String, String> themes;

    private StructureThemes() {
    }

    public static synchronized String themeOf(String structureSetId) {
        if (themes == null) {
            themes = load();
        }
        return themes.get(structureSetId);
    }

    private static Map<String, String> load() {
        Map<String, String> map = new java.util.HashMap<>();
        try (InputStream in = StructureThemes.class.getResourceAsStream("/structure_themes.json")) {
            if (in == null) {
                MultiverseServer.LOGGER.warn("structure_themes.json missing from jar — theme-aware structure control disabled");
            } else {
                Map<String, String> baked = new Gson().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() {
                        }.getType());
                map.putAll(baked);
                MultiverseServer.LOGGER.info("Loaded {} structure-set themes", baked.size());
            }
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to load structure_themes.json", e);
        }
        // Consumer overlay rows win over the baked map.
        try {
            java.nio.file.Path extra = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("structure_themes.json");
            if (java.nio.file.Files.exists(extra)) {
                Map<String, String> overlay = new Gson().fromJson(
                        java.nio.file.Files.readString(extra),
                        new TypeToken<Map<String, String>>() {
                        }.getType());
                if (overlay != null && !overlay.isEmpty()) {
                    map.putAll(overlay);
                    MultiverseServer.LOGGER.info("Merged {} consumer structure-set themes from config/structure_themes.json",
                            overlay.size());
                }
            }
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to merge config/structure_themes.json — using baked themes only", e);
        }
        return map.isEmpty() ? Collections.emptyMap() : map;
    }
}
