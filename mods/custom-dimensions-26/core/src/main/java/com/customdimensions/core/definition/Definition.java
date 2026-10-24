package com.customdimensions.core.definition;

import com.customdimensions.core.BlockPos;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * A schema v2 dimension definition ({@code definitions/<slug>.json}). The
 * layers later work packages extend ({@code terrain}, {@code materials},
 * {@code zones}, {@code structures}, {@code difficulty}, {@code portals},
 * the exits and {@code roll}) stay JSON here until those packages type them.
 * Absent fields are null; {@code biomes}, {@code zones}, {@code portals} and
 * {@code wins} are empty lists when absent.
 */
public record Definition(
        int schemaVersion,
        String description,
        Seed seed,
        BlockPos spawn,
        Generator generator,
        JsonObject terrain,
        List<BiomeEntry> biomes,
        Liquids liquids,
        JsonObject materials,
        List<JsonObject> zones,
        DimensionTypeFields dimensionType,
        Atmosphere atmosphere,
        Rules rules,
        Borders borders,
        JsonObject difficulty,
        JsonObject structures,
        List<JsonObject> portals,
        JsonObject exitPortal,
        JsonObject exits,
        JsonObject exitShrines,
        Deaths deaths,
        List<Win> wins,
        JsonObject roll) {

    public static final int SCHEMA_VERSION = 2;

    /** The Gradle task that writes v2 definitions from the 1.21.1 ones. */
    public static final String CONVERTER_TASK = "./gradlew :compiler:convertDefinitions";

    /** Top-level keys in the order the converter writes them. */
    public static final List<String> FIELD_ORDER = List.of(
            "schemaVersion", "description", "seed", "spawn", "generator", "terrain", "biomes",
            "liquids", "materials", "zones", "dimensionType", "atmosphere", "rules", "borders",
            "difficulty", "structures", "portals", "exitPortal", "exits", "exitShrines", "deaths", "wins", "roll");

    public Definition {
        biomes = biomes == null ? List.of() : List.copyOf(biomes);
        zones = zones == null ? List.of() : List.copyOf(zones);
        portals = portals == null ? List.of() : List.copyOf(portals);
        wins = wins == null ? List.of() : List.copyOf(wins);
    }
}
