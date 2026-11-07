package com.customdimensions.core.firewall;

import java.util.List;
import java.util.Set;

/**
 * The classes a mixin may not enter without the allow list or a bridge: the
 * terrain, biome placement, structure placement, chunk-status and dimension
 * code of Minecraft. Names are binary class names in either dotted or slashed form.
 */
public final class WorldgenClasses {
    /** Packages and class-name prefixes that generate the world. */
    public static final List<String> PREFIXES = List.of(
            "net.minecraft.world.level.levelgen.",
            "net.minecraft.world.level.biome.",
            "net.minecraft.world.level.chunk.ChunkGenerator",
            "net.minecraft.world.level.chunk.status.",
            "net.minecraft.world.level.dimension.",
            "net.minecraft.data.worldgen.");

    /**
     * Classes under {@link #PREFIXES} that run in a loaded world rather than
     * generate it: mob spawners, heightmap upkeep, a biome's weather and spawn
     * queries, the dragon fight. A biome's features and spawns are policed by
     * the biome check instead.
     */
    public static final Set<String> RUNTIME = Set.of(
            "net.minecraft.world.level.levelgen.PatrolSpawner",
            "net.minecraft.world.level.levelgen.PhantomSpawner",
            "net.minecraft.world.level.levelgen.Heightmap",
            "net.minecraft.world.level.biome.Biome",
            "net.minecraft.world.level.biome.MobSpawnSettings",
            "net.minecraft.world.level.dimension.end.EnderDragonFight");

    private WorldgenClasses() {}

    public static boolean isWorldgen(String className) {
        String name = className.replace('/', '.');
        String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
        if (RUNTIME.contains(outer)) return false;
        return PREFIXES.stream().anyMatch(name::startsWith);
    }
}
