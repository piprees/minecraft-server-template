package com.customdimensions.mixin;

import net.minecraft.world.WorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to StructureAccessor's backing world, for the
 *  terrain-adaptation arm site (StructureWeightSamplerMixin). */
@Mixin(StructureAccessor.class)
public interface StructureAccessorAccessor {

    @Accessor("world")
    WorldAccess getWorld();
}
