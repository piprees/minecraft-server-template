package com.customdimensions.client.dev;

import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.config.RealtimeControls;
import com.customdimensions.client.config.RealtimeSettings;
import com.customdimensions.client.realtime.DestinationChunks;
import com.customdimensions.client.realtime.DestinationWorlds;
import com.customdimensions.client.realtime.PortalFrames;
import com.customdimensions.client.render.ProjectionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The dev control surface's HTTP port, bound to loopback only.
 *
 * <p>Everything that touches the game is submitted to the render thread and
 * waited on with a bound; a wait that expires answers with a reason rather than
 * hanging the request. {@code /health} is the exception — it reads only this
 * mod's tick counter, so it still answers when the client thread is wedged,
 * which is itself the diagnosis.
 */
public final class DevServer {

    private static final long RENDER_TIMEOUT_MS = 5000;
    private static final long WALK_SLACK_MS = 5000;
    private static final int DEFAULT_STALL_TICKS = 20;
    private static final int DEFAULT_WALK_TIMEOUT_MS = 20000;

    private static HttpServer server;

    private DevServer() {}

    static void start(int port, String source) {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/", DevServer::route);
            // Four threads, not one: a walk holds its request for its whole
            // timeout, and /health and /state have to stay answerable while it
            // runs. Two walks at once are refused in DevBridge.startWalk.
            server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "customdim-dev");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            CustomDimensionsClient.LOGGER.info(
                    "Dev control surface listening on 127.0.0.1:{} (port from {})", port, source);
        } catch (IOException e) {
            CustomDimensionsClient.LOGGER.error(
                    "Dev control surface failed to bind port {}", port, e);
            server = null;
        }
    }

    // ---------------------------------------------------------------- routes

    /** One answer, one send. Every failure becomes a body rather than a dropped reply. */
    private static void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Answer answer;
        try {
            answer = answer(exchange, path);
        } catch (DevTimeout e) {
            CustomDimensionsClient.LOGGER.warn(
                    "Dev control surface {} timed out after {}ms", path, e.timeoutMs());
            answer = new Answer(503, DevResponse.timeout(path, e.timeoutMs()));
        } catch (RuntimeException | IOException e) {
            CustomDimensionsClient.LOGGER.warn("Dev control surface {} failed", path, e);
            answer = new Answer(500, DevResponse.error(DevResponse.reasonOf(e)));
        }
        send(exchange, answer.status(), DevResponse.nonEmpty(answer.body()));
    }

    private record Answer(int status, String body) {}

    private static Answer answer(HttpExchange exchange, String path) throws IOException {
        return switch (path) {
            case "/health" -> new Answer(200, health());
            case "/state" -> new Answer(200, onRender(DevServer::state));
            case "/screenshot" -> screenshot(exchange);
            case "/input" -> input(exchange);
            default -> new Answer(404, DevResponse.error("no such endpoint: " + path));
        };
    }

    private static String health() {
        return Json.obj()
                .bool("ok", true)
                .str("mod", "customdimensionsclient")
                .str("mc", SharedConstants.getGameVersion().getName())
                .num("tick", DevBridge.tick())
                .toString();
    }

    private static String state() {
        MinecraftClient client = MinecraftClient.getInstance();
        return DevState.json(client, DevBridge.tick());
    }

    private static Answer screenshot(HttpExchange exchange) throws IOException {
        ScreenshotRequest request = ScreenshotRequest.parse(body(exchange));
        if (!request.ok()) {
            return new Answer(400, DevResponse.error(request.error()));
        }
        return new Answer(200, onRender(() ->
                DevShots.json(MinecraftClient.getInstance(), request.path())));
    }

    // ----------------------------------------------------------------- input

    private static Answer input(HttpExchange exchange) throws IOException {
        DevRequest request = DevRequest.parse(body(exchange));
        if (!request.ok()) {
            return new Answer(400, DevResponse.error(request.error()));
        }
        try {
            return new Answer(200, switch (request.action()) {
                case "walk" -> walk(request);
                case "sneak" -> sneak(request);
                case "realtime" -> realtime(request);
                default -> instant(request);
            });
        } catch (JsonReader.Malformed e) {
            return new Answer(400, DevResponse.error(e.getMessage()));
        }
    }

    /**
     * Reads the real-time view's settings, and writes the fields the body
     * names. A body with no fields is a read, so the A/B can record which path
     * a shot was taken on without changing it.
     *
     * <p>Off the render thread on purpose: the store is its own lock and the
     * game reads it on the next tick, so a settings write never waits on a
     * frame — which matters when the reason for flipping it is a frame that is
     * not arriving.
     */
    private static String realtime(DevRequest request) {
        RealtimeSettings before = RealtimeControls.store().current();
        RealtimeSettings after = before
                .withRenderClientSidePortals(request.flag("renderClientSidePortals",
                        before.renderClientSidePortals()))
                .withMaxRenderDistance((int) request.number("maxRenderDistance",
                        before.maxRenderDistance()))
                .withDistantHorizons(request.flag("distantHorizons", before.distantHorizons()))
                .withRenderServerSidePortals(request.flag("renderServerSidePortals",
                        before.renderServerSidePortals()))
                .withSpectatorPass(request.flag("spectatorPass", before.spectatorPass()));
        boolean changed = !after.equals(before);
        if (changed) {
            RealtimeControls.store().save(after);
        }
        return DevResponse.realtime(changed, after.toJson(), held());
    }

    /**
     * What the local view actually holds right now: the framed portals and the
     * destination chunks that have arrived for each dimension. A count, not an
     * absence of errors — the plan's own bar for the destination world.
     */
    private static String held() {
        Json.Obj chunks = Json.obj();
        DestinationChunks.counts().forEach((destination, count) ->
                chunks.num(destination.toString(), count));
        Json.Obj frames = Json.obj();
        for (com.customdimensions.client.CompanionPayloads.PortalFrame frame : PortalFrames.all()) {
            frames.raw(frame.apertureOrigin().toShortString(), Json.obj()
                    .str("destination", frame.destination().toString())
                    .str("dimensionType", frame.dimensionType().toString())
                    .raw("offset", Json.numbers(frame.dx(), frame.dy(), frame.dz()))
                    .toString());
        }
        Json.Obj worlds = Json.obj();
        DestinationWorlds.loadedCounts().forEach((destination, loaded) ->
                worlds.num(destination.toString(), loaded));
        return Json.obj()
                .num("frames", PortalFrames.count())
                .num("slabProjections", ProjectionStore.count())
                .num("destinationWorlds", DestinationWorlds.count())
                .raw("chunksInWorld", worlds.toString())
                .num("destinationChunks", DestinationChunks.total())
                .raw("chunksByDimension", chunks.toString())
                .raw("framesByAperture", frames.toString())
                .toString();
    }

    /**
     * A walk is measured, not assumed: before and after state, a screenshot of
     * each, and the tracker's own verdict for why it ended.
     */
    private static String walk(DevRequest request) {
        double blocks = request.number("blocks", 1);
        long timeoutMs = (long) request.number("timeoutMs", DEFAULT_WALK_TIMEOUT_MS);
        int stallTicks = (int) request.number("stallTicks", DEFAULT_STALL_TICKS);
        String shots = request.text("shots", null);

        String before = onRender(DevServer::state);
        String beforeShot = onRender(() -> DevShots.json(
                MinecraftClient.getInstance(), DevShots.allocate(shots, "walk", "before")));

        CompletableFuture<WalkTracker> running = onRender(() -> DevBridge.startWalk(
                MinecraftClient.getInstance(), blocks, stallTicks,
                WalkTracker.ticksFromMillis(timeoutMs)));
        WalkTracker tracker = await(running, timeoutMs + WALK_SLACK_MS);

        String after = onRender(DevServer::state);
        String afterShot = onRender(() -> DevShots.json(
                MinecraftClient.getInstance(), DevShots.allocate(shots, "walk", "after")));

        return DevResponse.walk(tracker, blocks, before, after, beforeShot, afterShot);
    }

    private static String sneak(DevRequest request) {
        int ticks = (int) request.number("ticks", 20);
        String shots = request.text("shots", null);
        return measured("sneak", shots, () -> {
            CompletableFuture<Void> held = onRender(() ->
                    DevBridge.startHold(MinecraftClient.getInstance(), "sneak", ticks));
            await(held, ticks * 50L + RENDER_TIMEOUT_MS);
            return Json.obj().num("ticks", ticks).toString();
        });
    }

    private static String instant(DevRequest request) {
        String action = request.action();
        String shots = request.text("shots", null);
        return measured(action, shots, () -> onRender(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            switch (action) {
                case "look" -> DevBridge.look(client,
                        request.number("yaw", client.player == null ? 0 : client.player.getYaw()),
                        request.number("pitch", client.player == null ? 0 : client.player.getPitch()));
                case "use" -> DevBridge.use(client);
                case "key" -> DevBridge.tap(client,
                        request.value() != null ? request.value() : request.text("name", null));
                default -> throw new IllegalStateException("unhandled action " + action);
            }
            return "{}";
        }));
    }

    /** Before and after, both ways, for every action that is not a walk. */
    private static String measured(String action, String shots, Supplier<String> body) {
        String before = onRender(DevServer::state);
        String beforeShot = onRender(() -> DevShots.json(
                MinecraftClient.getInstance(), DevShots.allocate(shots, action, "before")));
        String detail = body.get();
        String after = onRender(DevServer::state);
        String afterShot = onRender(() -> DevShots.json(
                MinecraftClient.getInstance(), DevShots.allocate(shots, action, "after")));
        return DevResponse.action(action, detail, before, after, beforeShot, afterShot);
    }

    // -------------------------------------------------------------- plumbing

    /** Runs on the render thread; a wait that expires is an error, never a hang. */
    private static <V> V onRender(Supplier<V> work) {
        return await(MinecraftClient.getInstance().submit(work), RENDER_TIMEOUT_MS);
    }

    private static <V> V await(CompletableFuture<V> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new DevTimeout(timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the render thread");
        }
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Carries the bound that expired, so the answer can name it. */
    private static final class DevTimeout extends RuntimeException {

        private final long timeoutMs;

        DevTimeout(long timeoutMs) {
            super("timed out after " + timeoutMs + "ms waiting for the render thread");
            this.timeoutMs = timeoutMs;
        }

        long timeoutMs() {
            return this.timeoutMs;
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
