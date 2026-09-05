package com.customdimensions.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.SpriteAtlasTexture;
import org.lwjgl.opengl.GL11;

/**
 * The layer that opens the portal.
 *
 * <p>A quad drawn at the far end of the cone through the opening, with the
 * depth test forced to always pass and depth writing on. It does two jobs at
 * once: it paints the destination's own sky and fog colour behind whatever
 * geometry follows, and it resets the depth buffer inside the opening so the
 * source world's blocks BEHIND the frame — a portal cut into a hillside is
 * looking at solid stone — stop occluding the destination.
 *
 * <p>An ordinary depth test is enough because the renderer compresses the whole
 * pass into a depth slice at the window (`ProjectionRenderer.depthSlice`): the
 * quad tests at the portal surface rather than at its own distance, so a real
 * block in front of the frame is nearer and survives, while the source world's
 * blocks BEHIND the frame — a portal cut into a hillside is looking at solid
 * stone — are farther and are covered.
 */
public final class PortalRenderLayers {

    private PortalRenderLayers() {}

    /**
     * The layer a captured block quad is DRAWN on, which is not the layer it
     * was captured for.
     *
     * <p>The mesh is built through vanilla's block renderer, so it comes back
     * keyed by {@code RenderLayers.getBlockLayer} — a terrain layer. A shader
     * pack's {@code gbuffers_terrain} is written for chunk geometry and reads
     * vertex attributes that only chunk geometry carries; a mod submitting
     * terrain layers through a {@code VertexConsumerProvider.Immediate} supplies
     * none of them, and under Complementary Reimagined the result reaches no
     * pixel at all ({@code TROUBLESHOOTING.md#t98}).
     *
     * <p>The entity layers over the block atlas take the same
     * {@code VertexFormats.ENTITY} the capture already writes — position,
     * colour, uv, overlay, light, normal — and are the same class of draw as
     * any mod's custom model, which is what the pack shades. Cutout loses
     * mipmapping: there is no mipmapped entity cutout layer.
     */
    public static RenderLayer forDestination(RenderLayer captured) {
        if (captured == RenderLayer.getTranslucent()
                || captured == RenderLayer.getTranslucentMovingBlock()) {
            return RenderLayer.getEntityTranslucentCull(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        }
        if (captured == RenderLayer.getCutout() || captured == RenderLayer.getCutoutMipped()) {
            return RenderLayer.getEntityCutoutNoCull(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        }
        return RenderLayer.getEntitySolid(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
    }

    public static final RenderLayer BACKDROP = new RenderLayer(
            "customdimensions_portal_backdrop",
            VertexFormats.POSITION_COLOR,
            VertexFormat.DrawMode.QUADS,
            1536,
            false,
            false,
            () -> {
                RenderSystem.setShader(GameRenderer::getPositionColorProgram);
                RenderSystem.disableBlend();
                RenderSystem.disableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
            },
            () -> {
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.enableCull();
            }) {};

    /**
     * The opening's own depth, written and nothing else.
     *
     * <p>Drawn on the portal surface after the destination, it replaces the
     * destination's depth — which is expressed in SOURCE-world coordinates and
     * so competes with the source world's own blocks — with the depth of the
     * window itself. Everything vanilla draws afterwards then composites
     * against the window: in front of it draws, behind it does not, whatever
     * the destination happens to hold along that sightline.
     *
     * <p>The colour mask is deliberately NOT set here. {@code RenderLayer.draw}
     * carries no exception table, so a throw between a layer's start and end
     * actions skips the end action — and a colour mask left off blanks the rest
     * of the frame. {@code ProjectionRenderer.withColourMaskOff} owns it and
     * restores it in a finally.
     */
    public static final RenderLayer APERTURE_DEPTH = new RenderLayer(
            "customdimensions_portal_aperture_depth",
            VertexFormats.POSITION_COLOR,
            VertexFormat.DrawMode.QUADS,
            1536,
            false,
            false,
            () -> {
                RenderSystem.setShader(GameRenderer::getPositionColorProgram);
                RenderSystem.disableBlend();
                RenderSystem.disableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_ALWAYS);
                RenderSystem.depthMask(true);
            },
            () -> {
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.enableCull();
            }) {};
}
