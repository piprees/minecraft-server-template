package com.customdimensions.client.realtime;

import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.Repeated;
import com.customdimensions.client.config.RealtimeControls;
import com.customdimensions.client.mixin.GameRendererFovInvoker;
import com.customdimensions.client.mixin.MinecraftClientFramebufferAccessor;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Collection;

/**
 * A second world render of the destination, into an offscreen framebuffer,
 * composited through the opening's own quad.
 *
 * <h2>Three parts, in three places, because of where the depth buffer is</h2>
 * The render runs at the head of {@code GameRenderer.renderWorld}, outside the
 * source world's own render — every {@code WorldRenderEvents} phase fires
 * inside it with the shared {@code BufferBuilderStorage} mid-use. The
 * occlusion query and the composite run inside the source render, which is the
 * only moment the source world's depth exists: {@code renderWorld} clears it
 * between {@code WorldRenderer.render} and the hand. The corner preview rides
 * the return, where it tests nothing and so does not care.
 *
 * <p>So the gate reads a query issued one frame earlier. That costs a stale
 * verdict for one frame when the view changes, and buys a gate that reads real
 * depth and a result read with no GPU stall.
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

    /** The opening this frame chose, and its quad in source-world coordinates. */
    private static BlockPos chosen;
    private static double[] quad;

    /** Whether the cheap tests allowed a pass this frame, gate or no gate. */
    private static boolean cpuVisible;

    private static long lastNanos;
    private static long totalNanos;
    private static long passes;
    private static long gated;
    private static String refusal = "not-run";

    /** The draw framebuffer either side of the destination render, and how often it moved. */
    private static int boundBefore = -1;
    private static int boundAfter = -1;
    private static long rebinds;

    private SpectatorPass() {}

    /** Renders one destination into the offscreen target. Never throws. */
    public static void render(GameRenderer gameRenderer, RenderTickCounter counter) {
        drawn = false;
        cpuVisible = false;
        if (disabled || gameRenderer == null || counter == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) {
            refusal = "no source world";
            return;
        }
        if (!RealtimeControls.settings().renderClientSidePortals()) {
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
        CompanionPayloads.PortalFrame frame = pick(client.player.getEyePos());
        if (frame == null) {
            quad = null;
            refusal = "no framed destination standing in front and in range";
            return;
        }
        Direction normal = facing(frame);
        if (!frame.apertureOrigin().equals(chosen)) {
            // The standing verdict belongs to one opening.
            PortalOcclusion.forget();
            chosen = frame.apertureOrigin();
        }
        quad = CompositeQuad.corners(frame.aperture(), normal);
        ClientWorld destination = DestinationWorlds.get(frame.destination());
        WorldRenderer renderer = DestinationWorlds.rendererFor(frame.destination());
        if (destination == null || renderer == null) {
            refusal = "destination world or renderer missing";
            return;
        }
        cpuVisible = true;
        refusal = "";
        run(client, gameRenderer, counter, frame, destination, renderer);
    }

    /**
     * One pass's own script: the gate, then the target, then the render.
     * Returns whether the destination was drawn.
     */
    static boolean runPass(SpectatorSteps steps) {
        if (!steps.visible()) {
            return false;
        }
        steps.prepareTarget();
        steps.clearTarget();
        steps.adoptTarget();
        try {
            steps.renderDestination();
        } finally {
            steps.releaseTarget();
        }
        return true;
    }

    /**
     * Inside the source world's render: the query that answers the next
     * frame's gate, and this frame's composite through the opening.
     */
    static void runComposite(SpectatorPresent steps, boolean allowed, boolean rendered) {
        if (!allowed) {
            return;
        }
        steps.issueOcclusionQuery();
        if (rendered) {
            steps.compositeThroughQuad();
        }
    }

    /** After the source world: the scaffold preview, while it is on. */
    static void runCorner(SpectatorPresent steps, boolean rendered, boolean cornerOn) {
        if (rendered && cornerOn) {
            steps.blitCorner();
        }
    }

    private static void run(MinecraftClient client, GameRenderer gameRenderer,
            RenderTickCounter counter, CompanionPayloads.PortalFrame frame,
            ClientWorld destination, WorldRenderer renderer) {
        Pass pass = new Pass(client, gameRenderer, counter, frame, destination, renderer);
        long started = System.nanoTime();
        depth++;
        try {
            drawn = runPass(pass);
            if (!drawn) {
                gated++;
                refusal = "occluded";
            }
        } catch (Throwable failure) {
            disabled = true;
            drawn = false;
            refusal = "threw: " + failure;
            CustomDimensionsClient.LOGGER.error(
                    "{} dimension={} disabled after a throw", MARKER, frame.destination(), failure);
        } finally {
            depth--;
            pass.restore();
            if (drawn) {
                lastNanos = System.nanoTime() - started;
                totalNanos += lastNanos;
                passes++;
                if (passes % REPORT_EVERY == 1) {
                    Repeated.log(CustomDimensionsClient.LOGGER, passes == 1L,
                            "{} dimension={} passes={} gated={} lastUs={} meanUs={} "
                                    + "occlusion={} queries={} refusals={} readUs={} composite={}",
                            MARKER, frame.destination(), passes, gated,
                            lastNanos / 1000L, totalNanos / Math.max(1L, passes) / 1000L,
                            PortalOcclusion.path(), PortalOcclusion.issued(),
                            PortalOcclusion.refusals(), PortalOcclusion.readMicros(),
                            SpectatorComposite.refusal().isEmpty() ? "on"
                                    : SpectatorComposite.refusal());
                }
            }
        }
    }

    /**
     * The in-world half: this frame's query and composite. Driven from
     * {@code WorldRenderEvents.BEFORE_ENTITIES}, after the opaque terrain and
     * before the entities, which is where the depth buffer holds the source
     * world and nothing else.
     */
    public static void inWorld(WorldRenderContext context) {
        if (context == null || quad == null || disabled) {
            return;
        }
        runComposite(new Present(context), cpuVisible, drawn);
    }

    /** The scaffold corner. Called after the source world's own render. */
    public static void blit() {
        runCorner(new Present(null), drawn, SpectatorCorner.enabled());
    }

    /**
     * The nearest framed opening the camera can see: destination standing, in
     * front of the surface, inside {@link SpectatorGate#RANGE}.
     */
    private static CompanionPayloads.PortalFrame pick(Vec3d eye) {
        Collection<CompanionPayloads.PortalFrame> held = PortalFrames.all();
        if (held.isEmpty()) {
            return null;
        }
        CompanionPayloads.PortalFrame[] frames =
                held.toArray(new CompanionPayloads.PortalFrame[0]);
        double[] distances = new double[frames.length];
        boolean[] allowed = new boolean[frames.length];
        for (int i = 0; i < frames.length; i++) {
            CompanionPayloads.PortalFrame frame = frames[i];
            if (DestinationWorlds.get(frame.destination()) == null) {
                continue;
            }
            Direction normal = facing(frame);
            if (normal == null) {
                continue;
            }
            double[] centre = CompositeQuad.centre(frame.aperture(), normal);
            if (centre == null) {
                continue;
            }
            distances[i] = SpectatorGate.distanceSquared(
                    eye.x, eye.y, eye.z, centre[0], centre[1], centre[2]);
            double surface = CompositeQuad.on(centre[0], centre[1], centre[2], normal.getAxis());
            allowed[i] = SpectatorGate.withinRange(distances[i])
                    && SpectatorGate.inFront(surface, CompositeQuad.towardsHigh(normal),
                            CompositeQuad.on(eye.x, eye.y, eye.z, normal.getAxis()));
        }
        int best = SpectatorGate.nearest(distances, allowed);
        return best < 0 ? null : frames[best];
    }

    /**
     * The frame's normal, or null when the wire carried an index no direction
     * has. The render path runs outside the pass's own try, so a malformed
     * payload here would take the whole frame down.
     */
    private static Direction facing(CompanionPayloads.PortalFrame frame) {
        int index = frame.normal();
        return index < 0 || index >= Direction.values().length
                ? null : Direction.values()[index];
    }

    /**
     * The offscreen target, at the MAIN framebuffer's size and never
     * downsampled. The composite reads it at {@code gl_FragCoord / ScreenSize},
     * so any other size samples the wrong texel; and it is allocated once,
     * resized only when the window changes.
     */
    private static Framebuffer ensureTarget(MinecraftClient client) {
        Framebuffer main = client.getFramebuffer();
        int width = Math.max(1, main.textureWidth);
        int height = Math.max(1, main.textureHeight);
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
        cpuVisible = false;
        depth = 0;
        chosen = null;
        quad = null;
        lastNanos = 0L;
        totalNanos = 0L;
        passes = 0L;
        gated = 0L;
        refusal = "not-run";
        boundBefore = -1;
        boundAfter = -1;
        rebinds = 0L;
        PortalOcclusion.reset();
    }

    public static boolean disabled() {
        return disabled;
    }

    public static long passes() {
        return passes;
    }

    /** Frames the occlusion gate refused after the cheap tests allowed them. */
    public static long gated() {
        return gated;
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

    /** The draw framebuffer bound going in to the destination render. */
    public static int boundBefore() {
        return boundBefore;
    }

    /** The draw framebuffer bound when the destination render returned. */
    public static int boundAfter() {
        return boundAfter;
    }

    /** Renders that returned with a different draw framebuffer than they were given. */
    public static long rebinds() {
        return rebinds;
    }

    /**
     * The draw framebuffer either side of {@code WorldRenderer.render}, which
     * calls {@code client.getFramebuffer().beginWrite} from inside itself on
     * the fabulous and entity-outline paths. Everything drawn after such a
     * rebind lands in the main framebuffer instead of this pass's target.
     */
    private static void recordBinding(int before, int after) {
        boundBefore = before;
        boundAfter = after;
        if (before == after) {
            return;
        }
        rebinds++;
        if (rebinds == 1L || rebinds % REPORT_EVERY == 0L) {
            CustomDimensionsClient.LOGGER.warn(
                    "{} draw framebuffer moved inside render before={} after={} rebinds={}",
                    MARKER, before, after, rebinds);
        }
    }

    /**
     * The render half, against live GL. Everything it touches is restored by
     * {@link #restore}, which is a no-op on a frame the gate refused — that is
     * the whole saving.
     */
    private static final class Pass implements SpectatorSteps {

        private final MinecraftClient client;
        private final GameRenderer gameRenderer;
        private final RenderTickCounter counter;
        private final CompanionPayloads.PortalFrame frame;
        private final ClientWorld destination;
        private final WorldRenderer renderer;

        private ClientWorld source;
        private Framebuffer into;
        private Framebuffer main;
        private int drawBinding;
        private int readBinding;
        private boolean captured;

        private Pass(MinecraftClient client, GameRenderer gameRenderer, RenderTickCounter counter,
                CompanionPayloads.PortalFrame frame, ClientWorld destination,
                WorldRenderer renderer) {
            this.client = client;
            this.gameRenderer = gameRenderer;
            this.counter = counter;
            this.frame = frame;
            this.destination = destination;
            this.renderer = renderer;
        }

        @Override
        public boolean visible() {
            return PortalOcclusion.visible();
        }

        @Override
        public void prepareTarget() {
            this.drawBinding = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            this.readBinding = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            this.captured = true;
            this.into = ensureTarget(this.client);
        }

        @Override
        public void clearTarget() {
            this.into.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            this.into.clear(MinecraftClient.IS_SYSTEM_MAC);
        }

        @Override
        public void adoptTarget() {
            this.main = this.client.getFramebuffer();
            ((MinecraftClientFramebufferAccessor) this.client)
                    .customdimensionsclient$setFramebuffer(this.into);
        }

        @Override
        public void releaseTarget() {
            if (this.main == null) {
                return;
            }
            ((MinecraftClientFramebufferAccessor) this.client)
                    .customdimensionsclient$setFramebuffer(this.main);
            this.main = null;
        }

        @Override
        public void renderDestination() {
            float tickDelta = this.counter.getTickDelta(true);
            double[] eye = PortalCamera.destinationEye(
                    this.client.player.getX(),
                    this.client.player.getEyeY(),
                    this.client.player.getZ(),
                    this.frame.dx(), this.frame.dy(), this.frame.dz());

            // MinecraftClient.world stays the source. Other threads read it —
            // the sound engine on every sound it starts — and a world no client
            // lifecycle stood up has no per-world state there (TROUBLESHOOTING.md#t92).
            this.source = this.client.world;
            this.client.getEntityRenderDispatcher().setWorld(this.destination);
            this.client.getBlockEntityRenderDispatcher().setWorld(this.destination);
            DestinationLightmap.hold(this.destination);
            try {
                this.gameRenderer.getLightmapTextureManager().update(tickDelta);
            } finally {
                DestinationLightmap.release();
            }
            CAMERA.standIn(this.destination, this.client.player, tickDelta,
                    eye[0], eye[1], eye[2]);

            SpectatorProjection.ViewFov view = new SourceFov(this.client, this.gameRenderer, tickDelta);
            Matrix4f projection =
                    this.gameRenderer.getBasicProjectionMatrix(SpectatorProjection.render(view));
            Matrix4f culling =
                    this.gameRenderer.getBasicProjectionMatrix(SpectatorProjection.frustum(view));
            Matrix4f position = new Matrix4f()
                    .rotation(CAMERA.getRotation().conjugate(new Quaternionf()));

            this.into.beginWrite(true);
            RenderSystem.backupProjectionMatrix();
            try {
                this.gameRenderer.loadProjectionMatrix(projection);
                this.renderer.setupFrustum(new Vec3d(eye[0], eye[1], eye[2]), position, culling);
                int wrote = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                this.renderer.render(this.counter, false, CAMERA, this.gameRenderer,
                        this.gameRenderer.getLightmapTextureManager(), position, projection);
                recordBinding(wrote, GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
            } finally {
                RenderSystem.restoreProjectionMatrix();
            }
        }

        /** Puts back everything the pass swapped, whether or not it got that far. */
        private void restore() {
            releaseTarget();
            DestinationLightmap.release();
            if (this.source != null) {
                this.client.getEntityRenderDispatcher().setWorld(this.source);
                this.client.getBlockEntityRenderDispatcher().setWorld(this.source);
            }
            if (this.captured) {
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readBinding);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawBinding);
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
            }
        }
    }

    /**
     * Vanilla's field-of-view answers for the source frame.
     *
     * <p>The camera is the game renderer's own, which the pass reads at the
     * head of {@code renderWorld} before this frame's {@code Camera.update}.
     * Only the submersion type can differ by a frame, and a frame of that is
     * not visible; the multiplier the zoom drives is ticked, not updated here.
     */
    private record SourceFov(MinecraftClient client, GameRenderer gameRenderer, float tickDelta)
            implements SpectatorProjection.ViewFov {

        @Override
        public double fov(boolean changing) {
            return ((GameRendererFovInvoker) this.gameRenderer).customdimensionsclient$getFov(
                    this.gameRenderer.getCamera(), this.tickDelta, changing);
        }

        @Override
        public double option() {
            return this.client.options.getFov().getValue().doubleValue();
        }
    }

    /** The presentation half: the query, the composite, and the scaffold corner. */
    private record Present(WorldRenderContext context) implements SpectatorPresent {

        @Override
        public void issueOcclusionQuery() {
            SpectatorComposite.probe(this.context, quad);
        }

        @Override
        public void compositeThroughQuad() {
            if (target == null) {
                return;
            }
            SpectatorComposite.composite(this.context, quad, target.getColorAttachment());
        }

        /**
         * Copies this frame's destination into a preview against the left
         * edge of whatever is bound. Called after the source pass, so the
         * source world is underneath.
         */
        @Override
        public void blitCorner() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (target == null || client == null) {
                return;
            }
            int drawBinding = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int readBinding = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int[] rect = SpectatorCorner.preview(client.getFramebuffer().textureWidth,
                    client.getFramebuffer().textureHeight);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.fbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
            GlStateManager._glBlitFrameBuffer(
                    0, 0, target.textureWidth, target.textureHeight,
                    rect[0], rect[1], rect[2], rect[3],
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBinding);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
        }
    }
}
