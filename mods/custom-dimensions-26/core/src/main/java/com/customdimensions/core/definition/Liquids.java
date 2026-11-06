package com.customdimensions.core.definition;

import java.util.List;

/**
 * Sea level, the lava level, the fluid that fills below sea level, the
 * aquifer mode and the basins an airborne terrain holds. Null fields keep the
 * generator's own.
 */
public record Liquids(Integer seaLevel, Integer lavaLevel, String defaultFluid, String aquifers, Basins basins) {

    /** {@code aquifers} values, in the order the schema lists them. */
    public static final List<String> AQUIFER_MODES = List.of("on", "off", "dry", "flooded");
    /** {@code basins} fields. */
    public static final List<String> BASINS_FIELDS = List.of("level", "fluid");

    /** Fluid that stands only inside a terrain's basins, up to {@code level}; null fields take the terrain's water line and water. */
    public record Basins(Integer level, String fluid) {}

    public Liquids(Integer seaLevel, Integer lavaLevel, String defaultFluid, String aquifers) {
        this(seaLevel, lavaLevel, defaultFluid, aquifers, null);
    }

    public Liquids(Integer seaLevel, String defaultFluid) {
        this(seaLevel, null, defaultFluid, null, null);
    }
}
