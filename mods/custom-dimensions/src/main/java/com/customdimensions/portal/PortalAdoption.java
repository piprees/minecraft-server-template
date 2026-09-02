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
    // validator uses. Every cell is a neighbour of the area the player is
    // standing in, so no chunk is loaded to answer this.
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
