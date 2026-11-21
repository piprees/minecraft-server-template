package com.customdimensions.server.mixin;

import com.customdimensions.server.level.SavedLevel;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The saved position is the value of the first {@code orElse} in {@code start}. One whose level is
 * not open is dropped, so the player goes to the world spawn, never to its coordinates elsewhere.
 */
@Mixin(PrepareSpawnTask.class)
abstract class PrepareSpawnTaskMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private NameAndId nameAndId;

    @ModifyExpressionValue(method = "start", at = @At(value = "INVOKE",
            target = "Ljava/util/Optional;orElse(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 0))
    private Object customdimensions$placeable(Object saved) {
        return SavedLevel.placeable(server, nameAndId, (ServerPlayer.SavedPosition) saved);
    }
}
