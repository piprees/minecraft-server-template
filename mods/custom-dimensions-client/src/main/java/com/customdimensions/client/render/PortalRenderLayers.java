package com.customdimensions.client.render;

import com.customdimensions.client.config.RealtimeControls;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

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
     * Vanilla's 1x1 white texture, so the quad's own colour is what reaches the
     * screen and no atlas sprite is sampled.
     */
    private static final Identifier WHITE = Identifier.ofVanilla("textures/misc/white.png");

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
    public static RenderLayer forDestination(RenderLayer captured, boolean unshaded) {
        return layerFor(UnshadedDestination.of(classify(captured), unshaded));
    }

    private static UnshadedDestination.Captured classify(RenderLayer captured) {
        if (captured == RenderLayer.getTranslucent()
                || captured == RenderLayer.getTranslucentMovingBlock()) {
            return UnshadedDestination.Captured.TRANSLUCENT;
        }
        if (captured == RenderLayer.getCutout() || captured == RenderLayer.getCutoutMipped()) {
            return UnshadedDestination.Captured.CUTOUT;
        }
        return UnshadedDestination.Captured.SOLID;
    }

    private static RenderLayer layerFor(UnshadedDestination.Target target) {
        return switch (target) {
            case ENTITY_TRANSLUCENT_CULL ->
                    RenderLayer.getEntityTranslucentCull(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            case ENTITY_CUTOUT_NO_CULL ->
                    RenderLayer.getEntityCutoutNoCull(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            case ENTITY_SOLID -> RenderLayer.getEntitySolid(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            case UNSHADED_OPAQUE -> UNSHADED_OPAQUE;
            case UNSHADED_BLENDED -> UNSHADED_BLENDED;
            case ENTITY_BACKDROP -> RenderLayer.getEntityCutoutNoCull(WHITE);
            case UNSHADED_BACKDROP -> UNSHADED_BACKDROP;
        };
    }

    /**
     * The destination on vanilla's beacon-beam program: position, colour, uv
     * and a lightmap coordinate the program never samples. No normal and no
     * lightmap phase, so neither vanilla's diffuse nor the source world's
     * lightmap texel can reach it, and its light comes from
     * {@link UnshadedDestination#scale} in the vertex colour instead.
     */
    public static final RenderLayer UNSHADED_OPAQUE =
            unshaded("customdimensions_portal_unshaded_opaque", false);

    /** The same, blended, for the captured layers that carry alpha. */
    public static final RenderLayer UNSHADED_BLENDED =
            unshaded("customdimensions_portal_unshaded_blended", true);

    /**
     * The same, over the white texture and unculled, for the backdrop: its
     * winding follows the aperture rather than the view, and its colour is the
     * destination's fog colour rather than a sprite.
     */
    public static final RenderLayer UNSHADED_BACKDROP =
            unshaded("customdimensions_portal_unshaded_backdrop", WHITE, false, false);

    private static RenderLayer unshaded(String name, boolean blend) {
        return unshaded(name, SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE, blend, true);
    }

    private static RenderLayer unshaded(String name, Identifier texture, boolean blend,
            boolean cull) {
        return new RenderLayer(
                name,
                VertexFormats.POSITION_COLOR_TEXTURE_LIGHT,
                VertexFormat.DrawMode.QUADS,
                1536,
                false,
                blend,
                () -> {
                    RenderSystem.setShader(GameRenderer::getRenderTypeBeaconBeamProgram);
                    applyAtlasFilter(texture);
                    RenderSystem.setShaderTexture(0, texture);
                    recordAtlasFilter(name, texture);
                    if (blend) {
                        RenderSystem.enableBlend();
                        RenderSystem.defaultBlendFunc();
                    } else {
                        RenderSystem.disableBlend();
                    }
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);
                    // Written, so the mesh self-occludes inside the slice as it
                    // does on the entity layers.
                    RenderSystem.depthMask(true);
                    if (cull) {
                        RenderSystem.enableCull();
                    } else {
                        RenderSystem.disableCull();
                    }
                },
                () -> {
                    RenderSystem.disableBlend();
                    RenderSystem.enableCull();
                }) {};
    }

    /** Per unshaded layer, the filter its own texture object carried at draw. */
    private static final Map<String, Probe> ATLAS_FILTERS = new ConcurrentHashMap<>();

    private record Probe(int min, int mag, long draws) {

        Probe next(int min, int mag) {
            return new Probe(min, mag, this.draws + 1);
        }

        @Override
        public String toString() {
            return String.format("min=0x%04X %s mag=0x%04X %s draws=%d",
                    this.min, filterName(this.min), this.mag, filterName(this.mag), this.draws);
        }
    }

    /**
     * The block atlas is one shared GL texture object, so a layer that only
     * binds it draws with whatever filter the last phase left on it.
     * {@code apertureAtlasFilter} -1 inherits that, 0 is plain, 1 mipmapped.
     */
    private static void applyAtlasFilter(Identifier texture) {
        int mode = RealtimeControls.settings().apertureAtlasFilter();
        if (mode < 0) {
            return;
        }
        MinecraftClient.getInstance().getTextureManager().getTexture(texture)
                .setFilter(false, mode == 1 && !WHITE.equals(texture));
    }

    /**
     * Read off the texture object rather than assumed, because the answer is
     * whatever ran last. The binding is restored so nothing downstream sees it.
     */
    private static void recordAtlasFilter(String name, Identifier texture) {
        int glId = MinecraftClient.getInstance().getTextureManager().getTexture(texture).getGlId();
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(glId);
        int min = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
        int mag = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
        GlStateManager._bindTexture(bound);
        ATLAS_FILTERS.compute(name,
                (key, held) -> held == null ? new Probe(min, mag, 1) : held.next(min, mag));
    }

    /** A rising draw count is what says the layer drew in a given window. */
    public static String atlasFilters() {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(ATLAS_FILTERS).forEach((name, probe) ->
                out.append(out.isEmpty() ? "" : "; ").append(name).append(' ').append(probe));
        return out.isEmpty() ? "none" : out.toString();
    }

    private static String filterName(int filter) {
        return switch (filter) {
            case GL11.GL_NEAREST -> "NEAREST";
            case GL11.GL_LINEAR -> "LINEAR";
            case GL11.GL_NEAREST_MIPMAP_NEAREST -> "NEAREST_MIPMAP_NEAREST";
            case GL11.GL_LINEAR_MIPMAP_NEAREST -> "LINEAR_MIPMAP_NEAREST";
            case GL11.GL_NEAREST_MIPMAP_LINEAR -> "NEAREST_MIPMAP_LINEAR";
            case GL11.GL_LINEAR_MIPMAP_LINEAR -> "LINEAR_MIPMAP_LINEAR";
            default -> "UNKNOWN";
        };
    }

    /**
     * The backdrop's layer, over white so the quad's own colour is what reaches
     * the screen, and unculled because the quad is cast from the camera through
     * the opening's corners and its winding follows the aperture rather than the
     * view.
     *
     * <p>Unshaded it is the destination's fog colour flat, which is what the
     * colour already is: on an entity layer a lightmap texel and a diffuse term
     * are applied to a finished colour a second time
     * ({@code TROUBLESHOOTING.md#t99}), and a pack shadows it from the source
     * world as it shades any model.
     */
    public static RenderLayer backdrop(boolean unshaded) {
        return layerFor(UnshadedDestination.backdrop(unshaded));
    }

    /**
     * The destination's own depth, written and nothing else.
     *
     * <p>{@code LEQUAL} rather than {@link #APERTURE_DEPTH}'s always-pass,
     * because the mesh's quads overlap on screen and the nearest has to win —
     * an always-pass test settles that by draw order instead. The far stamp
     * runs first and puts the whole opening behind this, so every fragment
     * here passes on its first write.
     */
    public static final RenderLayer DESTINATION_DEPTH = new RenderLayer(
            "customdimensions_portal_destination_depth",
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
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.depthMask(true);
            },
            () -> {
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
