package com.customdimensions.server.mixin;

import com.customdimensions.server.level.LevelSeeds;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A base world answers {@code getSeed()} with its own seed ({@link LevelSeeds}),
 * so its noise, biomes, structures, features and every other reader of the
 * level's seed follow its definition. Fantasy's levels override
 * {@code getSeed()} and never reach this. The constructor's structure check
 * and dragon fight read the level's seed instead of the world options', for
 * base and Fantasy levels alike.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelSeedMixin {
    @ModifyReturnValue(method = "getSeed", at = @At("RETURN"))
    private long customdimensions$ownSeed(long world) {
        Long own = LevelSeeds.baseSeedOrNull(((ServerLevel) (Object) this).dimension());
        return own != null ? own : world;
    }

    @ModifyExpressionValue(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/WorldOptions;seed()J"))
    private long customdimensions$levelSeed(long world) {
        return ((ServerLevel) (Object) this).getSeed();
    }
}
