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
        try (InputStream in = StructureThemes.class.getResourceAsStream("/structure_themes.json")) {
            if (in == null) {
                MultiverseServer.LOGGER.warn("structure_themes.json missing from jar — theme-aware structure control disabled");
                return Collections.emptyMap();
            }
            Map<String, String> map = new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {
                    }.getType());
            MultiverseServer.LOGGER.info("Loaded {} structure-set themes", map.size());
            return map;
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to load structure_themes.json", e);
            return Collections.emptyMap();
        }
    }
}
