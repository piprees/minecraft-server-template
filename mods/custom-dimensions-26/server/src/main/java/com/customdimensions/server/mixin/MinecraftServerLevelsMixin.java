package com.customdimensions.server.mixin;

import com.customdimensions.server.level.LevelSeeds;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Each base world's biome manager takes the zoom seed of its own seed ({@link LevelSeeds}), the one its clients are sent. */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerLevelsMixin {
    @ModifyArg(method = "createLevels", index = 7, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;<init>(Lnet/minecraft/server/MinecraftServer;Ljava/util/concurrent/Executor;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/world/level/storage/ServerLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/dimension/LevelStem;ZJLjava/util/List;Z)V"))
    private long customdimensions$ownZoomSeed(MinecraftServer server, Executor executor, LevelStorageSource.LevelStorageAccess storage,
            ServerLevelData data, ResourceKey<Level> key, LevelStem stem, boolean debug, long zoom, List<CustomSpawner> spawners,
            boolean tickTime) {
        Long own = LevelSeeds.baseSeedOrNull(key);
        return own != null ? BiomeManager.obfuscateSeed(own) : zoom;
    }
}
