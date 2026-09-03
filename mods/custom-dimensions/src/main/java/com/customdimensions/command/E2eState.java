package com.customdimensions.command;

import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.facts.Json;
import com.customdimensions.facts.Measured;
import com.customdimensions.mixin.MinecraftServerAccessor;
import com.customdimensions.portal.PortalHelper;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /customdim e2e-state [player]} — everything an end-to-end assertion
 * needs about the live server, as one JSON file, in one RCON round trip.
 *
 * <p>RCON cannot carry this and cannot be parsed for it: a failed
 * {@code data get entity} answers with a sentence, and a harness that reads
 * the reply as data reads the sentence as a coordinate
 * ([T17](../../../../../../../TROUBLESHOOTING.md#t17)). Every fact here is a
 * typed JSON value or an explicit absence — an offline player is
 * {@code "online": false} with null facts, never a missing key.
 *
 * <p>Nothing loads a chunk. A zone's frame is only checked when every chunk
 * {@link PortalHelper#isZoneValid} would read is already resident; otherwise
 * the zone reports {@code "resident": false} and no verdict, so "the frame is
 * down" and "nobody could tell" stay distinguishable.
 *
 * <p>The record is the answer and the summary line is a receipt. The path is
 * fixed, so a harness can read the file without parsing the reply at all.
 */
public final class E2eState {

    /** One file, overwritten each call: session state, not a keyed record. */
    static final String FILE_NAME = "e2e-state.json";

    /** Zones are held per world, so an unloaded world contributes none. */
    private static final String ZONE_SCOPE =
            "every world loaded right now; a zone in an unloaded world is not listed";

    private static final String NO_LOAD_TICK =
            "nothing in the mod records a per-world load tick";

    private E2eState() {
    }

    // ------------------------------------------------------------------
    // The record — plain values, no server
    // ------------------------------------------------------------------

    public record Pos(int x, int y, int z) {
    }

    /**
     * A player, or the fact that there is no such player online. Online
     * carries every fact and offline carries none — a half-filled record
     * cannot be built, which is what stops a failed lookup reaching a test.
     */
    public record PlayerState(String name, boolean online, String uuid, String dimension,
                              Double x, Double y, Double z, Pos blockPos,
                              Float yaw, Float pitch, Boolean onGround,
                              String mainHandItem, Integer portalCooldown) {

        public PlayerState {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a player record needs a name");
            }
            boolean allPresent = uuid != null && dimension != null && x != null && y != null
                    && z != null && blockPos != null && yaw != null && pitch != null
                    && onGround != null && mainHandItem != null && portalCooldown != null;
            boolean allAbsent = uuid == null && dimension == null && x == null && y == null
                    && z == null && blockPos == null && yaw == null && pitch == null
                    && onGround == null && mainHandItem == null && portalCooldown == null;
            if (online ? !allPresent : !allAbsent) {
                throw new IllegalArgumentException(
                        "an online player carries every fact and an offline one carries none: "
                        + name);
            }
        }

        public static PlayerState offline(String name) {
            return new PlayerState(name, false, null, null, null, null, null, null,
                    null, null, null, null, null);
        }

        public static PlayerState online(String name, String uuid, String dimension,
                                         double x, double y, double z, Pos blockPos,
                                         float yaw, float pitch, boolean onGround,
                                         String mainHandItem, int portalCooldown) {
            return new PlayerState(name, true, uuid, dimension, x, y, z, blockPos,
                    yaw, pitch, onGround, mainHandItem, portalCooldown);
        }
    }

    /** A loaded world and whether this mod manages its namespace. */
    public record DimensionState(String id, boolean managed, Measured<Integer> loadedAtTick) {
    }

    /**
     * One portal zone: where it stands, where it leads, and whether its frame
     * still bounds it. A verdict exists only when the chunks are resident.
     */
    public record ZoneState(String kind, String world, String targetWorld, String axis,
                            List<Pos> interior, boolean resident, Boolean frameStands) {

        public ZoneState {
            interior = List.copyOf(interior);
            if (resident == (frameStands == null)) {
                throw new IllegalArgumentException(
                        "a zone reports whether its frame stands exactly when its chunks are "
                        + "resident: " + kind + " in " + world);
            }
        }
    }

    /** {@code tick} counts from boot, so boot is tick 0 and this is uptime. */
    public record ServerState(int tick, Measured<Long> averageNanosPerTick,
                              Measured<Double> tps, String modVersion) {
    }

    public record State(ServerState server, List<PlayerState> players,
                        List<DimensionState> dimensions, String zoneScope,
                        List<ZoneState> zones) {
    }

    // ------------------------------------------------------------------
    // Pure derivations
    // ------------------------------------------------------------------

    /** Absent rather than zero: an untimed tick is not a fast one. */
    static Measured<Long> nanosFrom(long averageNanosPerTick) {
        return averageNanosPerTick > 0
                ? Measured.of(averageNanosPerTick)
                : Measured.absent("the server has not timed a tick yet");
    }

    /** Ticks per second, capped at the 20 the server schedules. */
    static Measured<Double> tpsFrom(long averageNanosPerTick) {
        return averageNanosPerTick > 0
                ? Measured.of(Math.min(20.0, 1.0e9 / averageNanosPerTick))
                : Measured.absent("the server has not timed a tick yet");
    }

    /**
     * Every chunk a frame check reads: the interior's own and the ring's.
     * The ring crosses a chunk boundary whenever the interior sits on one,
     * and reading a cold chunk from here is what wedges the main thread.
     */
    static Set<ChunkPos> chunksRead(Set<BlockPos> interior, Direction.Axis axis) {
        Set<ChunkPos> chunks = new HashSet<>();
        Direction[] plane = PortalHelper.planeDirections(axis);
        for (BlockPos pos : interior) {
            chunks.add(new ChunkPos(pos));
            for (Direction dir : plane) {
                chunks.add(new ChunkPos(pos.offset(dir)));
            }
        }
        return chunks;
    }

    /** Takes the directory so the naming rule is testable without Fabric. */
    static Path artefactPath(Path directory) {
        return directory.resolve(FILE_NAME);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static final Comparator<Pos> BY_COORDINATE =
            Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z);

    private static final Comparator<ZoneState> BY_PLACE =
            Comparator.comparing(ZoneState::world).thenComparing(ZoneState::kind)
                    .thenComparing(zone -> zone.interior().stream().min(BY_COORDINATE).orElse(null),
                            Comparator.nullsFirst(BY_COORDINATE));

    /**
     * The document, given {@link Artefacts#jsonHeader}'s opening. Collections
     * are sorted here, so two runs of one state are byte-identical and a diff
     * between two runs means something.
     */
    static String render(String header, State state) {
        StringBuilder body = new StringBuilder(header);
        body.append(" \"server\": ").append(serverJson(state.server())).append(",\n");

        body.append(" \"players\": [");
        List<PlayerState> players = new ArrayList<>(state.players());
        players.sort(Comparator.comparing(PlayerState::name));
        for (int i = 0; i < players.size(); i++) {
            body.append(i > 0 ? ",\n  " : "\n  ").append(playerJson(players.get(i)));
        }
        body.append(players.isEmpty() ? "],\n" : "\n ],\n");

        body.append(" \"dimensions\": [");
        List<DimensionState> dimensions = new ArrayList<>(state.dimensions());
        dimensions.sort(Comparator.comparing(DimensionState::id));
        for (int i = 0; i < dimensions.size(); i++) {
            body.append(i > 0 ? ",\n  " : "\n  ").append(dimensionJson(dimensions.get(i)));
        }
        body.append(dimensions.isEmpty() ? "],\n" : "\n ],\n");

        body.append(" \"zoneScope\": ").append(Json.quote(state.zoneScope())).append(",\n");

        body.append(" \"zones\": [");
        List<ZoneState> zones = new ArrayList<>(state.zones());
        zones.sort(BY_PLACE);
        for (int i = 0; i < zones.size(); i++) {
            body.append(i > 0 ? ",\n  " : "\n  ").append(zoneJson(zones.get(i)));
        }
        body.append(zones.isEmpty() ? "]\n}\n" : "\n ]\n}\n");
        return body.toString();
    }

    private static String serverJson(ServerState server) {
        return "{\"tick\": " + Json.number((long) server.tick())
                + ", \"averageNanosPerTick\": "
                + server.averageNanosPerTick().toJson(nanos -> Json.number((long) nanos))
                + ", \"tps\": " + server.tps().toJson(tps -> Json.number((double) tps))
                + ", \"modVersion\": " + Json.quote(server.modVersion()) + "}";
    }

    private static String playerJson(PlayerState player) {
        return "{\"name\": " + Json.quote(player.name())
                + ", \"online\": " + player.online()
                + ", \"uuid\": " + Json.quote(player.uuid())
                + ", \"dimension\": " + Json.quote(player.dimension())
                + ", \"pos\": " + (player.online()
                        ? "[" + Json.number(player.x()) + ", " + Json.number(player.y())
                                + ", " + Json.number(player.z()) + "]"
                        : "null")
                + ", \"blockPos\": " + posJson(player.blockPos())
                + ", \"yaw\": " + (player.yaw() == null ? "null" : angleJson(player.yaw()))
                + ", \"pitch\": " + (player.pitch() == null ? "null" : angleJson(player.pitch()))
                + ", \"onGround\": " + player.onGround()
                + ", \"mainHandItem\": " + Json.quote(player.mainHandItem())
                + ", \"portalCooldown\": " + (player.portalCooldown() == null
                        ? "null" : Json.number((long) player.portalCooldown()))
                + "}";
    }

    private static String dimensionJson(DimensionState dimension) {
        return "{\"id\": " + Json.quote(dimension.id())
                + ", \"managed\": " + dimension.managed()
                + ", \"loadedAtTick\": "
                + dimension.loadedAtTick().toJson(tick -> Json.number((long) tick)) + "}";
    }

    private static String zoneJson(ZoneState zone) {
        StringBuilder json = new StringBuilder("{\"kind\": ").append(Json.quote(zone.kind()))
                .append(", \"world\": ").append(Json.quote(zone.world()))
                .append(", \"targetWorld\": ").append(Json.quote(zone.targetWorld()))
                .append(", \"axis\": ").append(Json.quote(zone.axis()))
                .append(", \"resident\": ").append(zone.resident())
                .append(", \"frameStands\": ").append(zone.frameStands())
                .append(", \"interior\": [");
        List<Pos> interior = new ArrayList<>(zone.interior());
        interior.sort(BY_COORDINATE);
        for (int i = 0; i < interior.size(); i++) {
            json.append(i > 0 ? ", " : "").append(posJson(interior.get(i)));
        }
        return json.append("]}").toString();
    }

    /**
     * A yaw or pitch at its own precision. Widening a float to a double
     * prints the error the widening introduces, not the angle the server has.
     */
    static String angleJson(float angle) {
        if (!Float.isFinite(angle)) {
            throw new IllegalArgumentException("a non-finite angle is not a measurement");
        }
        return Float.toString(angle);
    }

    private static String posJson(Pos pos) {
        return pos == null ? "null"
                : "[" + pos.x() + ", " + pos.y() + ", " + pos.z() + "]";
    }

    // ------------------------------------------------------------------
    // Gathering — the only half that needs a server
    // ------------------------------------------------------------------

    static int e2eState(CommandContext<ServerCommandSource> ctx, String playerName) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();

        State state = new State(serverState(server), players(server, playerName),
                dimensions(server), ZONE_SCOPE, zones(server));

        Path target = artefactPath(Artefacts.canWriteDurably()
                ? Artefacts.rollingDir() : Artefacts.dir("e2e-state"));
        try {
            Artefacts.write(target, render(Artefacts.jsonHeader("e2e-state"), state));
        } catch (IOException e) {
            source.sendError(Text.literal("e2e-state: write failed: " + e.getMessage()));
            return 0;
        }

        long onlinePlayers = state.players().stream().filter(PlayerState::online).count();
        long managed = state.dimensions().stream().filter(DimensionState::managed).count();
        long cold = state.zones().stream().filter(zone -> !zone.resident()).count();
        final String out = "e2e-state: " + onlinePlayers + "/" + state.players().size()
                + " player(s) online, " + state.dimensions().size() + " world(s) loaded ("
                + managed + " managed), " + state.zones().size() + " zone(s) (" + cold
                + " cold), tick " + state.server().tick() + " -> " + target;
        source.sendFeedback(() -> Text.literal(out), false);
        return 1;
    }

    private static ServerState serverState(MinecraftServer server) {
        long nanos = server.getAverageNanosPerTick();
        return new ServerState(server.getTicks(), nanosFrom(nanos), tpsFrom(nanos),
                Artefacts.stackVersion());
    }

    /**
     * The named player, or every player online. A name that matches nobody is
     * an offline record for that name — the one answer a test must be able to
     * tell apart from a position.
     */
    private static List<PlayerState> players(MinecraftServer server, String playerName) {
        if (playerName != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
            return List.of(player == null ? PlayerState.offline(playerName) : describe(player));
        }
        List<PlayerState> out = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            out.add(describe(player));
        }
        return out;
    }

    private static PlayerState describe(ServerPlayerEntity player) {
        BlockPos block = player.getBlockPos();
        return PlayerState.online(player.getName().getString(), player.getUuidAsString(),
                player.getServerWorld().getRegistryKey().getValue().toString(),
                player.getX(), player.getY(), player.getZ(),
                new Pos(block.getX(), block.getY(), block.getZ()),
                player.getYaw(), player.getPitch(), player.isOnGround(),
                Registries.ITEM.getId(player.getMainHandStack().getItem()).toString(),
                player.getPortalCooldown());
    }

    /** Every loaded world; {@code managed} filtered is what {@code list} answers. */
    private static List<DimensionState> dimensions(MinecraftServer server) {
        List<DimensionState> out = new ArrayList<>();
        for (RegistryKey<World> key : ((MinecraftServerAccessor) server).getWorlds().keySet()) {
            out.add(new DimensionState(key.getValue().toString(),
                    MultiverseConfig.getInstance().isManagedNamespace(key.getValue().getNamespace()),
                    Measured.absent(NO_LOAD_TICK)));
        }
        return out;
    }

    private static List<ZoneState> zones(MinecraftServer server) {
        List<ZoneState> out = new ArrayList<>();
        for (Map.Entry<RegistryKey<World>, ServerWorld> entry
                : ((MinecraftServerAccessor) server).getWorlds().entrySet()) {
            collect(out, entry.getValue(), "source",
                    PortalHelper.getSourceZones(entry.getKey()));
            collect(out, entry.getValue(), "presentation",
                    PortalHelper.getPresentationZones(entry.getKey()));
            collect(out, entry.getValue(), "arrival",
                    PortalHelper.getArrivalZones(entry.getKey()));
        }
        return out;
    }

    private static void collect(List<ZoneState> out, ServerWorld world, String kind,
                                List<PortalHelper.PortalZone> zones) {
        for (PortalHelper.PortalZone zone : zones) {
            boolean resident = readable(world, zone);
            List<Pos> interior = new ArrayList<>();
            for (BlockPos pos : zone.interior) {
                interior.add(new Pos(pos.getX(), pos.getY(), pos.getZ()));
            }
            out.add(new ZoneState(kind, world.getRegistryKey().getValue().toString(),
                    zone.targetWorld.getValue().toString(), zone.axis.name(), interior,
                    resident, resident ? PortalHelper.isZoneValid(world, zone) : null));
        }
    }

    /** Whether the frame check can run without loading anything. */
    private static boolean readable(ServerWorld world, PortalHelper.PortalZone zone) {
        if (zone.interior.isEmpty()) {
            return false;
        }
        for (ChunkPos chunk : chunksRead(zone.interior, zone.axis)) {
            if (PortalHelper.residentChunk(world, chunk.x, chunk.z) == null) {
                return false;
            }
        }
        return true;
    }
}
