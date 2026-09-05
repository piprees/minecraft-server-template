package com.customdimensions.client.render;

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
 * the destination biome's tint. Those are the two things a fake-block
 * projection can never get right, because a vanilla client colours and lights
 * whatever it believes it is standing in.
 *
 * <p>Face shading and the height limits come from the client's own world:
 * they are properties of the camera and the render, not of the far side.
 *
 * <p>The light LEVELS are the destination's and the lightmap that turns a level
 * into a colour is the client's own, so {@link AmbientLift} re-expresses each
 * level as the source level that shades to the destination's own brightness.
 * Time of day stays the source's. A cell outside the grid reads as level 0.
 */
public final class ProjectionView implements BlockRenderView {

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

    /** Never consulted: {@link #getLightLevel} answers from the grid instead. */
    @Override
    public LightingProvider getLightingProvider() {
        return this.world.getLightingProvider();
    }

    @Override
    public int getLightLevel(LightType type, BlockPos pos) {
        int packed = this.projection.lightAt(pos.getX(), pos.getY(), pos.getZ());
        int level = type == LightType.SKY ? (packed >> 4) & 0xF : packed & 0xF;
        return AmbientLift.lift(level, this.projection.payload().ambientLight(),
                this.sourceAmbient);
    }

    @Override
    public int getColor(BlockPos pos, ColorResolver resolver) {
        int tint = -1;
        if (resolver == BiomeColors.GRASS_COLOR) {
            tint = this.projection.payload().grassColor();
        } else if (resolver == BiomeColors.FOLIAGE_COLOR) {
            tint = this.projection.payload().foliageColor();
        } else if (resolver == BiomeColors.WATER_COLOR) {
            tint = this.projection.payload().waterColor();
        }
        return tint >= 0 ? tint : this.world.getColor(pos, resolver);
    }
}
