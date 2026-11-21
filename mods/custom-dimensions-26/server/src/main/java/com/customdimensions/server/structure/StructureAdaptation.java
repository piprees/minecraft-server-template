package com.customdimensions.server.structure;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

/** The terrain adjustment the Beardifier uses for a structure, read through the structure manager's level. */
public final class StructureAdaptation {

    private static final java.util.concurrent.atomic.AtomicLong OVERRIDDEN = new java.util.concurrent.atomic.AtomicLong();

    private StructureAdaptation() {
    }

    public static TerrainAdjustment of(StructureManager manager, Structure structure) {
        LevelAccessor level = StructureReflection.level(manager);
        ResourceKey<Level> key = level instanceof WorldGenLevel w ? w.getLevel().dimension()
                : level instanceof Level l ? l.dimension() : null;
        TerrainAdjustment t = StructureRuntime.adaptation(key, structure);
        if (t != structure.terrainAdaptation()) {
            OVERRIDDEN.incrementAndGet();
        }
        return t;
    }

    /** How many times the Beardifier was handed an adjustment other than the structure's own. */
    public static long overridden() {
        return OVERRIDDEN.get();
    }
}
