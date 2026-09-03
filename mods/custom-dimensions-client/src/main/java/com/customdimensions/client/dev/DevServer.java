package com.customdimensions.client.dev;

import com.customdimensions.client.CustomDimensionsClient;
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

    static void start(int port) {
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
                    "Dev control surface listening on 127.0.0.1:{}", port);
        } catch (IOException e) {
            CustomDimensionsClient.LOGGER.error(
                    "Dev control surface failed to bind port {}", port, e);
            server = null;
        }
    }

    // ---------------------------------------------------------------- routes

    private static void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            switch (path) {
                case "/health" -> send(exchange, 200, health());
                case "/state" -> send(exchange, 200, onRender(DevServer::state));
                case "/screenshot" -> screenshot(exchange);
                case "/input" -> input(exchange);
                default -> send(exchange, 404, error("no such endpoint: " + path));
            }
        } catch (RuntimeException e) {
            CustomDimensionsClient.LOGGER.warn("Dev control surface {} failed", path, e);
            send(exchange, 500, error(String.valueOf(e)));
        }
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

    private static void screenshot(HttpExchange exchange) throws IOException {
        ScreenshotRequest request = ScreenshotRequest.parse(body(exchange));
        if (!request.ok()) {
            send(exchange, 400, error(request.error()));
            return;
        }
        send(exchange, 200, onRender(() ->
                DevShots.json(MinecraftClient.getInstance(), request.path())));
    }

    // ----------------------------------------------------------------- input

    private static void input(HttpExchange exchange) throws IOException {
        DevRequest request = DevRequest.parse(body(exchange));
        if (!request.ok()) {
            send(exchange, 400, error(request.error()));
            return;
        }
        try {
            send(exchange, 200, switch (request.action()) {
                case "walk" -> walk(request);
                case "sneak" -> sneak(request);
                default -> instant(request);
            });
        } catch (JsonReader.Malformed e) {
            send(exchange, 400, error(e.getMessage()));
        }
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
            throw new IllegalStateException(
                    "timed out after " + timeoutMs + "ms waiting for the render thread");
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

    private static String error(String message) {
        return DevResponse.error(message);
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
