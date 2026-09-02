package com.customdimensions.immersive;

import com.customdimensions.portal.PortalHelper;
import com.customdimensions.portal.PortalShape;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a VANILLA-managed portal actually leads, for the presentation zones
 * {@code PortalAdoption} registers over portals vanilla keeps the traversal
 * for.
 *
 * <p>A zone the mod owns has an arrival because the mod placed it. Vanilla
 * places its far portal wherever {@code PortalForcer} finds room, so the
 * scaled column is a guess, and a guess is a preview of somewhere the player
 * will not arrive. This asks the same index vanilla asks — the
 * {@code minecraft:nether_portal} points of interest around the scaled
 * column — and answers {@code null} when it cannot be sure, which the
 * projector renders as no preview at all.
 *
 * <p>Never generates a chunk. {@code PortalForcer.getPortalPos} opens with
 * {@code PointOfInterestStorage.preloadChunks}, which generates, so it is
 * unusable from a tick path (Rule 1). Only chunks already resident are
 * consulted, via the {@code getWorldChunk(x, z, false)} idiom;
 * {@code getInChunk} still reads through the LOADING section getter, so a
 * resident chunk whose point-of-interest sections are somehow absent costs a
 * region-file read rather than a generation stall.
 *
 * <p>Reading part of the search square makes a nearer portal invisible rather
 * than absent, so a candidate is accepted only when every unread chunk is
 * further away than it is. Otherwise the answer is "not sure", not "this
 * one".
 */
public final class VanillaLinkResolver {

    /** Vanilla's own radius in blocks: {@code PortalForcer.getPortalPos}, non-Nether entry. */
    private static final int SEARCH_RADIUS = 128;

    /** Chunk radius covering {@link #SEARCH_RADIUS}, matching vanilla's own rounding. */
    private static final int SEARCH_CHUNK_RADIUS = (SEARCH_RADIUS >> 4) + 1;

    /** Ticks before a zone with no link re-scans. A scan is bounded but not free. */
    private static final int RETRY_INTERVAL = 40;

    // Keyed by TARGET world first, so a world closed by the idle unloader
    // drops exactly its own links — the same shape ImmersivePreloader uses.
    private static final Map<RegistryKey<World>, Map<String, Link>> LINKS = new ConcurrentHashMap<>();

    private VanillaLinkResolver() {
    }

    /**
     * The block position of the portal this zone's traversal would arrive at,
     * or {@code null} when it cannot be established this pass.
     *
     * @param searchPos the column vanilla would search around — the zone's
     *                  scaled arrival, at the source interior's floor
     */
    public static BlockPos resolve(ServerWorld targetWorld, PortalHelper.PortalZone zone,
            BlockPos searchPos, long tick) {
        if (World.END.equals(targetWorld.getRegistryKey())) {
            return endPlatform(targetWorld);
        }
        BlockPos centre = PortalShape.centreOf(zone.interior);
        if (centre == null) {
            return null;
        }
        Map<String, Link> forTarget = LINKS.computeIfAbsent(
                targetWorld.getRegistryKey(), k -> new ConcurrentHashMap<>());
        String key = zone.sourceWorld.getValue() + "|" + centre.toShortString();

        Link cached = forTarget.get(key);
        if (cached != null && cached.pos != null) {
            if (stillLinked(targetWorld, cached.pos)) {
                return cached.pos;
            }
            forTarget.remove(key);
        } else if (cached != null && tick - cached.tick < RETRY_INTERVAL) {
            return null;
        }

        BlockPos found = search(targetWorld, searchPos);
        forTarget.put(key, new Link(found, tick));
        return found;
    }

    /**
     * The End's arrival is a fixed platform at {@code ServerWorld.END_SPAWN_POS},
     * built on demand — no index, no search. Answered only when its chunk is
     * already resident, so the projector still never reads an unloaded column.
     */
    private static BlockPos endPlatform(ServerWorld targetWorld) {
        BlockPos platform = ServerWorld.END_SPAWN_POS;
        return targetWorld.getChunkManager()
                .getWorldChunk(platform.getX() >> 4, platform.getZ() >> 4, false) == null
                ? null : platform;
    }

    /**
     * Mirrors {@code PortalForcer.getPortalPos}'s filters and ordering — inside
     * the world border, a block state carrying {@code HORIZONTAL_AXIS},
     * nearest first with the lowest Y breaking ties — over resident chunks only.
     */
    private static BlockPos search(ServerWorld targetWorld, BlockPos searchPos) {
        PointOfInterestStorage poi = targetWorld.getPointOfInterestStorage();
        WorldBorder border = targetWorld.getWorldBorder();
        int centreChunkX = searchPos.getX() >> 4;
        int centreChunkZ = searchPos.getZ() >> 4;

        List<BlockPos> candidates = new ArrayList<>();
        double unknownNearestSq = Double.MAX_VALUE;

        for (int dx = -SEARCH_CHUNK_RADIUS; dx <= SEARCH_CHUNK_RADIUS; dx++) {
            for (int dz = -SEARCH_CHUNK_RADIUS; dz <= SEARCH_CHUNK_RADIUS; dz++) {
                int chunkX = centreChunkX + dx;
                int chunkZ = centreChunkZ + dz;
                if (targetWorld.getChunkManager().getWorldChunk(chunkX, chunkZ, false) == null) {
                    unknownNearestSq = Math.min(unknownNearestSq,
                            nearestSquaredHorizontal(chunkX, chunkZ, searchPos.getX(), searchPos.getZ()));
                    continue;
                }
                poi.getInChunk(entry -> entry.matchesKey(PointOfInterestTypes.NETHER_PORTAL),
                                new ChunkPos(chunkX, chunkZ), PointOfInterestStorage.OccupationStatus.ANY)
                        .map(PointOfInterest::getPos)
                        .filter(border::contains)
                        .filter(pos -> targetWorld.getBlockState(pos).contains(Properties.HORIZONTAL_AXIS))
                        .forEach(candidates::add);
            }
        }
        return chooseNearest(candidates, searchPos, unknownNearestSq);
    }

    /**
     * The nearest candidate, or {@code null} when an unread chunk sits closer
     * than it does and could hold a nearer portal.
     */
    static BlockPos chooseNearest(List<BlockPos> candidates, BlockPos searchPos, double unknownNearestSq) {
        BlockPos best = candidates.stream()
                .min(Comparator
                        .comparingDouble((BlockPos pos) -> pos.getSquaredDistance(searchPos))
                        .thenComparingInt(BlockPos::getY))
                .orElse(null);
        if (best == null) {
            return null;
        }
        // Horizontal-only bound on the unread chunks, so it can only
        // understate their distance and reject where vanilla would agree.
        return best.getSquaredDistance(searchPos) <= unknownNearestSq ? best : null;
    }

    /** Squared horizontal distance from a column to the nearest block of one chunk. */
    static double nearestSquaredHorizontal(int chunkX, int chunkZ, int x, int z) {
        double dx = axisGap(chunkX << 4, (chunkX << 4) + 15, x);
        double dz = axisGap(chunkZ << 4, (chunkZ << 4) + 15, z);
        return dx * dx + dz * dz;
    }

    private static double axisGap(int min, int max, int value) {
        if (value < min) {
            return min - value;
        }
        return value > max ? value - max : 0;
    }

    /**
     * Is the cached link still a portal? An unloaded chunk is not evidence
     * against it and must never be loaded to check — the same rule
     * {@link ArrivalResolver} applies to a registered arrival.
     */
    private static boolean stillLinked(ServerWorld targetWorld, BlockPos pos) {
        WorldChunk chunk = targetWorld.getChunkManager()
                .getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        return chunk == null || chunk.getBlockState(pos).contains(Properties.HORIZONTAL_AXIS);
    }

    /** Drops every link into one target world, on {@code ServerWorldEvents.UNLOAD}. */
    public static void invalidate(RegistryKey<World> targetWorld) {
        LINKS.remove(targetWorld);
    }

    /** Resets all session state (server shutdown). */
    public static void clear() {
        LINKS.clear();
    }

    /** One zone's answer: the linked portal, or {@code null} for a scan that found none. */
    private static final class Link {
        private final BlockPos pos;
        private final long tick;

        private Link(BlockPos pos, long tick) {
            this.pos = pos;
            this.tick = tick;
        }
    }
}
