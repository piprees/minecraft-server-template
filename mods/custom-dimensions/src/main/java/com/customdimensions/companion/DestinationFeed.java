package com.customdimensions.companion;

import com.customdimensions.MultiverseServer;
import com.customdimensions.portal.PortalHelper;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feeds the destination's chunks to a client that draws the far side itself.
 *
 * <h2>A filled core, then a wedge</h2>
 * The client renders the destination itself, and a renderer builds a chunk's
 * geometry only once that chunk AND all eight of its neighbours have arrived.
 * A cone of columns has no interior, so a cone alone builds nothing however
 * many chunks it sends. {@link #CORE_RADIUS} is a filled square around the
 * arrival that gives the renderer somewhere to start.
 *
 * <p>Beyond the core the cone still applies. A portal's opening is two or
 * three blocks wide, so feeding the whole disc would send an order of
 * magnitude more chunks than can ever be seen through the frame.
 * {@link #throughOpening} is that cone reduced to the horizontal plane:
 * a column is fed only if the line from the eye to it crosses the opening.
 *
 * <h2>Nearest first, under a budget</h2>
 * A client that receives the far edge of the wedge before the chunk the frame
 * stands on shows a hole at the opening while the horizon fills in. Chunks go
 * out nearest-first, a few per pass, so the view warms up outward.
 *
 * <p>Chunks are read through {@link PortalHelper#residentChunk} throughout —
 * nothing here may load or generate one (mods/AGENTS.md, Rule 1). An absent
 * chunk is skipped and retried on a later pass rather than waited for.
 */
public final class DestinationFeed {

    /** Grepped in the server log for what one destination's feed has sent. */
    public static final String FEED_MARKER = "companion-send:destination-chunks";

    /**
     * Chunks handed out on one pass. Small on purpose: each is a full chunk
     * serialisation on the server thread, and the wedge fills in seconds.
     */
    public static final int DEFAULT_BUDGET = 4;

    /** Ceiling on the fed radius whatever the client asks for, in chunks. */
    public static final int MAX_RADIUS = 16;

    /**
     * Chunks each side of the arrival fed whatever the cone says. Two gives a
     * filled 5x5, whose 3x3 interior is the smallest region a renderer can
     * build anything from — every chunk in it has all eight neighbours.
     */
    public static final int CORE_RADIUS = 2;

    /**
     * Pumps a destination may write nothing for before it says so again. At
     * the stationary refresh cadence of 16 ticks this is about a minute, so a
     * feed that has gone quiet is in any log snapshot rather than only in the
     * one covering the moment it stopped.
     */
    public static final int IDLE_REPEAT_PUMPS = 75;

    /** Per player, per destination, the chunk keys already sent. */
    private static final Map<UUID, Map<Identifier, Set<Long>>> SENT = new ConcurrentHashMap<>();

    /** Per player, per destination, consecutive pumps that wrote nothing. */
    private static final Map<UUID, Map<Identifier, Integer>> IDLE = new ConcurrentHashMap<>();

    private DestinationFeed() {}

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }

    /**
     * Whether a column can be seen through the opening from an eye, in the
     * horizontal plane only.
     *
     * <p>{@code A} is the opening's in-plane horizontal axis and {@code N} its
     * normal axis, both in SOURCE-world coordinates. The column must be beyond
     * the plane from the eye, and the line between them must cross the plane
     * inside {@code [a0, a1]}.
     *
     * <p>An eye level with the plane sees nothing rather than dividing by
     * zero: from exactly in the plane there is no through-line at all.
     */
    public static boolean throughOpening(double eyeA, double eyeN, double a0, double a1,
            double planeN, double colA, double colN) {
        double eyeToPlane = planeN - eyeN;
        if (Math.abs(eyeToPlane) < 1.0e-6) {
            return false;
        }
        double eyeToCol = colN - eyeN;
        double t = eyeToPlane / eyeToCol;
        // t in (0, 1) is the only case the column is BEYOND the plane: t <= 0
        // puts the crossing behind the eye, t >= 1 puts the column short of it.
        if (!(t > 0.0) || !(t < 1.0)) {
            return false;
        }
        double crossing = eyeA + (colA - eyeA) * t;
        return crossing >= a0 && crossing <= a1;
    }

    /**
     * The next chunks to send, nearest-first, skipping what is already sent.
     * The {@link #CORE_RADIUS} square around the arrival is always a
     * candidate; everything beyond it must be in the cone.
     *
     * <p>Coordinates are destination chunk coordinates; {@code dx}/{@code dz}
     * are the block offsets from {@link CompanionPayloads.PortalFrame}, so a
     * destination column maps back to source space by subtracting them. That
     * is the frame the opening and the eye are expressed in.
     */
    public static List<Long> nextChunks(int centreChunkX, int centreChunkZ, int radius,
            double eyeA, double eyeN, double a0, double a1, double planeN,
            int dx, int dz, Set<Long> sent, int budget, Normal normal) {
        List<Long> picked = new ArrayList<>();
        if (budget <= 0 || radius <= 0) {
            return picked;
        }
        List<long[]> candidates = new ArrayList<>();
        int span = Math.max(radius, CORE_RADIUS);
        for (int ox = -span; ox <= span; ox++) {
            for (int oz = -span; oz <= span; oz++) {
                boolean core = Math.abs(ox) <= CORE_RADIUS && Math.abs(oz) <= CORE_RADIUS;
                if (!core && ox * ox + oz * oz > radius * radius) {
                    continue;
                }
                int cx = centreChunkX + ox;
                int cz = centreChunkZ + oz;
                long key = chunkKey(cx, cz);
                if (sent.contains(key)) {
                    continue;
                }
                if (!core
                        && !chunkThroughOpening(cx, cz, eyeA, eyeN, a0, a1, planeN, dx, dz, normal)) {
                    continue;
                }
                candidates.add(new long[] {(long) (ox * ox + oz * oz), key});
            }
        }
        candidates.sort((left, right) -> {
            int byDistance = Long.compare(left[0], right[0]);
            return byDistance != 0 ? byDistance : Long.compare(left[1], right[1]);
        });
        for (long[] candidate : candidates) {
            if (picked.size() >= budget) {
                break;
            }
            picked.add(candidate[1]);
        }
        return picked;
    }

    /**
     * Which world axis the opening's normal runs along. {@code Z} means the
     * opening spans X and is crossed along Z; {@code X} is the other upright
     * case. {@code Y} is a horizontal portal — looking down through it, the
     * wedge is not a horizontal shape at all, so the whole disc is fed.
     */
    public enum Normal {
        X, Y, Z
    }

    /**
     * A chunk is in the wedge if any of its four corners is. Testing the
     * centre alone drops the chunk the frame itself stands on whenever the
     * wedge passes through a corner of it.
     */
    private static boolean chunkThroughOpening(int chunkX, int chunkZ,
            double eyeA, double eyeN, double a0, double a1, double planeN,
            int dx, int dz, Normal normal) {
        if (normal == Normal.Y) {
            return true;
        }
        for (int cornerX = 0; cornerX <= 1; cornerX++) {
            for (int cornerZ = 0; cornerZ <= 1; cornerZ++) {
                // Destination block corner, mapped back into source space.
                double blockX = ((chunkX + cornerX) << 4) - dx;
                double blockZ = ((chunkZ + cornerZ) << 4) - dz;
                double colA = normal == Normal.Z ? blockX : blockZ;
                double colN = normal == Normal.Z ? blockZ : blockX;
                if (throughOpening(eyeA, eyeN, a0, a1, planeN, colA, colN)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Drops every record for a player. A client that has dropped its
     * destination worlds must be sent their chunks again, and this record is
     * the only thing that would stop that.
     */
    public static void forget(UUID playerId) {
        SENT.remove(playerId);
        IDLE.remove(playerId);
    }

    /** Drops every record (server shutdown). */
    public static void clear() {
        SENT.clear();
        IDLE.clear();
    }

    /** How many chunks this client holds for this destination. */
    public static int sentCount(UUID playerId, Identifier destination) {
        Map<Identifier, Set<Long>> perDestination = SENT.get(playerId);
        if (perDestination == null) {
            return 0;
        }
        Set<Long> sent = perDestination.get(destination);
        return sent == null ? 0 : sent.size();
    }

    /**
     * Sends up to {@code budget} of this destination's chunks, and answers how
     * many actually went. A chunk that is not resident is skipped, not waited
     * for, and is picked up on a later pass once it arrives.
     */
    public static int pump(ServerPlayerEntity player, ServerWorld targetWorld, int radius,
            double eyeA, double eyeN, double a0, double a1, double planeN,
            int dx, int dz, int arrivalChunkX, int arrivalChunkZ, int budget, Normal normal) {
        if (player == null || targetWorld == null) {
            return 0;
        }
        Identifier destination = targetWorld.getRegistryKey().getValue();
        Set<Long> sent = recordFor(player.getUuid(), destination);

        List<Long> wanted = nextChunks(arrivalChunkX, arrivalChunkZ,
                Math.min(MAX_RADIUS, radius), eyeA, eyeN, a0, a1, planeN, dx, dz,
                sent, budget, normal);
        int written = 0;
        for (long key : wanted) {
            WorldChunk chunk = PortalHelper.residentChunk(targetWorld, chunkX(key), chunkZ(key));
            if (chunk == null) {
                continue;
            }
            ServerPlayNetworking.send(player, new CompanionPayloads.DestinationChunk(
                    destination,
                    new ChunkDataS2CPacket(chunk, targetWorld.getLightingProvider(), null, null)));
            sent.add(key);
            written++;
        }
        int idle = nextIdle(idleCount(player.getUuid(), destination), written);
        IDLE.computeIfAbsent(player.getUuid(), id -> new ConcurrentHashMap<Identifier, Integer>())
                .put(destination, idle);
        // A pump reaching here was asked: the projection pass calls this only
        // for a portal with a live frame. wanted separates a destination that
        // is fully fed (wanted 0) from one whose chunks are all non-resident
        // (wanted above 0, written 0) — the state that draws sky forever.
        if (written > 0 || reportsIdle(idle)) {
            MultiverseServer.LOGGER.debug(
                    "{} player={} dimension={} sent={} wanted={} held={} radius={} idlePumps={}",
                    FEED_MARKER, player.getNameForScoreboard(), destination,
                    written, wanted.size(), sent.size(), Math.min(MAX_RADIUS, radius), idle);
        }
        return written;
    }

    /**
     * Feeds one portal's destination to one viewer. Everything the wedge needs
     * is derived here from the zone's own geometry and the frame the client
     * was already sent, so the projection pass stays one line.
     */
    public static int feed(ServerPlayerEntity player, ServerWorld targetWorld,
            Set<BlockPos> interior, Direction.Axis normalAxis,
            CompanionPayloads.PortalFrame frame, int radius, int budget) {
        if (player == null || targetWorld == null || frame == null
                || interior == null || interior.isEmpty() || normalAxis == null) {
            return 0;
        }
        Direction.Axis axisA = normalAxis == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Z;
        double a0 = Double.MAX_VALUE;
        double a1 = -Double.MAX_VALUE;
        double planeN = 0.0;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (BlockPos cell : interior) {
            double onA = coordOn(cell, axisA);
            a0 = Math.min(a0, onA);
            // A block spans [n, n+1); the opening's far edge is the far face.
            a1 = Math.max(a1, onA + 1.0);
            planeN = coordOn(cell, normalAxis) + 0.5;
            minX = Math.min(minX, cell.getX());
            minZ = Math.min(minZ, cell.getZ());
        }
        Vec3d eye = player.getEyePos();
        double eyeA = axisA == Direction.Axis.X ? eye.x : eye.z;
        double eyeN = normalAxis == Direction.Axis.X ? eye.x
                : normalAxis == Direction.Axis.Y ? eye.y : eye.z;
        return pump(player, targetWorld, radius, eyeA, eyeN, a0, a1, planeN,
                frame.dx(), frame.dz(),
                (minX + frame.dx()) >> 4, (minZ + frame.dz()) >> 4,
                budget, normalOf(normalAxis));
    }

    static Normal normalOf(Direction.Axis axis) {
        switch (axis) {
            case X:
                return Normal.X;
            case Y:
                return Normal.Y;
            default:
                return Normal.Z;
        }
    }

    private static double coordOn(BlockPos pos, Direction.Axis axis) {
        switch (axis) {
            case X:
                return pos.getX();
            case Y:
                return pos.getY();
            default:
                return pos.getZ();
        }
    }

    /** Consecutive silent pumps after one that wrote {@code written}. */
    static int nextIdle(int consecutive, int written) {
        return written > 0 ? 0 : Math.max(0, consecutive) + 1;
    }

    /**
     * Whether a pump that wrote nothing says so. The first is what names the
     * moment a feed went quiet; the repeat is what puts it in a snapshot taken
     * later.
     */
    static boolean reportsIdle(int consecutive) {
        return consecutive == 1 || (consecutive > 0 && consecutive % IDLE_REPEAT_PUMPS == 0);
    }

    private static int idleCount(UUID playerId, Identifier destination) {
        Map<Identifier, Integer> perDestination = IDLE.get(playerId);
        Integer count = perDestination == null ? null : perDestination.get(destination);
        return count == null ? 0 : count;
    }

    private static Set<Long> recordFor(UUID playerId, Identifier destination) {
        return SENT
                .computeIfAbsent(playerId, id -> new ConcurrentHashMap<Identifier, Set<Long>>())
                .computeIfAbsent(destination, id -> ConcurrentHashMap.newKeySet());
    }

    /** Test seam: record chunks as sent, with no world to serialise one from. */
    static void remember(UUID playerId, Identifier destination, long... keys) {
        Set<Long> sent = recordFor(playerId, destination);
        for (long key : keys) {
            sent.add(key);
        }
    }

    /** Test seam: the live record for one player, as plain collections. */
    static Map<Identifier, Set<Long>> heldFor(UUID playerId) {
        Map<Identifier, Set<Long>> held = SENT.get(playerId);
        if (held == null) {
            return Map.of();
        }
        Map<Identifier, Set<Long>> copy = new HashMap<>();
        held.forEach((destination, keys) -> copy.put(destination, new HashSet<>(keys)));
        return copy;
    }
}
