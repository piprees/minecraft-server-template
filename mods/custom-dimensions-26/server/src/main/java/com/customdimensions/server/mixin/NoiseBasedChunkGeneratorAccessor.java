package com.customdimensions.server.mixin;

import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

/** A level's global fluid rule, replaced by {@link com.customdimensions.server.level.WorldgenRuntime} for its lava level and dry zones. */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorAccessor {

    @Accessor("globalFluidPicker")
    Supplier<Aquifer.FluidPicker> customdimensions$globalFluidPicker();

    @Mutable
    @Accessor("globalFluidPicker")
    void customdimensions$setGlobalFluidPicker(Supplier<Aquifer.FluidPicker> picker);
}
