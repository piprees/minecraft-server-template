package com.customdimensions.server.mixin;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A registry entry's value, bound by {@link com.customdimensions.server.level.RuntimeRegistry} to its compiled form. */
@Mixin(Holder.Reference.class)
public interface HolderReferenceAccessor {

    @Invoker("bindValue")
    void customdimensions$bindValue(Object value);
}
