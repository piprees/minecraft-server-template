package com.customdimensions.server.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * On respawn, {@code keep_inventory} is the rule of the dimension the player
 * died in, which decided whether their inventory dropped, not the respawn level's.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerRestoreMixin {
    @ModifyExpressionValue(method = "restoreFrom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getGameRules()Lnet/minecraft/world/level/gamerules/GameRules;"))
    private GameRules customdimensions$deathLevelRules(GameRules respawnLevel, @Local(argsOnly = true) ServerPlayer oldPlayer) {
        return oldPlayer.level().getGameRules();
    }
}
