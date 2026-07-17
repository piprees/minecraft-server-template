package com.customdimensions.mixin;

import com.customdimensions.config.DimensionDefinition;
import com.customdimensions.config.MultiverseConfig;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpawnHelper.class)
public class MobSpawnMixin {
    @Inject(method = "spawnEntitiesInChunk(Lnet/minecraft/entity/SpawnGroup;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/SpawnHelper$Checker;Lnet/minecraft/world/SpawnHelper$Runner;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void onSpawnEntitiesInChunk(SpawnGroup group, ServerWorld world, WorldChunk chunk, SpawnHelper.Checker checker, SpawnHelper.Runner runner, CallbackInfo ci) {
        if (group != SpawnGroup.MONSTER) {
            return;
        }
        RegistryKey<World> worldKey = world.getRegistryKey();
        // Namespace guard: definitions are looked up by path, and a foreign
        // dimension whose path matches one of our names must not inherit its
        // spawn suppression.
        if (!DimensionDefinition.getNamespace().equals(worldKey.getValue().getNamespace())) {
            return;
        }
        DimensionDefinition def = com.customdimensions.dimension.DimensionManager.getInstance().resolveDefinition(worldKey.getValue().getPath());
        if (def != null && !def.isHostileSpawningEnabled()) {
            ci.cancel();
        }
    }
}
