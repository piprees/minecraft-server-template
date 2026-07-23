package com.customdimensions.mixin;

import com.customdimensions.dimension.ExitConditions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "respawnAt" exit rules: a one-shot respawn override queued at death by
 * ExitConditions.onDeath. Only intercepts the alive=false path (an actual
 * respawn) — alive=true calls are the mod's own bed-exit resolution
 * (EntityTickPortalMixin) and must never consume the pending entry.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class PlayerRespawnRedirectMixin {

    @Inject(method = "getRespawnTarget", at = @At("HEAD"), cancellable = true)
    private void customdimensions$redirectRespawn(boolean alive,
            TeleportTarget.PostDimensionTransition postDimensionTransition,
            CallbackInfoReturnable<TeleportTarget> cir) {
        if (alive) {
            return;
        }
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        TeleportTarget override = ExitConditions.consumePendingRespawn(self);
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
