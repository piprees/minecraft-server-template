package com.customdimensions.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/**
 * The layers a portal draws on.
 *
 * <p>The backdrop paints the destination's own sky and fog colour behind
 * whatever geometry follows. An ordinary {@code LEQUAL} test is enough because
 * the renderer compresses the whole pass into a depth slice at the window
 * ({@code ProjectionRenderer.depthSlice}): every fragment tests at the portal
 * surface rather than at its own distance, so a real block in front of the
 * frame is nearer and survives, while the source world's blocks BEHIND the
 * frame — a portal cut into a hillside is looking at solid stone — are farther
 * and are covered.
 *
 * <p>{@link #forDestination} and {@link #backdrop} both answer the same
 * question: which layer does a shader pack treat as ordinary world geometry.
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

    /**
     * Vanilla's 1x1 white texture, so the quad's own colour is what reaches the
     * screen and no atlas sprite is sampled.
     */
    private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");

    /**
     * The backdrop's layer: an entity layer, over white, no culling.
     *
     * <p>Drawn through {@code position_color} it is geometry with no normal and
     * no lightmap, which a shader pack has nothing to shade: measured under
     * Complementary Reimagined, the destination's fog colour {@code
     * (192,216,255)} reaches the screen as {@code (248,244,246)}, a blown-out
     * white filling the opening. On an entity layer, with a normal facing the
     * camera and the destination's sky light, it is shaded like any model.
     *
     * <p>No culling because the quad is cast from the camera through the
     * opening's corners and its winding follows the aperture rather than the
     * view.
     */
    public static RenderLayer backdrop() {
        return RenderLayer.getEntityCutoutNoCull(WHITE);
    }

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
     * restores it in a finally. The {@code GL_ALWAYS} depth function is set
     * here because the draw needs it, and
     * {@code ProjectionRenderer.withDepthStateRestored} puts it back in a
     * finally for the same reason.
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
