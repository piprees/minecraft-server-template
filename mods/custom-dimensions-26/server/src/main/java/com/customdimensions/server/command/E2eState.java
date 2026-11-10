package com.customdimensions.server.command;

import com.customdimensions.core.BlockPos;
import com.customdimensions.server.portal.PortalJournal;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * The record {@code customdim e2e-state} writes, and its JSON: plain values,
 * no server, so the shape is tested without one. Every fact is a typed value
 * or an explicit null: an offline player is {@code "online": false} with null
 * facts, a zone whose chunks are cold is {@code "resident": false} with null
 * verdicts. Collections are sorted, so two writes of one state are identical
 * apart from {@code generatedAt}.
 */
final class E2eState {
    static final String KIND = "e2e-state";
    static final String ZONE_SCOPE = "every zone the registry holds; cells and verdicts only where every chunk the frame "
            + "check reads is resident";

    private E2eState() {
    }

    record View(boolean rendersLocally, boolean keepSlab, int maxRenderDistance, int viewDepth) {
    }

    record SeenEntity(int id, String type, double x, double y, double z) {
    }

    /** The companion feed one player is being sent; null in {@link Player} when it watches no portal. */
    record Feed(BlockPos portal, String world, String destination, BlockPos apertureOrigin, int chunksSent,
                List<SeenEntity> entities, long entityTick) {
        Feed {
            entities = List.copyOf(entities);
        }
    }

    /** A player, or that there is no such player online: online carries every fact, offline none. */
    record Player(String name, boolean online, String uuid, String dimension, double[] pos, BlockPos blockPos,
                  Float yaw, Float pitch, Boolean onGround, String mainHandItem, Integer portalCooldown,
                  Boolean companion, View view, Long companionPayloads, Feed feed, Boolean serverPack) {
        Player {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a player record needs a name");
            }
            boolean core = uuid != null && dimension != null && pos != null && blockPos != null && yaw != null
                    && pitch != null && onGround != null && mainHandItem != null && portalCooldown != null
                    && companion != null && serverPack != null;
            boolean none = uuid == null && dimension == null && pos == null && blockPos == null && yaw == null
                    && pitch == null && onGround == null && mainHandItem == null && portalCooldown == null
                    && companion == null && view == null && companionPayloads == null && feed == null
                    && serverPack == null;
            if (online ? !core : !none) {
                throw new IllegalArgumentException("an online player carries every fact and an offline one none: " + name);
            }
        }

        static Player offline(String name) {
            return new Player(name, false, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null);
        }
    }

    /** A dimension the runtime knows: a base world, or a lazy level open or not. */
    record Dimension(String slug, String id, boolean managed, String state, boolean open, int players,
                     int loadedChunks) {
    }

    /**
     * One opening cell: the server's block, the block a client without this mod
     * is sent for it, the one a client that loaded the server pack is sent
     * (null while tints are off or the state was not granted), each through
     * Polymer's block mapper of an online player of that kind when there is
     * one, and the dye
     * colour its tint paints ({@code #RRGGBB}).
     */
    record Cell(BlockPos pos, String block, String clientSees, String packSees, String tint) {
    }

    /** An arrival cell's way back. */
    record ReturnTo(String leadsTo, String exitMode, Integer sourceX, Integer sourceZ) {
    }

    record Aura(boolean enabled, Long spent, Integer budget, PortalJournal.AuraPasses passes) {
    }

    record Zone(String kind, String world, String targetWorld, String definition, String axis, List<BlockPos> interior,
                boolean resident, Boolean frameStands, Boolean lit, List<Cell> cells, Integer singleUseTicksLeft,
                ReturnTo returnTo, Aura aura, PortalJournal.Particles particles) {
        Zone {
            interior = List.copyOf(interior);
            cells = cells == null ? null : List.copyOf(cells);
            if (resident == (frameStands == null) || resident == (lit == null) || resident == (cells == null)) {
                throw new IllegalArgumentException("a zone carries verdicts exactly when its chunks are resident: "
                        + kind + " in " + world);
            }
        }
    }

    /** {@code tintPack}: the SHA-1 of the pack tints are on for, null while they are off. */
    record Server(long tick, Double msPerTick, Double tps, String modVersion, String tintPack) {
    }

    record State(String generatedAt, Server server, List<Player> players, List<Dimension> dimensions, List<Zone> zones,
                 List<PortalJournal.Ignition> ignitions, List<PortalJournal.Traversal> traversals,
                 List<PortalJournal.Title> titles, List<PortalJournal.Ignition> lastIgnitions,
                 List<PortalJournal.Traversal> lastTraversals) {
    }

    // ------------------------------------------------------------------ rendering

    private static final Comparator<BlockPos> BY_COORDINATE =
            Comparator.comparingInt(BlockPos::x).thenComparingInt(BlockPos::y).thenComparingInt(BlockPos::z);

    static String render(State state) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
                .toJson(json(state)) + "\n";
    }

    static JsonObject json(State state) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", KIND);
        o.addProperty("generatedAt", state.generatedAt());
        o.add("server", server(state.server()));
        JsonArray players = new JsonArray();
        state.players().stream().sorted(Comparator.comparing(Player::name))
                .forEach(p -> players.add(player(p, state)));
        o.add("players", players);
        JsonArray dims = new JsonArray();
        state.dimensions().stream().sorted(Comparator.comparing(Dimension::id)).forEach(d -> dims.add(dimension(d)));
        o.add("dimensions", dims);
        o.addProperty("zoneScope", ZONE_SCOPE);
        JsonArray zones = new JsonArray();
        state.zones().stream().sorted(Comparator.comparing(Zone::world).thenComparing(Zone::kind)
                        .thenComparing(z -> z.interior().stream().min(BY_COORDINATE).orElse(null),
                                Comparator.nullsFirst(BY_COORDINATE)))
                .forEach(z -> zones.add(zone(z)));
        o.add("zones", zones);
        JsonArray ignitions = new JsonArray();
        state.ignitions().forEach(i -> ignitions.add(ignition(i)));
        o.add("ignitions", ignitions);
        JsonArray traversals = new JsonArray();
        state.traversals().forEach(t -> traversals.add(traversal(t)));
        o.add("traversals", traversals);
        return o;
    }

    private static JsonObject server(Server s) {
        JsonObject o = new JsonObject();
        o.add("tick", num(s.tick()));
        o.add("msPerTick", s.msPerTick() == null ? JsonNull.INSTANCE : num(s.msPerTick()));
        o.add("tps", s.tps() == null ? JsonNull.INSTANCE : num(s.tps()));
        o.addProperty("modVersion", s.modVersion());
        o.addProperty("tintPack", s.tintPack());
        return o;
    }

    private static JsonObject player(Player p, State state) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.name());
        o.addProperty("online", p.online());
        o.addProperty("uuid", p.uuid());
        o.addProperty("dimension", p.dimension());
        o.add("pos", p.pos() == null ? JsonNull.INSTANCE : triple(p.pos()));
        o.add("blockPos", pos(p.blockPos()));
        o.add("yaw", p.yaw() == null ? JsonNull.INSTANCE : angle(p.yaw()));
        o.add("pitch", p.pitch() == null ? JsonNull.INSTANCE : angle(p.pitch()));
        o.add("onGround", p.onGround() == null ? JsonNull.INSTANCE : new JsonPrimitive(p.onGround()));
        o.addProperty("mainHandItem", p.mainHandItem());
        o.add("portalCooldown", p.portalCooldown() == null ? JsonNull.INSTANCE : num(p.portalCooldown()));
        o.add("companion", p.companion() == null ? JsonNull.INSTANCE : new JsonPrimitive(p.companion()));
        o.add("serverPack", p.serverPack() == null ? JsonNull.INSTANCE : new JsonPrimitive(p.serverPack()));
        o.add("view", view(p.view()));
        o.add("companionPayloads", p.companionPayloads() == null ? JsonNull.INSTANCE : num(p.companionPayloads()));
        o.add("feed", feed(p.feed()));
        o.add("lastIgnition", state.lastIgnitions().stream().filter(i -> p.name().equals(i.player())).findFirst()
                .map(E2eState::ignition).orElse(null));
        o.add("lastTraversal", state.lastTraversals().stream().filter(t -> p.name().equals(t.player())).findFirst()
                .map(E2eState::traversal).orElse(null));
        o.add("title", state.titles().stream().filter(t -> p.name().equals(t.player())).findFirst()
                .map(E2eState::title).orElse(null));
        return o;
    }

    private static JsonElement view(View v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("rendersLocally", v.rendersLocally());
        o.addProperty("keepSlab", v.keepSlab());
        o.add("maxRenderDistance", num(v.maxRenderDistance()));
        o.add("viewDepth", num(v.viewDepth()));
        return o;
    }

    private static JsonElement feed(Feed f) {
        if (f == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.add("portal", pos(f.portal()));
        o.addProperty("world", f.world());
        o.addProperty("destination", f.destination());
        o.add("apertureOrigin", pos(f.apertureOrigin()));
        o.add("chunksSent", num(f.chunksSent()));
        JsonArray entities = new JsonArray();
        f.entities().stream().sorted(Comparator.comparingInt(SeenEntity::id)).forEach(e -> {
            JsonObject eo = new JsonObject();
            eo.add("id", num(e.id()));
            eo.addProperty("type", e.type());
            eo.add("pos", triple(new double[] {e.x(), e.y(), e.z()}));
            entities.add(eo);
        });
        o.add("entities", entities);
        o.add("entityTick", f.entityTick() == Long.MIN_VALUE ? JsonNull.INSTANCE : num(f.entityTick()));
        return o;
    }

    private static JsonObject dimension(Dimension d) {
        JsonObject o = new JsonObject();
        o.addProperty("slug", d.slug());
        o.addProperty("id", d.id());
        o.addProperty("managed", d.managed());
        o.addProperty("state", d.state());
        o.addProperty("open", d.open());
        o.add("players", num(d.players()));
        o.add("loadedChunks", num(d.loadedChunks()));
        return o;
    }

    private static JsonObject zone(Zone z) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", z.kind());
        o.addProperty("world", z.world());
        o.addProperty("targetWorld", z.targetWorld());
        o.addProperty("definition", z.definition());
        o.addProperty("axis", z.axis());
        o.addProperty("resident", z.resident());
        o.add("frameStands", z.frameStands() == null ? JsonNull.INSTANCE : new JsonPrimitive(z.frameStands()));
        o.add("lit", z.lit() == null ? JsonNull.INSTANCE : new JsonPrimitive(z.lit()));
        JsonArray interior = new JsonArray();
        z.interior().stream().sorted(BY_COORDINATE).forEach(p -> interior.add(pos(p)));
        o.add("interior", interior);
        if (z.cells() == null) {
            o.add("cells", JsonNull.INSTANCE);
        } else {
            JsonArray cells = new JsonArray();
            z.cells().stream().sorted(Comparator.comparing(Cell::pos, BY_COORDINATE)).forEach(c -> {
                JsonObject co = new JsonObject();
                co.add("pos", pos(c.pos()));
                co.addProperty("block", c.block());
                co.addProperty("clientSees", c.clientSees());
                co.addProperty("packSees", c.packSees());
                co.addProperty("tint", c.tint());
                cells.add(co);
            });
            o.add("cells", cells);
        }
        o.add("singleUseTicksLeft", z.singleUseTicksLeft() == null ? JsonNull.INSTANCE : num(z.singleUseTicksLeft()));
        if (z.returnTo() == null) {
            o.add("returnTo", JsonNull.INSTANCE);
        } else {
            JsonObject r = new JsonObject();
            r.addProperty("leadsTo", z.returnTo().leadsTo());
            r.addProperty("exitMode", z.returnTo().exitMode());
            r.add("sourceColumn", z.returnTo().sourceX() == null ? JsonNull.INSTANCE
                    : intArray(z.returnTo().sourceX(), z.returnTo().sourceZ()));
            o.add("returnTo", r);
        }
        o.add("aura", aura(z.aura()));
        o.add("particles", particles(z.particles()));
        return o;
    }

    private static JsonElement aura(Aura a) {
        if (a == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("enabled", a.enabled());
        o.add("spent", a.spent() == null ? JsonNull.INSTANCE : num(a.spent()));
        o.add("budget", a.budget() == null ? JsonNull.INSTANCE : num(a.budget()));
        PortalJournal.AuraPasses p = a.passes();
        o.add("passes", p == null ? num(0) : num(p.passes()));
        o.add("passSpent", p == null ? num(0) : num(p.spent()));
        o.add("vetoed", p == null ? num(0) : num(p.vetoed()));
        o.add("lastPassTick", p == null ? JsonNull.INSTANCE : num(p.lastTick()));
        return o;
    }

    private static JsonElement particles(PortalJournal.Particles p) {
        if (p == null) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.add("bursts", num(p.bursts()));
        o.add("lastTick", num(p.lastTick()));
        o.addProperty("particle", p.particle());
        o.addProperty("color", String.format("#%06X", p.color() & 0xFFFFFF));
        JsonArray to = new JsonArray();
        p.recipients().stream().sorted().forEach(to::add);
        o.add("recipients", to);
        return o;
    }

    static JsonObject ignition(PortalJournal.Ignition i) {
        JsonObject o = new JsonObject();
        o.addProperty("player", i.player());
        o.addProperty("definition", i.definition());
        o.addProperty("world", i.world());
        o.addProperty("targetWorld", i.target());
        o.addProperty("axis", i.axis());
        o.add("landing", pos(i.landing()));
        o.add("cells", num(i.cells()));
        o.add("tick", num(i.tick()));
        return o;
    }

    static JsonObject traversal(PortalJournal.Traversal t) {
        JsonObject o = new JsonObject();
        o.addProperty("player", t.player());
        o.addProperty("from", t.from());
        o.addProperty("to", t.to());
        o.add("portal", pos(t.portal()));
        o.add("landing", pos(t.landing()));
        o.addProperty("throughArrival", t.arrival());
        o.addProperty("preloadedSent", t.preloadedSent());
        o.add("tick", num(t.tick()));
        return o;
    }

    static JsonObject title(PortalJournal.Title t) {
        JsonObject o = new JsonObject();
        o.addProperty("world", t.world());
        o.add("portal", pos(t.portal()));
        o.addProperty("destination", t.destination());
        o.addProperty("text", t.text());
        o.add("tick", num(t.tick()));
        return o;
    }

    // ------------------------------------------------------------------ values

    static JsonElement pos(BlockPos p) {
        return p == null ? JsonNull.INSTANCE : intArray(p.x(), p.y(), p.z());
    }

    private static JsonArray intArray(int... values) {
        JsonArray a = new JsonArray();
        for (int v : values) {
            a.add(v);
        }
        return a;
    }

    private static JsonArray triple(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) {
            a.add(num(d));
        }
        return a;
    }

    /** A number at 3dp with trailing zeros stripped, so full health is {@code 20}, never {@code 20.0}. */
    static JsonPrimitive num(double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("a non-finite number is not a measurement");
        }
        BigDecimal d = BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        String plain = d.signum() == 0 ? "0" : d.toPlainString();
        return new JsonPrimitive(new LazilyParsedNumber(plain));
    }

    static JsonPrimitive num(long v) {
        return new JsonPrimitive(v);
    }

    /** A yaw or pitch to 3dp, which is finer than a client can turn. */
    static JsonPrimitive angle(float v) {
        if (!Float.isFinite(v)) {
            throw new IllegalArgumentException("a non-finite angle is not a measurement");
        }
        return num((double) v);
    }
}
