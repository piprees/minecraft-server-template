package com.customdimensions.mixin;

import com.customdimensions.dimension.TerrainAdaptationOverride;
import net.minecraft.world.gen.StructureTerrainAdaptation;
import net.minecraft.world.gen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The read half of the terrain-adaptation override: while
 * {@link StructureWeightSamplerMixin} has armed a dimension's map, the
 * registry getter answers the RESOLVED adaptation instead. Unarmed threads
 * (every other vanilla caller) pay one ThreadLocal read and keep the
 * registry value.
 */
@Mixin(Structure.class)
public abstract class StructureTerrainAdaptationMixin {

    @Inject(method = "getTerrainAdaptation", at = @At("RETURN"), cancellable = true)
    private void customdimensions$overrideTerrainAdaptation(
            CallbackInfoReturnable<StructureTerrainAdaptation> cir) {
        StructureTerrainAdaptation override =
                TerrainAdaptationOverride.armedOverride((Structure) (Object) this);
        if (override != null && override != cir.getReturnValue()) {
            cir.setReturnValue(override);
        }
    }
}
