package com.customdimensions.portal;

import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A portal the mod has no zone or registered cell for — vanilla-built, or lit
 * before the mod saw it — is matched to a portal definition and registered as
 * a source zone. The mod owns every portal in a dimension it configures, and
 * vanilla is never asked, so an unmatched portal would do nothing at all.
 */
public final class PortalAdoption {
    // Every cell offered for adoption this boot, matched or not. The tick path
    // reaches adoption on every entry edge, so a portal no definition fits
    // must cost one set lookup rather than a frame scan.
    private static final Map<RegistryKey<World>, Set<BlockPos>> ATTEMPTED = new HashMap<>();

    private PortalAdoption() {
    }

    /**
     * Registers a zone for an unowned portal area. Returns whether one was
     * added. One attempt per area per boot; a restart re-offers it.
     */
    public static boolean adopt(ServerWorld world, Set<BlockPos> portalBlocks) {
        if (portalBlocks.isEmpty()) {
            return false;
        }
        RegistryKey<World> worldKey = world.getRegistryKey();
        if (!claimAttempt(worldKey, portalBlocks)) {
            return false;
        }
        if (isCoveredByZone(worldKey, portalBlocks)) {
            return false;
        }

        Direction.Axis axis = axisOf(world, portalBlocks);
        List<String> frameIds = frameBlockIds(world, portalBlocks, axis);
        MultiverseConfig config = MultiverseConfig.getInstance();
        BlockPos probe = portalBlocks.iterator().next();

        // A frame a vanillaManaged definition accepts belongs to vanilla, and
        // it is asked FIRST: reserving the_nether otherwise just hands obsidian
        // to the next definition that also builds from it.
        PortalDefinition presented = firstThatFits(world, portalBlocks, axis,
                presentationCandidates(config.getPortals(), frameIds));
        if (presented != null) {
            boolean added = PortalHelper.registerPresentationZone(new PortalHelper.PortalZone(
                    portalBlocks, presented, axis, worldKey, presented.getTargetKey()));
            if (added) {
                System.err.println("[customdimensions] Presentation-only zone at "
                        + probe.toShortString() + " in " + worldKey.getValue() + " as "
                        + presented.getId() + " (" + portalBlocks.size()
                        + " cells) - vanilla keeps the traversal");
            }
            return false;
        }

        PortalDefinition adopted = firstThatFits(world, portalBlocks, axis,
                candidates(config.getPortals(), frameIds));
        if (adopted == null) {
            adopted = firstThatFits(world, portalBlocks, axis, frameDefaults(config, frameIds));
        }

        if (adopted == null) {
            // Inert, not delegated: vanilla answers "the Nether" for every
            // world it is asked about, and a frame no definition describes
            // has no destination this mod can name.
            System.err.println("[customdimensions] No portal definition fits the frame at "
                    + probe.toShortString() + " in " + worldKey.getValue()
                    + " " + frameIds + " - portal left inert");
            return false;
        }
        PortalHelper.registerZone(new PortalHelper.PortalZone(
                portalBlocks, adopted, axis, worldKey, adopted.getTargetKey()));
        System.err.println("[customdimensions] Adopted unowned portal at " + probe.toShortString()
                + " in " + worldKey.getValue() + " as " + adopted.getId()
                + " (" + portalBlocks.size() + " cells)");
        return true;
    }

    /** True the first time this area is offered; false on every later tick. */
    public static boolean claimAttempt(RegistryKey<World> world, Set<BlockPos> cells) {
        Set<BlockPos> attempted = ATTEMPTED.computeIfAbsent(world, k -> new HashSet<>());
        if (attempted.contains(cells.iterator().next())) {
            return false;
        }
        attempted.addAll(cells);
        return true;
    }

    public static void resetAttempts() {
        ATTEMPTED.clear();
    }

    /** Definitions whose frame accepts one of these block ids, in config order. */
    public static List<PortalDefinition> candidates(
            List<PortalDefinition> portals, Collection<String> frameIds) {
        List<PortalDefinition> matches = new ArrayList<>();
        for (PortalDefinition def : portals) {
            if (def.isVanillaManaged()) {
                continue;
            }
            for (String id : frameIds) {
                if (def.resolveFrameMatcher().acceptsBlockId(id)) {
                    matches.add(def);
                    break;
                }
            }
        }
        return matches;
    }

    /**
     * Definitions whose frame accepts one of these block ids AND that hand the
     * traversal to vanilla — the complement of {@link #candidates}. A match
     * here produces geometry for the immersive preview and nothing else.
     */
    public static List<PortalDefinition> presentationCandidates(
            List<PortalDefinition> portals, Collection<String> frameIds) {
        List<PortalDefinition> matches = new ArrayList<>();
        for (PortalDefinition def : portals) {
            if (!def.isVanillaManaged() || def.getImmersive() == null) {
                continue;
            }
            for (String id : frameIds) {
                if (def.resolveFrameMatcher().acceptsBlockId(id)) {
                    matches.add(def);
                    break;
                }
            }
        }
        return matches;
    }

    /**
     * The portals near enough to a player to be worth offering, in the order
     * they were found. Pure: the world read that produces {@code known} and
     * the adoption that consumes the answer both live in the caller.
     *
     * <p>{@code isWithinDistance} is the projector's own activation test, so a
     * portal this refuses is one the preview would not draw anyway; a portal
     * whose footprint is not resident is refused too, and offered again on the
     * next pass.
     */
    public static List<BlockPos> dueForPresentation(List<BlockPos> known, BlockPos playerPos,
            int range, Set<BlockPos> alreadyCovered, ColumnResidency resident) {
        List<BlockPos> due = new ArrayList<>();
        for (BlockPos pos : known) {
            if (!alreadyCovered.contains(pos) && pos.isWithinDistance(playerPos, range)
                    && footprintResident(pos, resident)) {
                due.add(pos);
            }
        }
        return due;
    }

    /** Whether the chunk holding a block column is loaded right now. */
    @FunctionalInterface
    public interface ColumnResidency {
        boolean isResident(int blockX, int blockZ);
    }

    /**
     * How far from one of its blocks a portal area and its frame ring reach.
     * Vanilla's widest frame is 23 blocks across, so this over-covers whatever
     * the portal's axis.
     */
    public static final int FOOTPRINT_RADIUS = 23;

    /**
     * Whether every chunk adopting this portal would read is loaded. The fill
     * and the frame walk both go through {@code World.getBlockState}, which
     * resolves via {@code getChunk(create = true)} and generates terrain on the
     * calling thread ([K1]/[K6]). On contact the player is the chunk ticket;
     * on approach nobody is near the portal, so this has to be asked.
     */
    public static boolean footprintResident(BlockPos pos, ColumnResidency resident) {
        for (int chunkX = (pos.getX() - FOOTPRINT_RADIUS) >> 4;
                chunkX <= (pos.getX() + FOOTPRINT_RADIUS) >> 4; chunkX++) {
            for (int chunkZ = (pos.getZ() - FOOTPRINT_RADIUS) >> 4;
                    chunkZ <= (pos.getZ() + FOOTPRINT_RADIUS) >> 4; chunkZ++) {
                if (!resident.isResident(chunkX << 4, chunkZ << 4)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Which tick of the scan interval a world takes. Every world reads one
     * server tick counter, so unphased they all scan together; this is the
     * same hash-into-the-interval the projector's particles use.
     */
    public static int approachPhase(String worldId, int interval) {
        return Math.floorMod(worldId.hashCode(), Math.max(1, interval));
    }

    /**
     * How far off a portal an approach pass has to look. Approach has no zone
     * to read a range from — that is the gap it closes — so the widest range
     * any definition that could produce a presentation zone asks for stands in.
     * Zero means no definition can, and the pass has nothing to do.
     */
    public static int presentationRange(List<PortalDefinition> portals) {
        int range = 0;
        for (PortalDefinition def : portals) {
            if (def.isVanillaManaged() && def.getImmersive() != null) {
                range = Math.max(range, def.getImmersive().activationRange());
            }
        }
        return range;
    }

    private static List<PortalDefinition> frameDefaults(
            MultiverseConfig config, Collection<String> frameIds) {
        List<PortalDefinition> defaults = new ArrayList<>();
        for (String id : frameIds) {
            PortalDefinition fallback = config.getDefaultPortalForFrameBlock(id);
            if (fallback != null) {
                defaults.add(fallback);
            }
        }
        return defaults;
    }

    // A candidate is accepted only if the zone it would produce passes the
    // same validity check ServerWorldMixin runs every tick — otherwise the
    // adoption is undone next tick, taking the portal blocks with it.
    private static PortalDefinition firstThatFits(ServerWorld world, Set<BlockPos> interior,
            Direction.Axis axis, List<PortalDefinition> candidates) {
        for (PortalDefinition def : candidates) {
            try {
                PortalHelper.PortalZone probe = new PortalHelper.PortalZone(
                        interior, def, axis, world.getRegistryKey(), def.getTargetKey());
                if (PortalHelper.isZoneValid(world, probe)) {
                    return def;
                }
            } catch (RuntimeException ignored) {
                // A malformed targetDimension on one definition must never
                // stop the rest of the list being tried.
            }
        }
        return null;
    }

    private static boolean isCoveredByZone(RegistryKey<World> worldKey, Set<BlockPos> cells) {
        for (PortalHelper.PortalZone zone : PortalHelper.getSourceZones(worldKey)) {
            for (BlockPos cell : cells) {
                if (zone.interior.contains(cell)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Direction.Axis axisOf(ServerWorld world, Set<BlockPos> cells) {
        BlockState state = world.getBlockState(cells.iterator().next());
        if (state.isOf(Blocks.NETHER_PORTAL) && state.contains(NetherPortalBlock.AXIS)) {
            return state.get(NetherPortalBlock.AXIS);
        }
        return Direction.Axis.Y;
    }

    // Frame ring around the interior, read through the same plane walk the
    // validator uses. Every read lands within FOOTPRINT_RADIUS of the area, so
    // a caller that has no player standing in it proves that square resident.
    private static List<String> frameBlockIds(
            ServerWorld world, Set<BlockPos> cells, Direction.Axis axis) {
        Set<String> ids = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            for (Direction dir : PortalHelper.planeDirections(axis)) {
                BlockPos neighbour = cell.offset(dir);
                if (cells.contains(neighbour)) {
                    continue;
                }
                ids.add(Registries.BLOCK.getId(world.getBlockState(neighbour).getBlock()).toString());
            }
        }
        return new ArrayList<>(ids);
    }
}
