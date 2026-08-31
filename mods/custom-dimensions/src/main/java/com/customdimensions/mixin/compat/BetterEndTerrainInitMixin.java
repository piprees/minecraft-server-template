package com.customdimensions.mixin.compat;

import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets BetterEnd stand down when the End's biome source is this mod's.
 *
 * <p>{@code TerrainGenerator.onServerLevelInit} already decides correctly: a
 * source that is not a {@code WoverEndBiomeSource} gets
 * {@code be_setTarget(false)}, and BetterEnd's density hook then returns
 * early, leaving End terrain to the generator that owns it. It goes on to
 * call {@code initNoise} unconditionally, which throws
 * {@code IllegalStateException: Biome source config is not set} because the
 * config it needs is only ever set from a Wover source — so the decision to
 * stand down crashes the boot instead of taking effect.
 *
 * <p>Cancelling on exactly the condition BetterEnd itself tests keeps its
 * behaviour identical wherever it IS the target, and the island noise it
 * would build is read only through the hook that has already switched off.
 *
 * <p>Descriptors are intermediary and unremapped: the target belongs to
 * another mod, so nothing here goes through the refmap.
 */
@Pseudo
@Mixin(targets = "org.betterx.betterend.world.generator.TerrainGenerator", remap = false)
public class BetterEndTerrainInitMixin {

    private static final String WOVER_END_SOURCE =
            "org.betterx.wover.generator.impl.biomesource.end.WoverEndBiomeSource";

    @Inject(
            method = "initNoise(JLnet/minecraft/class_1966;Lnet/minecraft/class_6544$class_6552;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1)
    private static void customdimensions$standDownForForeignEndSource(
            long seed, BiomeSource source, MultiNoiseUtil.MultiNoiseSampler sampler, CallbackInfo ci) {
        if (!isWoverEndSource(source)) {
            ci.cancel();
        }
    }

    /** By name: the Wover classes are not on this mod's compile classpath. */
    private static boolean isWoverEndSource(BiomeSource source) {
        for (Class<?> c = source == null ? null : source.getClass(); c != null; c = c.getSuperclass()) {
            if (WOVER_END_SOURCE.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
