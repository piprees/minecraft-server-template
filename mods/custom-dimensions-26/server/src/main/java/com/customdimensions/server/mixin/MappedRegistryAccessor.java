package com.customdimensions.server.mixin;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** A registry's lookups by value, kept in step by {@link com.customdimensions.server.level.RuntimeRegistry} when it binds an entry. */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor<T> {

    @Accessor("byValue")
    Map<T, Holder.Reference<T>> customdimensions$byValue();

    @Accessor("toId")
    Reference2IntMap<T> customdimensions$toId();
}
