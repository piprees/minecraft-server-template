package com.customdimensions.server.mixin;

import com.customdimensions.server.level.LevelClientRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After vanilla tells every client the store's new value of a client-held rule,
 * each player is told their own level's value ({@link LevelClientRules}).
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerGameRulesMixin {
    @Inject(method = "onGameRuleChanged", at = @At("TAIL"))
    private void customdimensions$levelValues(GameRule<?> rule, Object value, CallbackInfo ci) {
        LevelClientRules.afterServerWideChange(rule, ((MinecraftServer) (Object) this).getPlayerList().getPlayers());
    }
}
