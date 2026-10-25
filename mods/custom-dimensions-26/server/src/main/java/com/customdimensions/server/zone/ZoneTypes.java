package com.customdimensions.server.zone;

import com.customdimensions.server.CustomDimensions;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Registers our biome source and density-function types, which the compiled pack names. */
public final class ZoneTypes {
    private ZoneTypes() {}

    public static void register() {
        Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath(CustomDimensions.ID, "zoned"), ZonedBiomeSource.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Identifier.fromNamespaceAndPath(CustomDimensions.ID, "zone_distance"),
                ZoneDistanceFunction.CODEC);
    }
}
