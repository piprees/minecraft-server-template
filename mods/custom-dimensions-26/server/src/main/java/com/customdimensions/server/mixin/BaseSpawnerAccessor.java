package com.customdimensions.server.mixin;

import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A set piece's spawner takes its delay, range and count ({@code SetPiecePart}). */
@Mixin(BaseSpawner.class)
public interface BaseSpawnerAccessor {
    @Accessor("spawnDelay")
    void customdimensions$setSpawnDelay(int value);

    @Accessor("minSpawnDelay")
    void customdimensions$setMinSpawnDelay(int value);

    @Accessor("maxSpawnDelay")
    void customdimensions$setMaxSpawnDelay(int value);

    @Accessor("spawnRange")
    void customdimensions$setSpawnRange(int value);

    @Accessor("spawnCount")
    void customdimensions$setSpawnCount(int value);

    @Accessor("spawnDelay")
    int customdimensions$spawnDelay();

    @Accessor("spawnRange")
    int customdimensions$spawnRange();
}
