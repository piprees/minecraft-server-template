package com.customdimensions.client.realtime;

import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.config.RealtimeControls;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * A second world render of the destination, into an offscreen framebuffer,
 * blitted to a screen corner.
 *
 * <h2>What this is for</h2>
 * One thing to render and one thing to look at, with no mask involved: a
 * corner that shows the destination while the source world still looks right
 * says the second {@link WorldRenderer} survives Sodium and Iris. A failure
 * cannot be blamed on a composite that is not there.
 *
 * <h2>Fabulous graphics is refused</h2>
 * {@code WorldRenderer.render} calls {@code client.getFramebuffer().beginWrite}
 * from inside itself in two places: the fabulous-graphics block
 * ({@code transparencyPostProcessor != null}) and the entity-outline block. On
 * fabulous the destination abandons this pass's target mid-render and paints
 * into the main framebuffer, which reads as the source world breaking. The
 * outline block needs a glowing entity in frame and is left to be measured.
 *
 * <h2>The binding is captured, never assumed</h2>
 * {@code Framebuffer.endWrite()} hard-binds framebuffer 0 rather than
 * restoring what was bound, and Iris's own binding tracker skips binds it
 * believes are redundant. So the binding is read with
 * {@code GlStateManager._getInteger} and put back through
 * {@code GlStateManager._glBindFramebuffer}.
 */
public final class SpectatorPass {

    /** Grepped in the client log when a pass runs or stops running. */
    public static final String MARKER = "companion-client:spectator-pass";

    /** How often the pass reports its own cost, in frames. */
    private static final int REPORT_EVERY = 600;

    private static final SpectatorCamera CAMERA = new SpectatorCamera();

    private static Framebuffer target;
    private static int depth;
    private static boolean disabled;
    private static boolean drawn;
    private static long lastNanos;
    private static long totalNanos;
    private static long passes;
    private static String refusal = "not-run";

    private SpectatorPass() {}

    /** Renders one destination into the offscreen target. Never throws. */
    public static void render(GameRenderer gameRenderer, RenderTickCounter counter) {
        drawn = false;
        if (disabled || gameRenderer == null || counter == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) {
            refusal = "no source world";
            return;
        }
        if (!RealtimeControls.settings().enabled()) {
            refusal = "real-time view disabled";
            return;
        }
        if (depth > 0) {
            refusal = "nested";
            return;
        }
        if (client.options.getGraphicsMode().getValue() == GraphicsMode.FABULOUS) {
            refusal = "fabulous graphics rebinds the main framebuffer mid-render";
            return;
        }
        CompanionPayloads.PortalFrame frame = pick();
        if (frame == null) {
            refusal = "no framed destination standing";
            return;
        }
        ClientWorld destination = DestinationWorlds.get(frame.destination());
        WorldRenderer renderer = DestinationWorlds.rendererFor(frame.destination());
        if (destination == null || renderer == null) {
            refusal = "destination world or renderer missing";
            return;
        }
        refusal = "";
        run(client, gameRenderer, counter, frame, destination, renderer);
    }

    private static void run(MinecraftClient client, GameRenderer gameRenderer,
            RenderTickCounter counter, CompanionPayloads.PortalFrame frame,
            ClientWorld destination, WorldRenderer renderer) {
        ClientWorld source = client.world;
        int drawBinding = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readBinding = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        long started = System.nanoTime();
        depth++;
        try {
            Framebuffer into = ensureTarget(client);
            float tickDelta = counter.getTickDelta(true);
            double[] eye = PortalCamera.destinationEye(
                    client.player.getX(),
                    client.player.getEyeY(),
                    client.player.getZ(),
                    frame.dx(), frame.dy(), frame.dz());

            client.world = destination;
            client.getEntityRenderDispatcher().setWorld(destination);
            client.getBlockEntityRenderDispatcher().setWorld(destination);
            gameRenderer.getLightmapTextureManager().update(tickDelta);
            CAMERA.standIn(destination, client.player, tickDelta, eye[0], eye[1], eye[2]);

            double fov = client.options.getFov().getValue().doubleValue();
            Matrix4f projection = gameRenderer.getBasicProjectionMatrix(fov);
            Matrix4f position = new Matrix4f()
                    .rotation(CAMERA.getRotation().conjugate(new Quaternionf()));

            into.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            into.clear(MinecraftClient.IS_SYSTEM_MAC);
            into.beginWrite(true);
            RenderSystem.backupProjectionMatrix();
            try {
                gameRenderer.loadProjectionMatrix(projection);
                Vec3d at = new Vec3d(eye[0], eye[1], eye[2]);
                renderer.setupFrustum(at, position, projection);
                renderer.render(counter, false, CAMERA, gameRenderer,
                        gameRenderer.getLightmapTextureManager(), position, projection);
            } finally {
                RenderSystem.restoreProjectionMatrix();
            }
            drawn = true;
        } catch (Throwable failure) {
            disabled = true;
            drawn = false;
            refusal = "threw: " + failure;
            CustomDimensionsClient.LOGGER.error(
                    "{} dimension={} disabled after a throw", MARKER, frame.destination(), failure);
        } finally {
            depth--;
            client.world = source;
            client.getEntityRenderDispatcher().setWorld(source);
            client.getBlockEntityRenderDispatcher().setWorld(source);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBinding);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            lastNanos = System.nanoTime() - started;
            totalNanos += lastNanos;
            passes++;
            if (passes % REPORT_EVERY == 1) {
                CustomDimensionsClient.LOGGER.info(
                        "{} dimension={} passes={} lastUs={} meanUs={} drawn={}",
                        MARKER, frame.destination(), passes,
                        lastNanos / 1000L, totalNanos / Math.max(1L, passes) / 1000L, drawn);
            }
        }
    }

    /**
     * Copies this frame's destination into the top-left corner of whatever is
     * bound. Called after the source pass, so the source world is underneath.
     */
    public static void blit() {
        if (!drawn || target == null) {
            return;
        }
        int drawBinding = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readBinding = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] rect = SpectatorCorner.topLeft(target.textureWidth, target.textureHeight);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.fbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
        GlStateManager._glBlitFrameBuffer(
                0, 0, target.textureWidth, target.textureHeight,
                rect[0], rect[1], rect[2], rect[3],
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBinding);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
    }

    /** The nearest framed opening whose destination world is standing. */
    private static CompanionPayloads.PortalFrame pick() {
        for (CompanionPayloads.PortalFrame frame : PortalFrames.all()) {
            if (DestinationWorlds.get(frame.destination()) != null) {
                return frame;
            }
        }
        return null;
    }

    private static Framebuffer ensureTarget(MinecraftClient client) {
        int width = client.getFramebuffer().textureWidth;
        int height = client.getFramebuffer().textureHeight;
        if (target == null) {
            target = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
        } else if (target.textureWidth != width || target.textureHeight != height) {
            target.resize(width, height, MinecraftClient.IS_SYSTEM_MAC);
        }
        return target;
    }

    /** Drops the target and re-arms a pass that disabled itself. */
    public static void reset() {
        if (target != null) {
            target.delete();
            target = null;
        }
        disabled = false;
        drawn = false;
        depth = 0;
        lastNanos = 0L;
        totalNanos = 0L;
        passes = 0L;
        refusal = "not-run";
    }

    public static boolean disabled() {
        return disabled;
    }

    public static long passes() {
        return passes;
    }

    public static long lastMicros() {
        return lastNanos / 1000L;
    }

    public static long meanMicros() {
        return passes == 0L ? 0L : totalNanos / passes / 1000L;
    }

    /** Empty while the pass is running; otherwise why it did not. */
    public static String refusal() {
        return refusal;
    }
}
