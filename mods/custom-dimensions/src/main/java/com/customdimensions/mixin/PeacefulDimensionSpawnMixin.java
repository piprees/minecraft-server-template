package com.customdimensions.mixin;

import com.customdimensions.config.DimensionDefinition;
import com.customdimensions.config.MultiverseConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
public class PeacefulDimensionSpawnMixin {
    @Inject(method = "shouldCancelSpawn", at = @At("HEAD"), cancellable = true)
    private void onShouldCancelSpawn(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity == null || entity.getType().getSpawnGroup() != SpawnGroup.MONSTER) {
            return;
        }

        ServerWorld world = (ServerWorld) (Object) this;
        // Same namespace guard as MobSpawnMixin: path lookups must never
        // match foreign dimensions.
        if (!DimensionDefinition.getNamespace().equals(world.getRegistryKey().getValue().getNamespace())) {
            return;
        }
        DimensionDefinition def = com.customdimensions.dimension.DimensionManager.getInstance().resolveDefinition(world.getRegistryKey().getValue().getPath());
        if (def != null && !def.isHostileSpawningEnabled()) {
            cir.setReturnValue(true);
        }
    }
}
