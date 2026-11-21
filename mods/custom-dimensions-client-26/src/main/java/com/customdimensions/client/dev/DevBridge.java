package com.customdimensions.client.dev;

import com.customdimensions.client.CompanionClient;
import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.ShaderGuard;
import com.customdimensions.client.feed.DecodedChunk;
import com.customdimensions.client.feed.Feed;
import com.customdimensions.client.view.EntityPass;
import com.customdimensions.client.view.PortalRenderer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The companion's dev bridge: loopback HTTP for the end-to-end harness, off
 * unless {@code -Dcustomdimensions.devBridge=<port>} names a port. Every read
 * runs on the client thread; a reply that is not ready in
 * {@value #WAIT_SECONDS} s is a 503 with {@code "retryable": true}.
 * <ul>
 * <li>{@code GET /health}: the mod, Minecraft and the client tick.</li>
 * <li>{@code GET /state}: the client's own view: world, screen, fps, the player, the feed and renderer counts.</li>
 * <li>{@code POST /screenshot {"path"}}: the game's framebuffer as a PNG at that path, with its size.</li>
 * <li>{@code POST /input}: one of {@code look {yaw, pitch}}, {@code walk {blocks, timeoutMs}},
 * {@code use {}} (the main hand on the block in the crosshair, else in the air), {@code slot {index}}.</li>
 * <li>{@code GET /portals}: per fed portal, whether its far side is being drawn, where it leads, what of it is held,
 * the sky and fog colours it is drawn with, and the fed entities drawn through it in the last second
 * ({@code entitiesDrawn}, {@code entitiesDrawnTypes}).</li>
 * <li>{@code GET /portal-census[?origin=x,y,z]}: {@link ApertureCensus} through the nearest fed portal, or the one at
 * that aperture origin.</li>
 * </ul>
 */
public final class DevBridge {
    public static final String PROPERTY = "customdimensions.devBridge";
    static final int WAIT_SECONDS = 10;
    /** A preview is "being drawn" when its last emit is this recent. */
    static final long DRAWING_NANOS = 1_000_000_000L;

    private static int clientTick;
    private static Walk walk;

    private record Answer(int code, JsonObject body) {
    }

    private static final class Walk {
        final WalkProgress progress;
        final String dimension;
        final CompletableFuture<JsonObject> done = new CompletableFuture<>();

        Walk(WalkProgress progress, String dimension) {
            this.progress = progress;
            this.dimension = dimension;
        }
    }

    private DevBridge() {
    }

    public static void register() {
        String value = System.getProperty(PROPERTY, "");
        if (value.isEmpty()) {
            return;
        }
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            CompanionClient.LOG.warn("dev-bridge: {}={} is not a port; the bridge stays off", PROPERTY, value);
            return;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 8);
            server.createContext("/", DevBridge::route);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "customdimensions-dev-bridge");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            CompanionClient.LOG.info("dev-bridge: listening on 127.0.0.1:{}", port);
        } catch (IOException e) {
            CompanionClient.LOG.warn("dev-bridge: could not listen on {}: {}", port, e.getMessage());
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(DevBridge::tick);
    }

    // ------------------------------------------------------------------ HTTP

    private static void route(HttpExchange exchange) throws IOException {
        Answer answer;
        try {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            boolean post = "POST".equals(exchange.getRequestMethod());
            JsonObject body = post ? readBody(exchange.getRequestBody()) : new JsonObject();
            answer = switch (path) {
                case "/health" -> onClient(DevBridge::health);
                case "/state" -> onClient(DevBridge::state);
                case "/portals" -> onClient(DevBridge::portals);
                case "/portal-census" -> onClient(() -> census(query));
                case "/screenshot" -> post ? screenshot(body) : error(405, "POST a {\"path\": ...} body");
                case "/input" -> post ? input(body) : error(405, "POST an input body");
                default -> error(404, "no route " + path);
            };
        } catch (RuntimeException e) {
            answer = error(400, e.toString());
        }
        byte[] bytes = (new GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(answer.body()) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.code(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static JsonObject readBody(InputStream in) throws IOException {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
    }

    private static Answer onClient(Supplier<JsonObject> read) {
        return await(Minecraft.getInstance().submit(read), WAIT_SECONDS);
    }

    private static Answer await(CompletableFuture<JsonObject> future, long seconds) {
        try {
            return new Answer(200, future.get(seconds, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            JsonObject o = new JsonObject();
            o.addProperty("error", "the client thread did not answer within " + seconds + " s");
            o.addProperty("retryable", true);
            return new Answer(503, o);
        } catch (Exception e) {
            return error(500, String.valueOf(e.getCause() == null ? e : e.getCause()));
        }
    }

    private static Answer error(int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        o.addProperty("retryable", false);
        return new Answer(code, o);
    }

    // ------------------------------------------------------------------ reads

    private static JsonObject health() {
        JsonObject o = new JsonObject();
        o.addProperty("mod", CompanionClient.ID + " " + CompanionClient.version());
        o.addProperty("mc", SharedConstants.getCurrentVersion().name());
        o.add("tick", num(clientTick));
        return o;
    }

    private static JsonObject state() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        JsonObject client = new JsonObject();
        Screen screen = mc.gui.screen();
        client.addProperty("worldLoaded", mc.level != null && mc.player != null);
        client.addProperty("currentScreen", screen == null ? null : screen.getClass().getName());
        client.addProperty("arrivalScreen", screen instanceof LevelLoadingScreen);
        client.add("fps", num(mc.getFps()));
        client.add("tick", num(clientTick));
        o.add("client", client);
        o.add("player", player(mc));
        JsonObject companion = new JsonObject();
        companion.addProperty("enabled", CompanionClient.enabled());
        companion.addProperty("drawing", CompanionClient.drawing());
        companion.addProperty("shaderBlocks", ShaderGuard.blocks());
        companion.addProperty("rendererFailed", PortalRenderer.failed());
        o.add("companion", companion);
        JsonObject feed = new JsonObject();
        feed.add("frames", num(Feed.frames().size()));
        feed.add("framesReceived", num(Feed.framesReceived));
        feed.add("chunksReceived", num(Feed.chunksReceived));
        feed.add("chunksHeld", num(Feed.chunksHeld()));
        feed.add("entitySnapshots", num(Feed.entitySnapshots));
        feed.add("entitiesHeld", num(Feed.entitiesHeld()));
        feed.add("handovers", num(Feed.handovers));
        feed.add("clears", num(Feed.clears));
        o.add("feed", feed);
        JsonObject renderer = new JsonObject();
        renderer.add("openingsDrawn", num(PortalRenderer.openingsDrawn));
        renderer.add("facesDrawn", num(PortalRenderer.facesDrawn));
        renderer.add("lastFaces", num(PortalRenderer.lastFaces));
        o.add("renderer", renderer);
        return o;
    }

    private static JsonElement player(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            JsonObject o = new JsonObject();
            o.addProperty("absent", mc.level == null ? "no world is loaded" : "the world has no local player");
            return o;
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getName().getString());
        o.addProperty("dimension", mc.level.dimension().identifier().toString());
        o.add("pos", triple(p.getX(), p.getY(), p.getZ()));
        BlockPos b = p.blockPosition();
        o.add("blockPos", ints(b.getX(), b.getY(), b.getZ()));
        o.add("yaw", num(p.getYRot()));
        o.add("pitch", num(p.getXRot()));
        o.addProperty("pose", p.getPose().name());
        JsonObject status = new JsonObject();
        status.addProperty("onGround", p.onGround());
        status.addProperty("sneaking", p.isShiftKeyDown());
        status.addProperty("sprinting", p.isSprinting());
        status.addProperty("swimming", p.isSwimming());
        status.addProperty("inWater", p.isInWater());
        status.addProperty("submerged", p.isUnderWater());
        status.addProperty("crawling", p.isVisuallyCrawling());
        status.addProperty("inPortalCooldown", p.isOnPortalCooldown());
        o.add("status", status);
        JsonObject vitals = new JsonObject();
        vitals.add("health", num(p.getHealth()));
        vitals.add("food", num(p.getFoodData().getFoodLevel()));
        o.add("vitals", vitals);
        JsonObject held = new JsonObject();
        held.add("mainHand", item(p.getMainHandItem()));
        held.add("offHand", item(p.getOffhandItem()));
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) {
            hotbar.add(item(p.getInventory().getItem(i)));
        }
        held.add("hotbar", hotbar);
        held.add("selectedSlot", num(p.getInventory().getSelectedSlot()));
        o.add("held", held);
        HitResult hit = mc.hitResult;
        o.add("target", hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK
                ? ints(bh.getBlockPos().getX(), bh.getBlockPos().getY(), bh.getBlockPos().getZ()) : JsonNull.INSTANCE);
        return o;
    }

    private static JsonElement item(ItemStack stack) {
        if (stack.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        o.add("count", num(stack.getCount()));
        o.add("damage", stack.isDamageableItem() ? num(stack.getDamageValue()) : JsonNull.INSTANCE);
        o.add("maxDamage", stack.isDamageableItem() ? num(stack.getMaxDamage()) : JsonNull.INSTANCE);
        return o;
    }

    /** Per fed portal: whether its far side is drawn now, where it leads, what of it is held, its colours. */
    private static JsonObject portals() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        Vec3 eye = mc.player == null ? null : mc.player.getEyePosition();
        long now = System.nanoTime();
        for (CompanionPayloads.PortalFrame frame : Feed.frames().values()) {
            JsonObject f = new JsonObject();
            BlockPos origin = frame.apertureOrigin();
            f.add("apertureOrigin", ints(origin.getX(), origin.getY(), origin.getZ()));
            f.addProperty("destination", frame.destination().toString());
            f.addProperty("dimensionType", frame.dimensionType().toString());
            f.add("portalAxis", num(frame.portalAxis()));
            f.add("cells", num(frame.aperture().size()));
            f.add("offset", ints(frame.dx(), frame.dy(), frame.dz()));
            f.add("distance", eye == null ? JsonNull.INSTANCE : num(Math.sqrt(origin.distToCenterSqr(eye))));
            f.add("skyColor", colour(frame.skyColor()));
            f.add("fogColor", colour(frame.fogColor()));
            Feed.Destination d = Feed.destination(frame.destination());
            f.add("chunksHeld", num(d == null ? 0 : d.packets.size()));
            JsonArray entities = new JsonArray();
            if (d != null) {
                for (Feed.Shown s : d.entities.values()) {
                    JsonObject e = new JsonObject();
                    e.add("id", num(s.id()));
                    e.addProperty("type", s.type() == null ? null : s.type().toString());
                    e.add("pos", triple(s.x(), s.y(), s.z()));
                    double[] at = EntityPass.drawnPosition(frame.destination(), s.id());
                    e.add("drawnPos", at == null ? JsonNull.INSTANCE : triple(at[0], at[1], at[2]));
                    entities.add(e);
                }
            }
            f.add("entities", entities);
            PortalRenderer.Drawn drawn = PortalRenderer.drawn(origin);
            boolean drawing = drawn != null && now - drawn.nanos() < DRAWING_NANOS && drawn.faces() > 0;
            f.addProperty("drawing", drawing);
            f.add("lastDrawnMsAgo", drawn == null ? JsonNull.INSTANCE : num((now - drawn.nanos()) / 1.0e6));
            f.add("lastFaces", drawn == null ? JsonNull.INSTANCE : num(drawn.faces()));
            EntityPass.Seen seen = EntityPass.drawn(origin);
            boolean recent = seen != null && now - seen.nanos() < DRAWING_NANOS;
            f.add("entitiesDrawn", num(recent ? seen.count() : 0));
            JsonObject types = new JsonObject();
            if (recent) {
                seen.types().forEach((k, v) -> types.add(k, num(v)));
            }
            f.add("entitiesDrawnTypes", types);
            f.add("entityQuadsDrawn", num(recent ? seen.quads() : 0));
            f.addProperty("entitiesDrawnReason", EntityPass.failed() ? "the entity pass failed and is off"
                    : !CompanionClient.drawing() ? "the far side is not drawn"
                    : seen == null ? "no entity has been drawn through this opening"
                    : !recent ? "no frame in the last second drew this opening's entities"
                    : seen.count() == 0 ? "the fed entities lie outside the opening's view" : "drawn");
            list.add(f);
        }
        o.add("portals", list);
        o.addProperty("drawingEnabled", CompanionClient.drawing());
        return o;
    }

    private static JsonObject census(String query) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        if (mc.player == null || mc.level == null) {
            o.addProperty("error", "no world is loaded");
            return o;
        }
        BlockPos wanted = originFrom(query);
        Vec3 eye = mc.player.getEyePosition();
        CompanionPayloads.PortalFrame chosen = null;
        double best = Double.MAX_VALUE;
        for (CompanionPayloads.PortalFrame frame : Feed.frames().values()) {
            double d = frame.apertureOrigin().distToCenterSqr(eye);
            if ((wanted == null || wanted.equals(frame.apertureOrigin())) && d < best) {
                best = d;
                chosen = frame;
            }
        }
        if (chosen == null) {
            o.addProperty("error", wanted == null ? "no portal is fed" : "no fed portal at " + wanted.toShortString());
            return o;
        }
        CompanionPayloads.PortalFrame frame = chosen;
        Feed.Destination d = Feed.destination(frame.destination());
        ClientLevel level = mc.level;
        ApertureCensus.Scene scene = new ApertureCensus.Scene() {
            @Override
            public String block(int x, int y, int z) {
                DecodedChunk c = d == null ? null : PortalRenderer.decoded(level, frame, d, x, z);
                return c == null ? null : BuiltInRegistries.BLOCK.getKey(c.state(x, y, z).getBlock()).toString();
            }

            @Override
            public String biome(int x, int y, int z) {
                DecodedChunk c = d == null ? null : PortalRenderer.decoded(level, frame, d, x, z);
                return c == null ? null : c.biome(x, y, z);
            }
        };
        List<int[]> cells = new ArrayList<>();
        frame.aperture().forEach(p -> cells.add(new int[] {p.getX(), p.getY(), p.getZ()}));
        ApertureCensus.Result r = ApertureCensus.measure(new double[] {eye.x, eye.y, eye.z}, cells, frame.portalAxis(),
                new int[] {frame.dx(), frame.dy(), frame.dz()}, scene, ApertureCensus.GRID, 40);
        BlockPos origin = frame.apertureOrigin();
        o.add("apertureOrigin", ints(origin.getX(), origin.getY(), origin.getZ()));
        o.addProperty("destination", frame.destination().toString());
        o.add("eye", triple(eye.x, eye.y, eye.z));
        o.add("rays", num(r.rays()));
        o.add("hits", num(r.hits()));
        o.add("unfed", num(r.unfed()));
        o.add("open", num(r.open()));
        o.add("blocks", counts(r.blocks()));
        o.add("biomes", counts(r.biomes()));
        o.add("medianHitDistance", r.medianHitDistance() == null ? JsonNull.INSTANCE : num(r.medianHitDistance()));
        o.add("skyColor", colour(frame.skyColor()));
        o.add("fogColor", colour(frame.fogColor()));
        return o;
    }

    private static BlockPos originFrom(String query) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("origin=")) {
                String[] xyz = part.substring(7).split(",");
                return new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()),
                        Integer.parseInt(xyz[2].trim()));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ actions

    private static Answer screenshot(JsonObject body) {
        if (!body.has("path")) {
            return error(400, "a screenshot needs a \"path\"");
        }
        Path path = Path.of(body.get("path").getAsString());
        CompletableFuture<JsonObject> done = new CompletableFuture<>();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            JsonObject o = new JsonObject();
            try (image) {
                Files.createDirectories(path.toAbsolutePath().getParent());
                image.writeToFile(path);
                o.addProperty("path", path.toString());
                o.add("bytes", num(Files.size(path)));
                o.add("width", num(image.getWidth()));
                o.add("height", num(image.getHeight()));
            } catch (IOException e) {
                o.add("path", JsonNull.INSTANCE);
                o.addProperty("error", e.toString());
            }
            done.complete(o);
        }));
        return await(done, WAIT_SECONDS);
    }

    private static Answer input(JsonObject body) {
        Minecraft mc = Minecraft.getInstance();
        if (body.has("look")) {
            JsonObject look = body.getAsJsonObject("look");
            float yaw = look.get("yaw").getAsFloat();
            float pitch = look.get("pitch").getAsFloat();
            return onClient(() -> {
                JsonObject o = new JsonObject();
                if (mc.player == null) {
                    o.addProperty("error", "no local player");
                    return o;
                }
                mc.player.setYRot(yaw);
                mc.player.setXRot(pitch);
                mc.player.setYHeadRot(yaw);
                JsonObject rotation = new JsonObject();
                rotation.add("yaw", num(mc.player.getYRot()));
                rotation.add("pitch", num(mc.player.getXRot()));
                rotation.addProperty("facing", net.minecraft.core.Direction.fromYRot(mc.player.getYRot())
                        .getSerializedName());
                JsonObject player = new JsonObject();
                player.add("rotation", rotation);
                JsonObject after = new JsonObject();
                after.add("player", player);
                o.add("after", after);
                return o;
            });
        }
        if (body.has("walk")) {
            JsonObject w = body.getAsJsonObject("walk");
            double blocks = w.get("blocks").getAsDouble();
            int timeoutMs = w.has("timeoutMs") ? w.get("timeoutMs").getAsInt() : 30_000;
            CompletableFuture<CompletableFuture<JsonObject>> started = mc.submit(() -> startWalk(mc, blocks, timeoutMs));
            try {
                return await(started.get(WAIT_SECONDS, TimeUnit.SECONDS), timeoutMs / 1000 + WAIT_SECONDS);
            } catch (Exception e) {
                return error(503, "the walk did not start: " + e);
            }
        }
        if (body.has("use")) {
            return onClient(() -> use(mc));
        }
        if (body.has("slot")) {
            int index = body.getAsJsonObject("slot").get("index").getAsInt();
            return onClient(() -> {
                JsonObject o = new JsonObject();
                if (mc.player == null || index < 0 || index > 8) {
                    o.addProperty("error", mc.player == null ? "no local player" : "a hotbar slot is 0 to 8");
                    return o;
                }
                mc.player.getInventory().setSelectedSlot(index);
                o.add("selectedSlot", num(index));
                o.add("mainHand", item(mc.player.getMainHandItem()));
                return o;
            });
        }
        return error(400, "an input body names look, walk, use or slot");
    }

    /** The main hand used as a right-click is: on the block in the crosshair, else in the air. */
    private static JsonObject use(Minecraft mc) {
        JsonObject o = new JsonObject();
        if (mc.player == null || mc.gameMode == null) {
            o.addProperty("error", "no local player");
            return o;
        }
        HitResult hit = mc.hitResult;
        InteractionResult result;
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, bh);
            o.add("block", ints(bh.getBlockPos().getX(), bh.getBlockPos().getY(), bh.getBlockPos().getZ()));
            o.addProperty("face", bh.getDirection().getSerializedName());
        } else {
            result = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            o.add("block", JsonNull.INSTANCE);
        }
        o.addProperty("item", BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString());
        o.addProperty("result", result.toString());
        o.addProperty("consumed", result.consumesAction());
        return o;
    }

    private static CompletableFuture<JsonObject> startWalk(Minecraft mc, double blocks, int timeoutMs) {
        if (mc.player == null || mc.level == null) {
            JsonObject o = new JsonObject();
            o.addProperty("error", "no local player");
            return CompletableFuture.completedFuture(o);
        }
        if (walk != null) {
            walk.progress.interrupt("replaced by a new walk");
            finish(mc);
        }
        walk = new Walk(new WalkProgress(blocks, Math.max(1, timeoutMs / 50), mc.player.getX(), mc.player.getZ()),
                mc.level.dimension().identifier().toString());
        mc.options.keyUp.setDown(true);
        return walk.done;
    }

    private static void tick(Minecraft mc) {
        clientTick++;
        if (walk == null) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            walk.progress.interrupt("the player left the world");
            finish(mc);
            return;
        }
        if (!mc.level.dimension().identifier().toString().equals(walk.dimension)) {
            walk.progress.interrupt("changed dimension to " + mc.level.dimension().identifier());
            finish(mc);
            return;
        }
        mc.options.keyUp.setDown(true);
        if (walk.progress.tick(mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
            finish(mc);
        }
    }

    private static void finish(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        Walk w = walk;
        walk = null;
        JsonObject o = new JsonObject();
        WalkProgress p = w.progress;
        o.add("travelled", num(p.travelled()));
        o.add("ticks", num(p.ticks()));
        o.addProperty("arrived", p.arrived());
        o.addProperty("stalled", p.stalled());
        o.addProperty("reason", p.reason());
        double[] at = p.stalledAt();
        o.add("stalledAt", at == null ? JsonNull.INSTANCE : triple(at[0], at[1], at[2]));
        o.addProperty("dimension", mc.level == null ? null : mc.level.dimension().identifier().toString());
        w.done.complete(o);
    }

    // ------------------------------------------------------------------ values

    private static JsonObject counts(Map<String, Integer> counts) {
        JsonObject o = new JsonObject();
        counts.forEach((k, v) -> o.add(k, num(v)));
        return o;
    }

    private static JsonElement colour(int rgb) {
        return rgb < 0 ? JsonNull.INSTANCE : new JsonPrimitive(String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF));
    }

    private static JsonArray ints(int... values) {
        JsonArray a = new JsonArray();
        for (int v : values) {
            a.add(v);
        }
        return a;
    }

    private static JsonArray triple(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(num(x));
        a.add(num(y));
        a.add(num(z));
        return a;
    }

    /** 3dp, trailing zeros stripped: full health is {@code 20}, never {@code 20.0}. */
    static JsonPrimitive num(double v) {
        if (!Double.isFinite(v)) {
            return null;
        }
        BigDecimal d = BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        return new JsonPrimitive(new LazilyParsedNumber(d.signum() == 0 ? "0" : d.toPlainString()));
    }
}
