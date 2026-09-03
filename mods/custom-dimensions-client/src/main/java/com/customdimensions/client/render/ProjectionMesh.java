package com.customdimensions.client.render;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The destination volume meshed once, in the volume's own coordinate space.
 *
 * <p>Built through vanilla's own block renderer against {@link ProjectionView},
 * so a stair is a stair and a leaf block is leaves — the geometry is whatever
 * the client would draw if it were standing there. Interior faces cull against
 * the grid's own neighbours, so a solid destination costs its shell, not its
 * volume.
 *
 * <p>Camera-independent by construction: the clip against the portal opening
 * happens per frame, downstream of this.
 */
public final class ProjectionMesh {

    /** One render layer's captured quads, {@link QuadCapture#STRIDE} per vertex. */
    public record Layer(RenderLayer layer, float[] data, int floats) {}

    private final List<Layer> layers;
    private final int quads;

    private ProjectionMesh(List<Layer> layers, int quads) {
        this.layers = layers;
        this.quads = quads;
    }

    public List<Layer> layers() {
        return this.layers;
    }

    public int quads() {
        return this.quads;
    }

    static ProjectionMesh build(ClientProjection projection) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return new ProjectionMesh(List.of(), 0);
        }
        BlockRenderManager blocks = client.getBlockRenderManager();
        ProjectionView view = new ProjectionView(projection, client.world);
        MatrixStack matrices = new MatrixStack();
        Random random = Random.create();
        BlockPos origin = projection.origin();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Map<RenderLayer, QuadCapture> captures = new LinkedHashMap<>();

        for (int lx = 0; lx < projection.sizeX(); lx++) {
            for (int ly = 0; ly < projection.sizeY(); ly++) {
                for (int lz = 0; lz < projection.sizeZ(); lz++) {
                    int x = origin.getX() + lx;
                    int y = origin.getY() + ly;
                    int z = origin.getZ() + lz;
                    BlockState state = projection.stateAt(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    pos.set(x, y, z);

                    if (state.getRenderType() == BlockRenderType.MODEL) {
                        QuadCapture capture = captures.computeIfAbsent(
                                RenderLayers.getBlockLayer(state), layer -> new QuadCapture());
                        capture.setOffset(0.0f, 0.0f, 0.0f);
                        matrices.push();
                        matrices.translate(lx, ly, lz);
                        blocks.renderBlock(state, pos, view, matrices, capture, true, random);
                        matrices.pop();
                    }

                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        QuadCapture capture = captures.computeIfAbsent(
                                RenderLayers.getFluidLayer(fluid), layer -> new QuadCapture());
                        // FluidRenderer writes at chunk-relative coordinates
                        // and takes no matrix, so the section corner is added
                        // back here to land the quads in volume space.
                        capture.setOffset(
                                (x & ~15) - origin.getX(),
                                (y & ~15) - origin.getY(),
                                (z & ~15) - origin.getZ());
                        blocks.renderFluid(pos, view, capture, state, fluid);
                        capture.setOffset(0.0f, 0.0f, 0.0f);
                    }
                }
            }
        }

        List<Layer> layers = new ArrayList<>(captures.size());
        int quads = 0;
        for (Map.Entry<RenderLayer, QuadCapture> entry : captures.entrySet()) {
            QuadCapture capture = entry.getValue();
            capture.finish();
            if (capture.floatCount() == 0) {
                continue;
            }
            layers.add(new Layer(entry.getKey(), capture.data(), capture.floatCount()));
            quads += capture.quadCount();
        }
        return new ProjectionMesh(layers, quads);
    }
}
