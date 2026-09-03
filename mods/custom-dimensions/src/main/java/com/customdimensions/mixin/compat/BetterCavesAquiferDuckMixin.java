package com.customdimensions.mixin.compat;

import net.minecraft.world.gen.chunk.AquiferSampler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Brings Better Caves' duck interface to every aquifer sampler (T79).
 *
 * <p>{@code MasterController.carve} casts its sampler to
 * {@code ILiquidRegionsProvider} unconditionally, and Better Caves' own
 * {@code AquiferMixin} mixes that interface into {@link AquiferSampler.Impl}
 * alone. A dimension whose noise settings set {@code aquifers_enabled: false}
 * is given the anonymous sea-level sampler instead, so the cast throws on
 * every carved chunk — and vanilla carves over a 17x17 neighbourhood, so one
 * such dimension poisons its neighbours too.
 *
 * <p>The body is empty because the interface and its method cannot be written
 * here: both are Better Caves types and this mod compiles against neither.
 * {@link CompatMixinPlugin#preApply} adds them to the target instead, and this
 * mixin is what brings {@code preApply} to {@link AquiferSampler} — it must
 * stay listed in {@code customdimensions.compat.mixins.json} and mapped to
 * {@code bettercaves} in the plugin, or nothing happens.
 *
 * <p>{@code Impl} is untouched: its own concrete method beats an interface
 * default, so the overworld keeps its liquid regions.
 */
@Mixin(AquiferSampler.class)
public interface BetterCavesAquiferDuckMixin {
}
