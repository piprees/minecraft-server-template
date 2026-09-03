package com.customdimensions.immersive;

import com.customdimensions.portal.PortalHelper;
import com.customdimensions.portal.PortalShape;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.TeleportTarget;
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
import java.util.Set;
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
 * consulted, via {@link PortalHelper#residentChunk};
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
        String key = keyFor(zone.sourceWorld, centre);

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
     * The pair vanilla just used, taken from the traversal itself.
     *
     * <p>A crossing is the one moment both ends are known for certain: no
     * search, no unread-chunk doubt, and nothing loaded that vanilla was not
     * loading anyway. {@link #search} stays the fallback for a portal nobody
     * has crossed this session.
     *
     * <p>Only a portal a PRESENTATION zone covers is recorded — a route this
     * mod presents and vanilla owns. A mod-owned portal already knows its own
     * arrival.
     */
    public static void recordVanillaCrossing(
            ServerWorld sourceWorld, BlockPos sourcePos, TeleportTarget target) {
        if (target == null || sourcePos == null || target.world() == null) {
            return;
        }
        RegistryKey<World> sourceKey = sourceWorld.getRegistryKey();
        Set<BlockPos> sourceInterior = presentationInteriorAt(sourceKey, sourcePos);
        if (sourceInterior == null) {
            return;
        }
        ServerWorld targetWorld = target.world();
        BlockPos arrival = BlockPos.ofFloored(target.pos());
        // Vanilla has just built or found the far portal, so its chunk is
        // resident; an absent one means there is no pair to record.
        if (!PortalHelper.isColumnResident(targetWorld, arrival.getX(), arrival.getZ())) {
            return;
        }
        Set<BlockPos> targetInterior = portalAreaAround(targetWorld, arrival);
        if (targetInterior.isEmpty()) {
            return;
        }
        recordCrossing(sourceKey, sourceInterior, targetWorld.getRegistryKey(), targetInterior,
                sourceWorld.getServer().getTicks());
    }

    /**
     * Both directions of one confirmed pair. The preview is wanted from either
     * side, and a zone only ever asks about its OWN target, so each side needs
     * its own entry.
     *
     * <p>In memory only, exactly like the presentation zones it serves: a link
     * on disk would be read back by an older jar as an ordinary zone and would
     * reclaim traversal on a vanilla portal.
     */
    static void recordCrossing(RegistryKey<World> sourceWorld, Set<BlockPos> sourceInterior,
            RegistryKey<World> targetWorld, Set<BlockPos> targetInterior, long tick) {
        BlockPos sourceCentre = PortalShape.centreOf(sourceInterior);
        BlockPos targetCentre = PortalShape.centreOf(targetInterior);
        if (sourceCentre == null || targetCentre == null) {
            return;
        }
        link(targetWorld, keyFor(sourceWorld, sourceCentre), floorRow(targetCentre, targetInterior),
                tick);
        link(sourceWorld, keyFor(targetWorld, targetCentre), floorRow(sourceCentre, sourceInterior),
                tick);
    }

    /** The recorded link for this zone's target, or null. */
    static BlockPos cachedLink(RegistryKey<World> targetWorld, PortalHelper.PortalZone zone) {
        BlockPos centre = PortalShape.centreOf(zone.interior);
        Map<String, Link> forTarget = LINKS.get(targetWorld);
        if (centre == null || forTarget == null) {
            return null;
        }
        Link cached = forTarget.get(keyFor(zone.sourceWorld, centre));
        return cached == null ? null : cached.pos;
    }

    // One expression, every caller: a second copy of this format would drift
    // and a recorded crossing would be invisible to the approach path.
    private static String keyFor(RegistryKey<World> sourceWorld, BlockPos centre) {
        return sourceWorld.getValue() + "|" + centre.toShortString();
    }

    private static void link(RegistryKey<World> targetWorld, String key, BlockPos pos, long tick) {
        LINKS.computeIfAbsent(targetWorld, k -> new ConcurrentHashMap<>())
                .put(key, new Link(pos, tick));
    }

    /**
     * The centre column at the portal's bottom row — the row {@code search}
     * answers with, since its tie-break is the lowest Y, and the row the
     * projection lands the source interior's floor on.
     */
    private static BlockPos floorRow(BlockPos centre, Set<BlockPos> interior) {
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : interior) {
            minY = Math.min(minY, p.getY());
        }
        return new BlockPos(centre.getX(), minY, centre.getZ());
    }

    private static Set<BlockPos> presentationInteriorAt(RegistryKey<World> world, BlockPos pos) {
        for (PortalHelper.PortalZone zone : PortalHelper.getPresentationZones(world)) {
            if (zone.interior.contains(pos)) {
                return zone.interior;
            }
        }
        return null;
    }

    // An arrival position is the entity's feet inside the portal, so the
    // block below or above it can be the portal block on an off-by-one.
    private static Set<BlockPos> portalAreaAround(ServerWorld world, BlockPos pos) {
        for (BlockPos probe : new BlockPos[] {pos, pos.up(), pos.down()}) {
            if (PortalHelper.isVanillaPortalBlock(world.getBlockState(probe))) {
                return PortalHelper.collectPortalArea(world, probe);
            }
        }
        return Set.of();
    }

    /**
     * The End's arrival is a fixed platform at {@code ServerWorld.END_SPAWN_POS},
     * built on demand — no index, no search. Answered only when its chunk is
     * already resident, so the projector still never reads an unloaded column.
     */
    private static BlockPos endPlatform(ServerWorld targetWorld) {
        BlockPos platform = ServerWorld.END_SPAWN_POS;
        return PortalHelper.isColumnResident(targetWorld, platform.getX(), platform.getZ())
                ? platform : null;
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
                if (PortalHelper.residentChunk(targetWorld, chunkX, chunkZ) == null) {
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
        WorldChunk chunk = PortalHelper.residentChunk(targetWorld, pos.getX() >> 4, pos.getZ() >> 4);
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
