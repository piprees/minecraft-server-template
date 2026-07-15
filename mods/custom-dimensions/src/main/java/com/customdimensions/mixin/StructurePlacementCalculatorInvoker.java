package com.customdimensions.mixin;

import java.util.List;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructureSet;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StructurePlacementCalculator.class)
public interface StructurePlacementCalculatorInvoker {
    // Mirrors the RegistryWrapper create() variant, which passes the structure
    // seed as BOTH structureSeed and concentricRingSeed. The public
    // Stream-based create() passes concentricRingSeed=0 (the flat-world path)
    // and would silently re-roll stronghold rings in every tuned dimension.
    @Invoker("<init>")
    static StructurePlacementCalculator invokeNew(NoiseConfig noiseConfig, BiomeSource biomeSource,
            long structureSeed, long concentricRingSeed,
            List<RegistryEntry<StructureSet>> structureSets) {
        throw new AssertionError("mixin invoker not applied");
    }
}
