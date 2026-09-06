package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;

/**
 * The destination grid seen as a world, so vanilla's block renderer meshes it
 * exactly as it meshes a chunk.
 *
 * <p>Two answers are deliberately not the client's own: {@link #getLightLevel}
 * returns the DESTINATION's sky and block light, and {@link #getColor} returns
 * the destination biome's tint for the column the position stands in. Those are
 * the two things a fake-block projection can never get right, because a vanilla
 * client colours and lights whatever it believes it is standing in.
 *
 * <p>Face shading and the height limits come from the client's own world:
 * they are properties of the camera and the render, not of the far side.
 *
 * <p>The light LEVELS are the destination's own, carried unlifted: a target
 * that samples the source lightmap re-expresses them through {@link
 * AmbientLift} at the vertex, and one that samples no lightmap shades from
 * them directly. Time of day stays the source's.
 */
public final class ProjectionView implements BlockRenderView {

    private static final java.util.concurrent.atomic.AtomicInteger LIGHTING_READS =
            new java.util.concurrent.atomic.AtomicInteger();

    private final ClientProjection projection;
    private final ClientWorld world;
    private final float sourceAmbient;

    public ProjectionView(ClientProjection projection, ClientWorld world) {
        this.projection = projection;
        this.world = world;
        this.sourceAmbient = world.getDimension().ambientLight();
    }

    /** The ambient light the mesh was lifted against, for the emit line. */
    public float sourceAmbient() {
        return this.sourceAmbient;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.projection.stateAt(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return this.world.getHeight();
    }

    @Override
    public int getBottomY() {
        return this.world.getBottomY();
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded) {
        return this.world.getBrightness(direction, shaded);
    }

    /**
     * The SOURCE world's, which is wrong for every destination position — the
     * grid is what {@link #getLightLevel} answers from. Nothing in this mod
     * calls it and vanilla's block renderer is not seen to, but that is an
     * assumption about someone else's code, so it is counted rather than
     * trusted: {@code viewLightingReads} above zero means a caller took the
     * source world's lighting for a destination block.
     */
    @Override
    public LightingProvider getLightingProvider() {
        LIGHTING_READS.incrementAndGet();
        return this.world.getLightingProvider();
    }

    /** Monotonic: one read at any point in the session falsifies the assumption. */
    public static int lightingReads() {
        return LIGHTING_READS.get();
    }

    /**
     * The destination's own level, unlifted. The mesh carries what the
     * destination reported and each target decides what to do with it: the
     * shaded one expresses it as a source-lightmap level, the unshaded one
     * shades from it directly and would lose it to a saturated source.
     */
    @Override
    public int getLightLevel(LightType type, BlockPos pos) {
        int packed = this.projection.lightAt(pos.getX(), pos.getY(), pos.getZ());
        return type == LightType.SKY ? (packed >> 4) & 0xF : packed & 0xF;
    }

    @Override
    public int getColor(BlockPos pos, ColorResolver resolver) {
        int channel = channelOf(resolver);
        int tint = channel < 0 ? -1 : this.projection.tintAt(pos.getX(), pos.getZ(), channel);
        return tint >= 0 ? tint : this.world.getColor(pos, resolver);
    }

    private static int channelOf(ColorResolver resolver) {
        if (resolver == BiomeColors.GRASS_COLOR) {
            return CompanionPayloads.Projection.TINT_GRASS;
        }
        if (resolver == BiomeColors.FOLIAGE_COLOR) {
            return CompanionPayloads.Projection.TINT_FOLIAGE;
        }
        return resolver == BiomeColors.WATER_COLOR
                ? CompanionPayloads.Projection.TINT_WATER : -1;
    }
}
