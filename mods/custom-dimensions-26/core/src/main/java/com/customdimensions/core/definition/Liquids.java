package com.customdimensions.core.definition;

import java.util.List;

/**
 * Sea level, the lava level, the fluid that fills below sea level and the
 * aquifer mode. Null fields keep the generator's own.
 */
public record Liquids(Integer seaLevel, Integer lavaLevel, String defaultFluid, String aquifers) {

    /** {@code aquifers} values, in the order the schema lists them. */
    public static final List<String> AQUIFER_MODES = List.of("on", "off", "dry", "flooded");

    public Liquids(Integer seaLevel, String defaultFluid) {
        this(seaLevel, null, defaultFluid, null);
    }
}
