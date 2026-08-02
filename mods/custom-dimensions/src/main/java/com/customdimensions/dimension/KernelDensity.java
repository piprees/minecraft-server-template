package com.customdimensions.dimension;

import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import java.util.List;

/**
 * Adds kernel density ON TOP of a chunk's final block-state density
 * function — the value the aquifer decides blocks from. Deliberately NOT
 * part of the beardifier: five mods transform StructureWeightSampler on
 * this platform (c2me, YungsApi, Moog's Structures, lithostitched, us) and
 * callbacks on its {@code sample} and its factory's RETURN are starved by
 * the interaction (live-verified 2026-08-02 — merged but never executed).
 * A top-level wrapper of an unknown type must be evaluated by any correct
 * optimiser; the delegate keeps every other mod's behaviour intact.
 *
 * Installed per chunk by ChunkNoiseSamplerMixin while the factory-HEAD
 * piece stash is set; instances are immutable and thread-safe.
 */
public final class KernelDensity implements DensityFunction {

    private static final CodecHolder<? extends DensityFunction> CODEC =
            CodecHolder.of(com.mojang.serialization.MapCodec.unit(
                    net.minecraft.world.gen.densityfunction.DensityFunctionTypes.zero()));

    /** Conservative widening of the delegate's bounds: a handful of
     *  overlapping pedestals at full strength. Bounds guide optimisers and
     *  must be wide, never tight. */
    private static final double BOUND_SLACK = 8.0;

    private final DensityFunction delegate;
    private final List<TerrainKernel.Piece> pieces;

    public KernelDensity(DensityFunction delegate, List<TerrainKernel.Piece> pieces) {
        this.delegate = delegate;
        this.pieces = pieces;
    }

    /** The delegate itself when there is nothing to add — kernel-free
     *  chunks keep their unwrapped fast path. */
    public static DensityFunction wrap(DensityFunction delegate,
                                       List<TerrainKernel.Piece> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return delegate;
        }
        return new KernelDensity(delegate, pieces);
    }

    @Override
    public double sample(NoisePos pos) {
        return this.delegate.sample(pos) + TerrainKernel.sampleAll(this.pieces, pos);
    }

    @Override
    public void fill(double[] densities, EachApplier applier) {
        this.delegate.fill(densities, applier);
        for (int i = 0; i < densities.length; i++) {
            densities[i] += TerrainKernel.sampleAll(this.pieces, applier.at(i));
        }
    }

    @Override
    public DensityFunction apply(DensityFunctionVisitor visitor) {
        // Stay on top through every rebuild — a visitor that dropped this
        // wrapper would silently delete the kernel term.
        return new KernelDensity(this.delegate.apply(visitor), this.pieces);
    }

    @Override
    public double minValue() {
        return this.delegate.minValue() - BOUND_SLACK;
    }

    @Override
    public double maxValue() {
        return this.delegate.maxValue() + BOUND_SLACK;
    }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return CODEC;
    }
}
