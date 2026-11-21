package com.customdimensions.compiler.terrain;

import java.util.List;

/** One tunable number of a library terrain: its range, its default, what it does, and whether it counts whole things. */
public record TerrainParam(String name, double min, double max, double defaultValue, String description, boolean whole) {

    /** The land parameters every library terrain shares. */
    public static final List<TerrainParam> SHARED = List.of(
            new TerrainParam("coast", -0.6, 0.4, -0.15, "Continentalness at the shoreline; higher is more ocean"),
            new TerrainParam("seaFloor", -40, 60, 36, "Height of the open ocean floor"),
            new TerrainParam("landHeight", 0, 200, 80, "Height of the land far inland"),
            new TerrainParam("relief", 0, 120, 24, "Blocks of hills where erosion is low"),
            new TerrainParam("roughness", 0, 1, 0.5, "How far 3D noise breaks the surface; 0 smooth, 1 overhangs"));

    /** The layers a terrain over a void stacks above its own: plain floating islands, each tier smaller than the one under it. */
    public static final List<TerrainParam> TIERS = List.of(
            count("tiers", 1, 4, 1, "Island layers, counting the terrain's own: each further tier floats tierSpacing above the one under it"),
            new TerrainParam("tierSpacing", 32, 200, 64, "Blocks from one tier's altitude to the next tier's"),
            new TerrainParam("tierGap", 16, 64, 20, "Blocks of open sky always left between a tier's island tops and the undersides of the tier above"),
            new TerrainParam("tierShrink", 0.25, 1, 0.6, "Each tier's island size, thickness and underside against the tier under it"),
            new TerrainParam("tierCoverage", 0, 0.8, 0, "Share of the sky each further tier covers; 0 takes the coverage under it times tierShrink"));

    public TerrainParam {
        if (!(min <= defaultValue && defaultValue <= max)) {
            throw new IllegalArgumentException(name + ": default " + defaultValue + " outside [" + min + ", " + max + "]");
        }
    }

    public TerrainParam(String name, double min, double max, double defaultValue, String description) {
        this(name, min, max, defaultValue, description, false);
    }

    /** A parameter that takes whole numbers only: a count. */
    static TerrainParam count(String name, int min, int max, int defaultValue, String description) {
        return new TerrainParam(name, min, max, defaultValue, description, true);
    }

    /** The same parameter with another default (a family's own starting point for a shared parameter). */
    TerrainParam withDefault(double value) {
        return new TerrainParam(name, min, max, value, description, whole);
    }
}
