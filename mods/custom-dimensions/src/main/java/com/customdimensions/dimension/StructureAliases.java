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
 * Short want/shun names to structure ids — {@code "fortress"} to
 * {@code "minecraft:fortress"}, {@code "wizard_tower"} to
 * {@code "structory_towers:wizard_tower"}.
 *
 * <p>Dimension configs name wanted structures by a short, human name; the
 * registry knows only ids. Nothing in the mod needed the mapping until lint
 * did, so it lived in the seed roller. It is baked into the jar as
 * {@code structure_aliases.json} for the same reason the structure themes are:
 * a config-shaped answer that must not depend on a file the server may not
 * have.
 *
 * <p>A name containing {@code :} is already an id and passes through. A name
 * that is neither an alias nor an id resolves to null, which lint reports
 * rather than guessing at — a wrong guess here is a want that silently scores
 * zero forever, which is the exact class of fault lint exists to end.
 *
 * <p>Values may be tag references ({@code "#minecraft:village"}); the caller
 * decides what to do with those, because a tag is a set of structures and not
 * one structure.
 *
 * <p>Consumer extension: {@code config/structure_aliases.json} is merged over
 * the baked map at load, so a consumer's own structure mods can be named.
 */
public final class StructureAliases {

    private static Map<String, String> aliases;

    private StructureAliases() {
    }

    /** The structure id (or {@code #tag}) a want name means, or null. */
    public static synchronized String resolve(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (aliases == null) {
            aliases = load();
        }
        String mapped = aliases.get(name);
        if (mapped != null) {
            return mapped;
        }
        return name.contains(":") ? name : null;
    }

    /** Every known short name, for lint's "did you mean" suggestions. */
    public static synchronized Map<String, String> all() {
        if (aliases == null) {
            aliases = load();
        }
        return aliases;
    }

    /** Test seam: forces the next lookup to re-read from disk/jar. */
    static synchronized void reset() {
        aliases = null;
    }

    private static Map<String, String> load() {
        Map<String, String> map = new java.util.HashMap<>();
        try (InputStream in = StructureAliases.class
                .getResourceAsStream("/structure_aliases.json")) {
            if (in == null) {
                MultiverseServer.LOGGER.warn(
                        "structure_aliases.json missing from jar — want names will "
                        + "only resolve when written as full structure ids");
            } else {
                Map<String, String> raw = new Gson().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() {
                        }.getType());
                if (raw != null) {
                    map.putAll(raw);
                }
            }
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to load structure_aliases.json", e);
        }
        try {
            java.nio.file.Path extra = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getConfigDir().resolve("structure_aliases.json");
            if (java.nio.file.Files.exists(extra)) {
                Map<String, String> raw = new Gson().fromJson(
                        com.customdimensions.config.DimensionConfigLoader.stripJsonComments(
                                java.nio.file.Files.readString(extra)),
                        new TypeToken<Map<String, String>>() {
                        }.getType());
                if (raw != null && !raw.isEmpty()) {
                    map.putAll(raw);
                    MultiverseServer.LOGGER.info(
                            "Merged {} consumer structure aliases from "
                            + "config/structure_aliases.json", raw.size());
                }
            }
        } catch (Exception e) {
            MultiverseServer.LOGGER.error(
                    "Failed to merge config/structure_aliases.json — using baked aliases only", e);
        }
        return map.isEmpty() ? Collections.emptyMap() : Map.copyOf(map);
    }
}
