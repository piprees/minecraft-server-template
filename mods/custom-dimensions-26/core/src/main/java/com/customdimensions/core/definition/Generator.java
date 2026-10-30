package com.customdimensions.core.definition;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * What builds the dimension's chunks. {@code preset} names the noise settings
 * for {@code noise}; {@code of} names the dimension a {@code clone} copies;
 * {@code cellScale} sizes {@code checkerboard} cells; {@code layers} and
 * {@code biome} describe a {@code flat} world, and {@code biome} alone a
 * {@code single_biome} one; {@code endIsland} applies to the {@code end} preset.
 */
public record Generator(String type, String preset, String of, Integer cellScale,
                        String biome, List<JsonObject> layers, Boolean endIsland) {

    public static final String NOISE = "noise";
    public static final String VOID = "void";
    public static final String FLAT = "flat";
    public static final String CHECKERBOARD = "checkerboard";
    public static final String SINGLE_BIOME = "single_biome";
    public static final String CLONE = "clone";

    public static final Set<String> TYPES = Set.of(NOISE, VOID, FLAT, CHECKERBOARD, SINGLE_BIOME, CLONE);

    public static final Set<String> PRESETS = Set.of(
            "overworld", "large_biomes", "amplified", "caves", "floating_islands", "nether", "end");

    public Generator {
        layers = layers == null ? null : List.copyOf(layers);
    }

    public static Generator noise(String preset) {
        return new Generator(NOISE, preset, null, null, null, null, null);
    }
}
