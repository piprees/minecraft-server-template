package com.customdimensions.client.view;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

/**
 * The collector one fed entity's renderer submits into. Every model (the
 * body, its layers, armour: all of {@code submitModel}) and every custom
 * geometry becomes one {@code submitCustomGeometry} on the frame's own
 * collector in the same render type, whose callback renders it as
 * {@code ModelFeatureRenderer} does but through a {@link ClipConsumer}. The
 * rest (shadows, name tags, text, flame, leashes, items, blocks, outlines,
 * particles, gizmos) is not drawn in the opening and is counted in
 * {@link EntityPass.Frame#dropped}.
 */
final class ClippedCollector implements SubmitNodeCollector {
    private final SubmitNodeCollector root;
    private final OrderedSubmitNodeCollector target;
    private final PoseStack view;
    private final EntityPass.Frame frame;

    ClippedCollector(SubmitNodeCollector root, OrderedSubmitNodeCollector target, PoseStack view, EntityPass.Frame frame) {
        this.root = root;
        this.target = target;
        this.view = view;
        this.frame = frame;
    }

    private void geometry(RenderType type, Consumer<ClipConsumer> body) {
        target.submitCustomGeometry(view, type, (pose, vc) -> frame.render(pose, vc, body));
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return new ClippedCollector(root, root.order(order), view, frame);
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light,
                                int overlay, int tint, UvMapping uvMapping, int outlineColor) {
        if (renderType.isOutline()) {
            frame.dropped++;
            return;
        }
        PoseStack.Pose modelPose = poseStack.last().copy();
        geometry(renderType, c -> {
            PoseStack ps = new PoseStack();
            ps.last().set(modelPose);
            model.setupAnim(state);
            model.renderToBuffer(ps, uvMapping == null ? c : uvMapping.wrap(c), light, overlay, tint);
        });
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer renderer) {
        PoseStack.Pose modelPose = poseStack.last().copy();
        geometry(renderType, c -> renderer.render(modelPose, c));
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        frame.dropped++;
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 at, int y, Component name, boolean seeThrough, int light,
                              CameraRenderState camera) {
        frame.dropped++;
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean shadow,
                           Font.DisplayMode mode, int light, int colour, int background, int outline) {
        frame.dropped++;
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int colour,
                                     Font.DisplayMode mode, int light) {
        frame.dropped++;
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
        frame.dropped++;
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leash) {
        frame.dropped++;
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                           int light, int overlay, int tint,
                                           ModelFeatureRenderer.CrumblingOverlay crumbling) {
        frame.dropped++;
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int light) {
        frame.dropped++;
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tints, int light, int overlay, int outline) {
        frame.dropped++;
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, Function<ChunkSectionLayer, RenderType> renderTypes,
                                 boolean translucent, List<BlockStateModelPart> parts, Mesh mesh, int[] tints,
                                 int light, int overlay, int outline) {
        frame.dropped++;
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
                                         boolean flag) {
        frame.dropped++;
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, Mesh mesh, int progress,
                                         boolean flag) {
        frame.dropped++;
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int colour,
                                   float width, boolean flag) {
        frame.dropped++;
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay, int outline,
                           int[] tints, ItemQuads quads, ItemStackRenderState.FoilType foil) {
        frame.dropped++;
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay, int outline,
                           int[] tints, ItemQuads quads, MeshView mesh, ItemStackRenderState.FoilType foil) {
        frame.dropped++;
    }

    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
        frame.dropped++;
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState state) {
        frame.dropped++;
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean flag) {
        frame.dropped++;
    }
}
