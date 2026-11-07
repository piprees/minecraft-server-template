package com.customdimensions.compiler.terrain;

import java.util.List;

/** One tunable number of a library terrain: its range, its default and what it does. */
public record TerrainParam(String name, double min, double max, double defaultValue, String description) {

    /** The land parameters every library terrain shares. */
    public static final List<TerrainParam> SHARED = List.of(
            new TerrainParam("coast", -0.6, 0.4, -0.15, "Continentalness at the shoreline; higher is more ocean"),
            new TerrainParam("seaFloor", -40, 60, 36, "Height of the open ocean floor"),
            new TerrainParam("landHeight", 64, 200, 80, "Height of the land far inland"),
            new TerrainParam("relief", 0, 120, 24, "Blocks of hills where erosion is low"),
            new TerrainParam("roughness", 0, 1, 0.5, "How far 3D noise breaks the surface; 0 smooth, 1 overhangs"));

    public TerrainParam {
        if (!(min <= defaultValue && defaultValue <= max)) {
            throw new IllegalArgumentException(name + ": default " + defaultValue + " outside [" + min + ", " + max + "]");
        }
    }

    /** The same parameter with another default (a family's own starting point for a shared parameter). */
    TerrainParam withDefault(double value) {
        return new TerrainParam(name, min, max, value, description);
    }
}
