package com.customdimensions.mixin;

import com.customdimensions.dimension.DifficultyManager;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-dimension mob difficulty (v4 Phase 2): after a mob finishes its
 * natural spawn initialisation, apply the dimension's attribute modifiers
 * (DimensionConfig.difficulty). initialize runs once per spawn and the
 * modifiers persist in NBT, so loaded entities are never re-scaled;
 * conversions that re-run initialize are handled idempotently
 * (remove-then-add by modifier id).
 */
@Mixin(MobEntity.class)
public class MobAttributeMixin {
    @Inject(method = "initialize", at = @At("TAIL"))
    private void customdimensions$applyDimensionDifficulty(CallbackInfoReturnable<?> cir) {
        DifficultyManager.applyMobModifiers((MobEntity) (Object) this);
    }
}
