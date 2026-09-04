package com.customdimensions.client.realtime;

import com.customdimensions.client.CustomDimensionsClient;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * One GPU occlusion query, reused for the life of the session, answering
 * whether the opening's quad reaches the screen at all.
 *
 * <h2>Which GL path this machine takes is observed, not predicted</h2>
 * {@code GL_ANY_SAMPLES_PASSED} is GL 3.3 and Apple's GL does not have it, so
 * the counting {@code GL_SAMPLES_PASSED} is used where it is absent. The
 * capability is read off {@code GL.getCapabilities()} and the chosen target is
 * named in {@link #path()}, which the pass's own report line carries.
 *
 * <h2>The result is read a frame late, and that is not a compromise</h2>
 * The query has to be issued where the source world's depth buffer exists,
 * which is inside the source render; the pass it gates runs at the head of the
 * next frame. Reading a result issued one whole frame ago costs no stall — the
 * availability flag is checked first and a result that is somehow not ready
 * leaves the previous verdict standing rather than blocking the render thread.
 *
 * <p>Optimistic until told otherwise: an unanswered query draws the portal.
 * The opposite default hides a working portal whenever the query path fails.
 */
public final class PortalOcclusion {

    /** Grepped in the client log for the GL path and the gate's own counts. */
    public static final String MARKER = "companion-client:occlusion";

    private static int query = -1;
    private static int target;
    private static boolean unavailable;
    private static boolean pending;
    private static boolean visible = true;
    private static long issued;
    private static long reads;
    private static long refusals;
    private static long readNanos;
    private static String path = "not-run";

    private PortalOcclusion() {}

    /**
     * Draws {@code quad} inside a query. The caller owns the GL state the draw
     * needs: depth test on so the count means occlusion, depth write off so
     * the probe does not become an occluder itself, colour mask off so it
     * paints nothing.
     */
    public static void issue(Runnable quad) {
        if (quad == null || unavailable) {
            return;
        }
        try {
            if (query <= 0) {
                // GL_ANY_SAMPLES_PASSED is GL 3.3 and Apple's GL does not have
                // it. Which one this machine takes is read off the caps, not
                // assumed, and named in the pass's report line.
                boolean any = !MinecraftClient.IS_SYSTEM_MAC && GL.getCapabilities().OpenGL33;
                target = any ? GL33.GL_ANY_SAMPLES_PASSED : GL15.GL_SAMPLES_PASSED;
                path = any ? "GL_ANY_SAMPLES_PASSED" : "GL_SAMPLES_PASSED";
                query = GL15.glGenQueries();
                CustomDimensionsClient.LOGGER.info("{} path={} mac={} gl33={} query={}",
                        MARKER, path, MinecraftClient.IS_SYSTEM_MAC,
                        GL.getCapabilities().OpenGL33, query);
            }
            GL15.glBeginQuery(target, query);
            try {
                quad.run();
            } finally {
                // A draw that throws between begin and end leaves the query
                // active, and the next glBeginQuery is then an invalid
                // operation for the rest of the session.
                GL15.glEndQuery(target);
            }
            pending = true;
            issued++;
        } catch (RuntimeException | LinkageError failure) {
            unavailable = true;
            visible = true;
            CustomDimensionsClient.LOGGER.warn(
                    "{} disabled; every pass runs ungated", MARKER, failure);
        }
    }

    /**
     * The GPU's last answer, refreshed when it is ready. Never blocks: a
     * result that has not landed leaves the previous verdict standing.
     */
    public static boolean visible() {
        if (unavailable || !pending || query <= 0) {
            return visible;
        }
        long started = System.nanoTime();
        try {
            if (GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return visible;
            }
            visible = GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT) != 0;
            pending = false;
            reads++;
            if (!visible) {
                refusals++;
            }
        } catch (RuntimeException | LinkageError failure) {
            unavailable = true;
            visible = true;
            CustomDimensionsClient.LOGGER.warn(
                    "{} result unreadable; every pass runs ungated", MARKER, failure);
        } finally {
            readNanos += System.nanoTime() - started;
        }
        return visible;
    }

    /**
     * Forgets the standing verdict. The answer belongs to one opening, so a
     * pass that switches portals must not inherit it.
     */
    public static void forget() {
        visible = true;
        pending = false;
    }

    /** Drops every count and the standing verdict; the query object survives. */
    public static void reset() {
        forget();
        issued = 0L;
        reads = 0L;
        refusals = 0L;
        readNanos = 0L;
    }

    public static String path() {
        return path;
    }

    public static long issued() {
        return issued;
    }

    public static long refusals() {
        return refusals;
    }

    /** Mean microseconds spent reading a result — the stall, measured. */
    public static long readMicros() {
        return reads == 0L ? 0L : readNanos / reads / 1000L;
    }
}
