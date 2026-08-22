package com.customdimensions.web;

import com.customdimensions.MultiverseServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The seed tool's HTTP port. The browser talks to the mod directly.
 *
 * <p>Rolling, rendering, trying out and picking are method calls inside this
 * process — the one that owns the registries and the live
 * {@link MinecraftServer}. There is no RCON hop, no chat command and no
 * static regeneration step between the browser and the code that answers it.
 *
 * <p>Reads come off the request thread; anything that touches a world is
 * queued onto the server thread by the code it calls.
 *
 * <p>Off unless {@code SEED_VIEWER_PORT} names a port: only
 * {@code docker-compose.local.yml} sets one, and only it publishes the port
 * outside the container.
 */
public final class SeedServer {

    private static HttpServer server;

    private SeedServer() {
    }

    public static void start(MinecraftServer minecraftServer) {
        int port = configuredPort();
        if (port <= 0) {
            MultiverseServer.LOGGER.info("Seed viewer disabled (SEED_VIEWER_PORT unset or 0)");
            return;
        }
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> route(minecraftServer, exchange));
            // One thread: every handler is a short read off disk or memory,
            // and a pool here would only invite two rolls at once.
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "customdim-seed-viewer");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            MultiverseServer.LOGGER.info("Seed viewer listening on port {}", port);
            // What the pack looks like RIGHT NOW, before anybody rolls
            // anything: every dimension has a configured world, and on a fresh
            // bank its seed is the only one there is to score or look at.
            RollPipeline.primeNamedSeeds(minecraftServer);
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Seed viewer failed to bind port {}", port, e);
            server = null;
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * Unset or unreadable means OFF: the listener has no authentication and
     * can teleport a player. Both compose files set it explicitly.
     */
    static int configuredPort() {
        return portFrom(System.getenv("SEED_VIEWER_PORT"));
    }

    static int portFrom(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            MultiverseServer.LOGGER.warn(
                    "SEED_VIEWER_PORT is not a number ({}) — seed viewer disabled", raw);
            return 0;
        }
    }

    // ------------------------------------------------------------------ routes

    private static void route(MinecraftServer minecraftServer, HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        try {
            if (path.equals("/") || path.isEmpty()) {
                send(exchange, 200, "text/html; charset=utf-8",
                        ViewerPage.render(minecraftServer).getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/assets/")) {
                asset(exchange, path.substring("/assets/".length()));
            } else if (path.equals("/api/bank")) {
                send(exchange, 200, "application/json; charset=utf-8",
                        BankView.json(minecraftServer).getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/pipeline-status")) {
                send(exchange, 200, "application/json; charset=utf-8",
                        RollPipeline.statusJson().getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/pipeline/start")) {
                startRoll(minecraftServer, exchange);
            } else if (path.equals("/pipeline/stop")) {
                RollPipeline.stop();
                send(exchange, 200, "application/json; charset=utf-8",
                        "{\"ok\": true}".getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/render/low/pause")) {
                RenderQueue.setLowPaused(true);
                sendOk(exchange);
            } else if (path.equals("/render/low/resume")) {
                RenderQueue.setLowPaused(false);
                sendOk(exchange);
            } else if (path.equals("/render/high/pause")) {
                RenderQueue.setHighPaused(true);
                sendOk(exchange);
            } else if (path.equals("/render/high/resume")) {
                RenderQueue.setHighPaused(false);
                sendOk(exchange);
            } else if (path.equals("/focus")) {
                // Opening a dimension in the viewer says it is the one being
                // looked at, so it is the one worth spending seeds and cores
                // on. An empty slug clears it. Nothing is cancelled either
                // way — see RenderQueue.focus.
                String slug = "";
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    if (!body.isBlank()) {
                        com.google.gson.JsonObject json = com.google.gson.JsonParser
                                .parseString(body).getAsJsonObject();
                        if (json.has("dim") && !json.get("dim").isJsonNull()) {
                            slug = json.get("dim").getAsString();
                        }
                    }
                } catch (RuntimeException ignored) {
                    // A malformed focus request clears the focus rather than
                    // failing the page: this is a hint, not an instruction.
                }
                RollPipeline.focus(minecraftServer, slug);
                send(exchange, 200, "application/json; charset=utf-8",
                        ("{\"ok\": true, \"focus\": "
                                + com.customdimensions.facts.Json.quote(RollPipeline.focused())
                                + "}").getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/tryout")) {
                tryOut(minecraftServer, exchange);
            } else if (path.equals("/tryout/back")) {
                tryOutBack(minecraftServer, exchange);
            } else if (path.equals("/tryout/status")) {
                send(exchange, 200, "application/json; charset=utf-8",
                        tryOutStatus(minecraftServer).getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/shortlist")) {
                shortlist(minecraftServer, exchange);
            } else if (path.equals("/pick")) {
                pick(minecraftServer, exchange);
            } else if (path.equals("/render")) {
                renderRequest(minecraftServer, exchange);
            } else if (path.startsWith("/census/")) {
                census(minecraftServer, exchange, path.substring("/census/".length()));
            } else if (path.startsWith("/renders/")) {
                render(minecraftServer, exchange, path.substring("/renders/".length()));
            } else {
                // Deep links (/the-nether, /the-nether/<seed>) are the same
                // page; route.js reads the URL and opens what it names.
                send(exchange, 200, "text/html; charset=utf-8",
                        ViewerPage.render(minecraftServer).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            MultiverseServer.LOGGER.error("Seed viewer {} failed", path, e);
            try {
                send(exchange, 500, "text/plain; charset=utf-8",
                        String.valueOf(e).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The client is gone; nothing to report to.
            }
        }
    }

    /**
     * Starts a roll from the browser. The body is the roller controls' own
     * shape: {@code {"count": n, "dim": "<slug>"|null, "dims": ["<slug>", …]}}.
     *
     * <p>{@code dims} is the Filtered option: the dimensions the grid is
     * showing, in the order it is showing them. Present and non-empty, it
     * wins over {@code dim} — the order is the whole point of it.
     */
    private static void startRoll(MinecraftServer minecraftServer, HttpExchange exchange)
            throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        // A per-dimension seed BUDGET, not a plan: tier 1 screens the whole
        // pool and tier 2 measures every shortlisted seed, so nothing here
        // stops a roll early. Five thousand is what gives the worst yields in
        // the pack — roughly one candidate per 250 seeds — a fair shot at a
        // full board.
        int count = 5000;
        String dim = null;
        List<String> dims = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonObject json = body.isBlank() ? new com.google.gson.JsonObject()
                    : com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (json.has("count") && !json.get("count").isJsonNull()) {
                count = json.get("count").getAsInt();
            }
            if (json.has("dim") && !json.get("dim").isJsonNull()) {
                dim = json.get("dim").getAsString();
            }
            if (json.has("dims") && json.get("dims").isJsonArray()) {
                for (com.google.gson.JsonElement el : json.getAsJsonArray("dims")) {
                    if (el != null && el.isJsonPrimitive()) {
                        dims.add(el.getAsString());
                    }
                }
            }
        } catch (RuntimeException e) {
            send(exchange, 400, "application/json; charset=utf-8",
                    ("{\"error\": \"unreadable request body\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String refusal = RollPipeline.start(minecraftServer, dim, dims, count);
        String answer = refusal == null ? "{\"ok\": true}"
                : "{\"error\": " + com.customdimensions.facts.Json.quote(refusal) + "}";
        send(exchange, 200, "application/json; charset=utf-8", answer.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Try a candidate out: build its throwaway world and step into it.
     *
     * <p>Answers {@code ready:false} while the world is still being built —
     * creation is drained on the server tick, never from this thread. The
     * browser calls again; the second call teleports.
     */
    private static void tryOut(MinecraftServer minecraftServer, HttpExchange exchange)
            throws IOException {
        com.google.gson.JsonObject body = readJson(exchange);
        String slug = body.has("dim") ? body.get("dim").getAsString() : null;
        long seed = body.has("seed") ? body.get("seed").getAsLong() : 0L;
        com.customdimensions.config.DimensionConfig def = slug == null ? null
                : BankView.resolve(slug);
        if (def == null) {
            sendJson(exchange, "{\"error\": \"no configured dimension " + escape(slug) + "\"}");
            return;
        }
        net.minecraft.server.network.ServerPlayerEntity player = driver(minecraftServer);
        if (player == null) {
            sendJson(exchange, "{\"error\": \"join the server first — a try-out is a place you fly "
                    + "around in, so somebody has to be online to fly\"}");
            return;
        }
        net.minecraft.util.Identifier worldId = com.customdimensions.tryout.TryOut.request(
                minecraftServer, def.getDimensionIdentifier(), seed, player.getUuid());
        if (worldId == null) {
            // A refusal, not a pending state. Reported as {"ready": false}
            // this was indistinguishable from "the world is still building",
            // so the browser polled a try-out that would never arrive.
            sendJson(exchange, "{\"error\": \"cannot build a try-out for "
                    + escape(def.getDimensionId()) + " — its config could not be resolved\"}");
            return;
        }
        boolean ready = com.customdimensions.tryout.TryOut.isReady(minecraftServer, worldId);
        if (ready) {
            // Teleporting mutates the world the tick loop is iterating, so it
            // goes through the server's own queue rather than this thread.
            minecraftServer.execute(() ->
                    com.customdimensions.tryout.TryOut.enter(player, worldId));
        }
        sendJson(exchange, "{\"ok\": true, \"ready\": " + ready + ", \"world\": "
                + com.customdimensions.facts.Json.quote(String.valueOf(worldId)) + "}");
    }

    private static void tryOutBack(MinecraftServer minecraftServer, HttpExchange exchange)
            throws IOException {
        net.minecraft.server.network.ServerPlayerEntity player = driver(minecraftServer);
        if (player == null) {
            sendJson(exchange, "{\"error\": \"no player online\"}");
            return;
        }
        minecraftServer.execute(() -> com.customdimensions.tryout.TryOut.leave(player));
        sendJson(exchange, "{\"ok\": true}");
    }

    /** Which try-outs are live, and which one the player is standing in. */
    private static String tryOutStatus(MinecraftServer minecraftServer) {
        net.minecraft.server.network.ServerPlayerEntity player = driver(minecraftServer);
        String inside = "";
        if (player != null) {
            String world = player.getWorld().getRegistryKey().getValue().toString();
            if (world.contains(":" + com.customdimensions.tryout.TryOut.PATH_PREFIX)) {
                inside = world;
            }
        }
        StringBuilder b = new StringBuilder("{\"player\": ")
                .append(com.customdimensions.facts.Json.quote(
                        player == null ? "" : player.getName().getString()))
                .append(", \"online\": ")
                .append(minecraftServer.getPlayerManager().getPlayerList().size())
                .append(", \"inside\": ").append(com.customdimensions.facts.Json.quote(inside))
                .append(", \"sessions\": [");
        List<com.customdimensions.tryout.TryOut.Session> sessions =
                com.customdimensions.tryout.TryOut.sessions();
        for (int i = 0; i < sessions.size(); i++) {
            com.customdimensions.tryout.TryOut.Session s = sessions.get(i);
            b.append(i > 0 ? ", " : "").append("{\"dimension\": ")
                    .append(com.customdimensions.facts.Json.quote(s.dimension()))
                    .append(", \"seed\": ").append(s.seed())
                    .append(", \"world\": ")
                    .append(com.customdimensions.facts.Json.quote(s.worldId().toString()))
                    .append("}");
        }
        return b.append("]}\n").toString();
    }

    /**
     * Keeps a seed, or stops keeping it.
     *
     * <p>A shortlisted seed stays on the dimension's roster for good: it is
     * drawn, it survives a re-roll that would otherwise push it off the
     * ranking, and it does not take one of the ranked places. Reconciled
     * immediately so the render is queued before the browser reloads, rather
     * than waiting for the next roll to notice.
     */
    private static void shortlist(MinecraftServer minecraftServer, HttpExchange exchange)
            throws IOException {
        com.google.gson.JsonObject body = readJson(exchange);
        String slug = body.has("dim") ? body.get("dim").getAsString() : null;
        com.customdimensions.config.DimensionConfig def = slug == null ? null
                : BankView.resolve(slug);
        if (def == null) {
            sendJson(exchange, "{\"ok\": false, \"error\": \"no configured dimension "
                    + escape(slug) + "\"}");
            return;
        }
        long seed;
        try {
            seed = body.has("seed") ? Long.parseLong(body.get("seed").getAsString().trim()) : 0L;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            sendJson(exchange, "{\"ok\": false, \"error\": \"unreadable seed\"}");
            return;
        }
        boolean add = !"remove".equalsIgnoreCase(
                body.has("action") ? body.get("action").getAsString() : "add");
        boolean now = com.customdimensions.roll.Shortlist.set(
                def.getDimensionIdentifier().toString(), seed, add);
        RenderQueue.reconcile(minecraftServer, def);
        sendJson(exchange, "{\"ok\": true, \"shortlisted\": " + now + "}");
    }

    private static void pick(MinecraftServer minecraftServer, HttpExchange exchange)
            throws IOException {
        com.google.gson.JsonObject body = readJson(exchange);
        String slug = body.has("dim") ? body.get("dim").getAsString() : null;
        long seed = body.has("seed") ? body.get("seed").getAsLong() : 0L;
        if (slug == null) {
            sendJson(exchange, "{\"error\": \"no dimension named\"}");
            return;
        }
        Picker.Result result = Picker.pick(minecraftServer, slug, seed);
        // A refusal answers 409 so the browser's own `res.ok` check sees it —
        // a 200 carrying {"ok": false} reads as saved and reloads the page.
        send(exchange, result.ok() ? 200 : 409, "application/json; charset=utf-8",
                ("{\"ok\": " + result.ok() + ", \"message\": "
                        + com.customdimensions.facts.Json.quote(result.message()) + "}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One candidate's structure census — what the modal's structures panel
     * reads. Counts and nearest distances come straight out of the file the
     * roll wrote; the per-site positions and their assigned structure ids are
     * recomputed, because the bank stores no coordinate per placement.
     *
     * <p>The recomputation is exact, not an estimate: same prefiltered set
     * list, same pool, same seed and same weighted pick the measurement used,
     * so every id and count here equals the banked {@code byStructure}.
     */
    private static void census(MinecraftServer minecraftServer, HttpExchange exchange, String rest)
            throws IOException {
        int slash = rest.lastIndexOf('/');
        if (slash < 0) {
            sendJson(exchange, "{\"ok\": false, \"error\": \"expected /census/<dimension>/<seed>\"}");
            return;
        }
        // The page spells a slug with dashes; the config spells it with
        // underscores.
        String slug = rest.substring(0, slash).replace('-', '_');
        String seed = rest.substring(slash + 1);
        sendJson(exchange, BankView.censusJson(minecraftServer, slug, seed));
    }

    /** Draws a candidate's map on demand — the high-res one the modal offers. */
    private static void renderRequest(MinecraftServer minecraftServer, HttpExchange exchange)
            throws IOException {
        com.google.gson.JsonObject body = readJson(exchange);
        String slug = body.has("dim") ? body.get("dim").getAsString() : null;
        long seed = body.has("seed") ? body.get("seed").getAsLong() : 0L;
        boolean highres = body.has("resolution")
                && "highres".equalsIgnoreCase(body.get("resolution").getAsString());
        if (slug == null) {
            sendJson(exchange, "{\"error\": \"no dimension named\"}");
            return;
        }
        String refusal = RollPipeline.render(minecraftServer, slug, seed, highres);
        sendJson(exchange, refusal == null ? "{\"ok\": true}"
                : "{\"error\": " + com.customdimensions.facts.Json.quote(refusal) + "}");
    }

    /**
     * The person driving this tool.
     *
     * <p>Refusing unless EXACTLY one player is connected made the button
     * unusable whenever anything else held a connection — a Carpet fake
     * player left over from a test does, and this repo's own notes say those
     * survive every attempt to remove them short of an {@code mc} restart. A
     * player already standing in a try-out is unambiguously the driver;
     * otherwise the first connected one is, and {@link #tryOutStatus} names
     * whoever was chosen so it is never a silent guess.
     */
    private static net.minecraft.server.network.ServerPlayerEntity driver(MinecraftServer server) {
        List<net.minecraft.server.network.ServerPlayerEntity> players =
                server.getPlayerManager().getPlayerList();
        if (players.isEmpty()) {
            return null;
        }
        for (net.minecraft.server.network.ServerPlayerEntity p : players) {
            if (p.getWorld().getRegistryKey().getValue().getPath()
                    .startsWith(com.customdimensions.tryout.TryOut.PATH_PREFIX)) {
                return p;
            }
        }
        return players.get(0);
    }

    private static com.google.gson.JsonObject readJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            return body.isBlank() ? new com.google.gson.JsonObject()
                    : com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return new com.google.gson.JsonObject();
        }
    }

    private static void sendJson(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendOk(HttpExchange exchange) throws IOException {
        sendJson(exchange, "{\"ok\": true}");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }

    /** Static viewer files, served straight out of the jar. */
    private static void asset(HttpExchange exchange, String name) throws IOException {
        if (name.contains("..") || name.contains("/")) {
            send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = SeedServer.class.getResourceAsStream("/seed-viewer/web/" + name)) {
            if (in == null) {
                send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, contentType(name), in.readAllBytes());
        }
    }

    /**
     * A candidate's PNG. The URL names the dimension and seed a person can
     * read; the input hash it actually lives under is resolved here, so a
     * link never has to carry one.
     */
    private static void render(MinecraftServer minecraftServer, HttpExchange exchange, String rest)
            throws IOException {
        int slash = rest.lastIndexOf('/');
        if (slash < 0 || !rest.endsWith(".png")) {
            send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String dimension = rest.substring(0, slash);
        String file = rest.substring(slash + 1, rest.length() - ".png".length());
        boolean hires = file.endsWith("_hires");
        String seed = hires ? file.substring(0, file.length() - "_hires".length()) : file;
        Path png = BankView.renderPath(minecraftServer, dimension, seed, hires);
        if (png == null || !Files.isRegularFile(png)) {
            send(exchange, 404, "text/plain", "no render".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(exchange, 200, "image/png", Files.readAllBytes(png));
    }

    private static String contentType(String name) {
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
