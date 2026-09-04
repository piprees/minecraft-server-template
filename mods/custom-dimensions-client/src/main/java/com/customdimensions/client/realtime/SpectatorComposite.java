package com.customdimensions.client.realtime;

import com.customdimensions.client.CustomDimensionsClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.impl.client.rendering.FabricShaderProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

/**
 * The destination drawn THROUGH the opening: the offscreen colour attachment
 * sampled in screen space by the opening's own quad.
 *
 * <h2>Why screen space and not a texture coordinate</h2>
 * The second pass used the same projection as the source pass, so the pixel
 * under the quad is already in the right place — the fragment shader reads
 * {@code gl_FragCoord.xy / ScreenSize} and nothing is reprojected, downsampled
 * or blitted. Per-vertex texture coordinates cannot do this: a screen
 * coordinate is a projective function of the surface point, and vanilla
 * interpolates attributes perspective-correctly, which reproduces an affine
 * one. The error grows with foreshortening across the quad, which is exactly
 * the oblique view a portal is looked at from.
 *
 * <h2>The quad is the mask</h2>
 * It depth-tests normally, so source geometry standing in front of the frame
 * occludes it for free, and it writes depth at the surface so nothing the
 * source world holds behind the opening draws over the far side.
 *
 * <h2>It runs inside the source world's render</h2>
 * {@code GameRenderer.renderWorld} clears the depth buffer between
 * {@code WorldRenderer.render} and the hand, so a composite hung off the
 * return of {@code renderWorld} has no source depth to test against and paints
 * over blocks in front of the frame.
 */
public final class SpectatorComposite {

    /** Grepped in the client log for what the composite did to one opening. */
    public static final String MARKER = "companion-client:spectator-composite";

    private static final Identifier SHADER =
            Identifier.of("customdimensionsclient", "portal_composite");

    /** Four position-only vertices; the allocator never needs to grow. */
    private static final int BUFFER_SIZE = 1536;

    /** The colour attachment {@link #COMPOSITE} samples, set before each draw. */
    private static int sampler;

    private static ShaderProgram program;
    private static String shaderRefusal = "";
    private static VertexConsumerProvider.Immediate immediate;
    private static boolean hooked;
    private static boolean sizeChecked;

    /**
     * The offscreen frame, sampled in screen space. Depth writes on: the
     * opening's surface becomes the depth everything drawn afterwards
     * composites against.
     *
     * <p>The polygon offset is not decoration. {@code ProjectionRenderer}
     * stamps the same opening on the same plane as a differently triangulated
     * polygon, and two coplanar draws agree on a pixel's depth only to a few
     * ULPs — without the offset the composite speckles wherever it loses the
     * tie.
     */
    private static final RenderLayer COMPOSITE = new RenderLayer(
            "customdimensions_portal_composite",
            VertexFormats.POSITION,
            VertexFormat.DrawMode.QUADS,
            BUFFER_SIZE,
            false,
            false,
            () -> {
                RenderSystem.setShader(() -> program);
                RenderSystem.setShaderTexture(0, sampler);
                RenderSystem.disableBlend();
                RenderSystem.disableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.depthMask(true);
                RenderSystem.polygonOffset(-1.0f, -10.0f);
                RenderSystem.enablePolygonOffset();
            },
            () -> {
                RenderSystem.disablePolygonOffset();
                RenderSystem.polygonOffset(0.0f, 0.0f);
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.enableCull();
            }) {};

    /**
     * The same quad, tested against the source world's depth and writing
     * nothing. Depth writes off, or the probe occludes the composite that
     * follows it; the colour mask is off outside the layer, in a finally, for
     * the reason {@code PortalRenderLayers.APERTURE_DEPTH} gives.
     */
    private static final RenderLayer PROBE = new RenderLayer(
            "customdimensions_portal_probe",
            VertexFormats.POSITION,
            VertexFormat.DrawMode.QUADS,
            BUFFER_SIZE,
            false,
            false,
            () -> {
                RenderSystem.setShader(GameRenderer::getPositionProgram);
                RenderSystem.disableBlend();
                RenderSystem.disableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.depthMask(false);
            },
            () -> {
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
            }) {};

    private SpectatorComposite() {}

    /**
     * Hooks the in-world half of the pass, after {@code ProjectionRenderer} on
     * the same phase so the composite draws over the meshed opening rather
     * than under it. Idempotent.
     */
    public static void register() {
        if (hooked) {
            return;
        }
        hooked = true;
        WorldRenderEvents.BEFORE_ENTITIES.register(SpectatorPass::inWorld);
    }

    /** Draws the opening's quad inside an occlusion query, painting nothing. */
    public static void probe(WorldRenderContext context, double[] quad) {
        RenderSystem.colorMask(false, false, false, false);
        try {
            PortalOcclusion.issue(() -> draw(context, quad, PROBE));
        } finally {
            RenderSystem.colorMask(true, true, true, true);
        }
    }

    /**
     * Draws the offscreen frame through the opening. False when the shader
     * could not be loaded, which leaves the corner preview as the only view.
     */
    public static boolean composite(WorldRenderContext context, double[] quad, int colorAttachment) {
        if (!ensureShader() || quad == null || colorAttachment <= 0) {
            return false;
        }
        checkScreenSize();
        sampler = colorAttachment;
        draw(context, quad, COMPOSITE);
        return true;
    }

    /**
     * Vanilla fills {@code ScreenSize} from the WINDOW's framebuffer while
     * {@code gl_FragCoord} spans the bound one, and the offscreen target is
     * sized to the latter. They agree on every path that resizes them
     * together, so this observes the disagreement rather than predicting it:
     * the symptom would be the far side offset by a few pixels, which reads
     * as a camera fault.
     */
    private static void checkScreenSize() {
        if (sizeChecked) {
            return;
        }
        sizeChecked = true;
        MinecraftClient client = MinecraftClient.getInstance();
        int mainWidth = client.getFramebuffer().textureWidth;
        int mainHeight = client.getFramebuffer().textureHeight;
        int windowWidth = client.getWindow().getFramebufferWidth();
        int windowHeight = client.getWindow().getFramebufferHeight();
        if (mainWidth != windowWidth || mainHeight != windowHeight) {
            CustomDimensionsClient.LOGGER.warn(
                    "{} main framebuffer {}x{} but ScreenSize carries {}x{}; the composite samples "
                            + "the wrong texel", MARKER, mainWidth, mainHeight,
                    windowWidth, windowHeight);
        }
    }

    /** Empty while the composite can draw; otherwise why it cannot. */
    public static String refusal() {
        return shaderRefusal;
    }

    /**
     * Loads the core shader on first use.
     *
     * <p>Not through {@code CoreShaderRegistrationCallback}: that fires during
     * the resource reload at startup, so registering for it needs a line in
     * the entrypoint. {@code FabricShaderProgram} is what the callback
     * constructs anyway, and it is what makes a namespaced shader resolve to
     * {@code assets/customdimensionsclient/shaders/core/}.
     */
    private static boolean ensureShader() {
        if (program != null) {
            return true;
        }
        if (!shaderRefusal.isEmpty()) {
            return false;
        }
        try {
            program = new FabricShaderProgram(
                    MinecraftClient.getInstance().getResourceManager(), SHADER,
                    VertexFormats.POSITION);
            CustomDimensionsClient.LOGGER.info("{} shader={} loaded", MARKER, SHADER);
            return true;
        } catch (Exception failure) {
            shaderRefusal = "shader " + SHADER + " did not load: " + failure;
            CustomDimensionsClient.LOGGER.error(
                    "{} shader={} did not load; the composite is off", MARKER, SHADER, failure);
            return false;
        }
    }

    /**
     * One quad, camera-relative, on one layer.
     *
     * <p>{@code BEFORE_ENTITIES} supplies no matrix stack, so the world
     * transform is rebuilt from the position matrix the context always
     * carries — the same thing {@code ProjectionRenderer} does.
     */
    private static void draw(WorldRenderContext context, double[] quad, RenderLayer layer) {
        if (quad == null || quad.length < 12) {
            return;
        }
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            matrices = new MatrixStack();
            matrices.multiplyPositionMatrix(context.positionMatrix());
        }
        if (immediate == null) {
            immediate = VertexConsumerProvider.immediate(new BufferAllocator(BUFFER_SIZE));
        }
        Vec3d camera = context.camera().getPos();
        MatrixStack.Entry entry = matrices.peek();
        VertexConsumer consumer = immediate.getBuffer(layer);
        for (int corner = 0; corner < 4; corner++) {
            consumer.vertex(entry,
                    (float) (quad[corner * 3] - camera.x),
                    (float) (quad[corner * 3 + 1] - camera.y),
                    (float) (quad[corner * 3 + 2] - camera.z));
        }
        immediate.draw(layer);
    }
}
