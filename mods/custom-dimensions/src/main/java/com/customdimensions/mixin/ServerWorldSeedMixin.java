package com.customdimensions.mixin;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes per-dimension seeds real. Vanilla's ServerWorld.getSeed() returns
 * the SERVER seed (save properties) for every world — and that value is
 * what seeds NoiseConfig (terrain noise, aquifers, ore RNG), structure
 * placement, and feature generation. Without this mixin every custom
 * dimension generates as a clone of the main world regardless of the
 * seed stored on its DimensionConfig.
 *
 * Only worlds in the adventure: namespace with an explicit seed are
 * overridden; everything else falls through to vanilla.
 */
@Mixin(ServerWorld.class)
public class ServerWorldSeedMixin {
    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void customdimensions$perDimensionSeed(CallbackInfoReturnable<Long> cir) {
        ServerWorld world = (ServerWorld) (Object) this;
        RegistryKey<World> key = world.getRegistryKey();
        if (MultiverseConfig.getInstance().isManagedNamespace(key.getValue().getNamespace())) {
            DimensionConfig def = com.customdimensions.dimension.DimensionManager.getInstance().resolveDefinition(key.getValue().getPath());
            if (def != null && def.getSeed() != null) {
                cir.setReturnValue(def.getSeed());
            }
            return;
        }
        // Static worlds: the config drives every seed. Each base-world file
        // (overworld.json, the_nether.json, the_end.json, paradise_lost.json)
        // carries its own "seed" — "env" reads the SEED environment variable
        // (.env SEED otherwise only seeds level.dat as a legacy fallback).
        // Config loads at createWorlds HEAD, so overrides cover the very
        // first chunk.
        Long worldSeed = MultiverseConfig.getInstance().getWorldSeedOverride(key.getValue().toString());
        if (worldSeed != null) {
            cir.setReturnValue(worldSeed);
        }
    }
}
