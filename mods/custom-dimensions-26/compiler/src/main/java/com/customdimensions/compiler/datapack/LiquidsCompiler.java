package com.customdimensions.compiler.datapack;

import com.customdimensions.compiler.terrain.TerrainFamily;
import com.customdimensions.compiler.terrain.TerrainSpec;
import com.customdimensions.core.definition.Liquids;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * {@code liquids} onto a preset's noise settings. 26.3 holds one global fluid
 * rule per generator: open cells below {@code sea_level} take
 * {@code default_fluid}, and open cells below the lower of -54 and sea level
 * take lava. The noise-based {@code aquifers} object, where present, gives
 * cells away from the surface their own fluid levels instead.
 *
 * <ul>
 *   <li>{@code on}: the preset's {@code aquifers} object; a preset without one is an error.</li>
 *   <li>{@code off}, {@code flooded}: no {@code aquifers} object, so every open cell below sea level
 *       holds the default fluid; {@code flooded} refuses air as that fluid.</li>
 *   <li>{@code dry}: no {@code aquifers} object and {@code default_fluid} air, so no open cell holds
 *       water; the runtime also removes {@link #DRY_REMOVED_FEATURES}.</li>
 * </ul>
 *
 * {@code lavaLevel} has no 26.3 data field: the runtime puts it in place of
 * the generator's -54 ({@link RuntimeWorldgen}). Nor has {@code basins}: the
 * runtime's fluid rule answers its fluid inside the terrain's basins, above
 * the basin floor and below its level, and the global rule everywhere else.
 */
public final class LiquidsCompiler {

    public static final String AIR = "minecraft:air";
    /** Placed features that put water into a dry dimension: springs, and dripstone clusters' wet pools and waterlogged tips. */
    public static final List<String> DRY_REMOVED_FEATURES = List.of("minecraft:spring_water", "minecraft:dripstone_cluster");
    /** The generator's own lava level, {@code NoiseBasedChunkGenerator}'s constant. */
    public static final int VANILLA_LAVA_LEVEL = -54;

    private LiquidsCompiler() {
    }

    /** Whether {@code liquids} changes the noise settings file. */
    static boolean changesSettings(Liquids liquids) {
        return liquids != null && (liquids.seaLevel() != null || liquids.defaultFluid() != null || liquids.aquifers() != null);
    }

    /**
     * Refuses an unknown aquifer mode, a contradictory fluid, a lava level
     * outside the dimension type's {@code min_y} to {@code min_y + height}, and
     * a sea level above it. A sea level below {@code min_y} leaves the
     * dimension without a sea; it is returned as a note for the report.
     */
    static String validate(String slug, Liquids liquids, JsonObject dimensionType) {
        if (liquids == null) {
            return null;
        }
        String mode = liquids.aquifers();
        if (mode != null && !Liquids.AQUIFER_MODES.contains(mode)) {
            throw error(slug, "aquifers", "`" + mode + "` is not one of " + String.join(", ", Liquids.AQUIFER_MODES));
        }
        if ("dry".equals(mode) && liquids.defaultFluid() != null && !AIR.equals(liquids.defaultFluid())) {
            throw error(slug, "defaultFluid", "`" + liquids.defaultFluid() + "` contradicts `aquifers: dry`, which makes it air");
        }
        if ("flooded".equals(mode) && AIR.equals(liquids.defaultFluid())) {
            throw error(slug, "defaultFluid", "`flooded` needs a fluid; air floods nothing");
        }
        int minY = dimensionType.get("min_y").getAsInt();
        int top = minY + dimensionType.get("height").getAsInt();
        checkRange(slug, "lavaLevel", liquids.lavaLevel(), minY, top);
        if (liquids.seaLevel() != null && liquids.seaLevel() < minY) {
            return "`liquids.seaLevel` " + liquids.seaLevel() + " is below the dimension type's floor, y " + minY
                    + ", so no open cell is below sea level and the dimension has no sea";
        }
        checkRange(slug, "seaLevel", liquids.seaLevel(), minY, top);
        return null;
    }

    /** Writes {@code liquids} into {@code settings}, a copy of the preset's noise settings. */
    static void apply(String slug, String preset, Liquids liquids, JsonObject settings) {
        if (liquids.seaLevel() != null) {
            settings.addProperty("sea_level", liquids.seaLevel());
        }
        if (liquids.defaultFluid() != null) {
            settings.addProperty("default_fluid", liquids.defaultFluid());
        }
        String mode = liquids.aquifers();
        if (mode == null) {
            return;
        }
        switch (mode) {
            case "on" -> {
                if (!settings.has("aquifers")) {
                    throw error(slug, "aquifers", "`on` keeps the preset's aquifers, and the `" + preset
                            + "` noise settings have none in 26.3 (only overworld, large_biomes and amplified do)");
                }
            }
            case "off" -> settings.remove("aquifers");
            case "flooded" -> {
                settings.remove("aquifers");
                if (AIR.equals(settings.get("default_fluid").getAsString())) {
                    throw error(slug, "aquifers", "`flooded` needs a fluid, and the `" + preset
                            + "` preset's default fluid is air; set `liquids.defaultFluid`");
                }
            }
            case "dry" -> {
                settings.remove("aquifers");
                settings.addProperty("default_fluid", AIR);
            }
            default -> throw error(slug, "aquifers", "`" + mode + "` is not one of " + String.join(", ", Liquids.AQUIFER_MODES));
        }
    }

    public static final String WATER = "minecraft:water";

    /** {@code liquids.basins} resolved against the terrain: its level, its fluid and the terrain's basin and floor functions. */
    public record Basins(int level, String fluid, String basin, String floor) {}

    /**
     * {@code liquids.basins} against the definition's terrain, or null when it has none. Basins
     * need a terrain over a void that shapes them ({@link TerrainFamily#shapesBasins}): a terrain
     * on the ground holds its water with sea level and aquifers, and a terrain with no basins has
     * nothing to fill. The level lies above the basin floor and no higher than the rim.
     *
     * @param prefix the terrain's function prefix, {@code <namespace>:terrain/<slug>}
     */
    static Basins basins(String slug, Liquids liquids, TerrainSpec terrain, String prefix) {
        if (liquids == null || liquids.basins() == null) {
            return null;
        }
        if (terrain == null) {
            throw error(slug, "basins", "needs a terrain that shapes basins (`basin_islands`); the definition has no `terrain.base`");
        }
        TerrainFamily family = terrain.family();
        if (!family.airborne()) {
            throw error(slug, "basins", "`" + family.id() + "` lies on the ground, where sea level and the aquifers hold the water; "
                    + "basins are for a terrain over a void (`basin_islands`)");
        }
        if (!family.shapesBasins()) {
            throw error(slug, "basins", "`" + family.id() + "` is over a void but shapes no basins; use `basin_islands`");
        }
        double line = terrain.param("altitude");
        int floor = (int) Math.floor(line - terrain.param("basinDepth")) + 1;
        int rim = (int) Math.floor(line + terrain.param("rimHeight"));
        Integer asked = liquids.basins().level();
        int level = asked != null ? asked : (int) Math.round(line);
        if (level < floor || level > rim) {
            throw error(slug, "basins.level", level + " is outside the basins of `" + family.id() + "`, from the floor at y " + floor
                    + " to the rim at y " + rim);
        }
        String fluid = liquids.basins().fluid() != null ? liquids.basins().fluid() : WATER;
        if (AIR.equals(fluid)) {
            throw error(slug, "basins.fluid", "air fills nothing");
        }
        return new Basins(level, fluid, prefix + "/" + TerrainFamily.BASIN, prefix + "/" + TerrainFamily.BASIN_FLOOR);
    }

    /** The runtime lava level: the definition's, or null to keep the generator's. */
    static Integer lavaLevel(Liquids liquids) {
        return liquids == null ? null : liquids.lavaLevel();
    }

    /** Placed features the runtime removes for the aquifer mode. */
    static List<String> removedFeatures(Liquids liquids) {
        return liquids != null && "dry".equals(liquids.aquifers()) ? DRY_REMOVED_FEATURES : List.of();
    }

    private static void checkRange(String slug, String field, Integer value, int minY, int top) {
        if (value != null && (value < minY || value > top)) {
            throw error(slug, field, value + " is outside the dimension type's range, y " + minY + " to " + top);
        }
    }

    static IllegalArgumentException error(String slug, String field, String message) {
        return new IllegalArgumentException(slug + ": liquids." + field + ": " + message);
    }
}
