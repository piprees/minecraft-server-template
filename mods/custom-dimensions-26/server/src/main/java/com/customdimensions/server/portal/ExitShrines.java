package com.customdimensions.server.portal;

import com.customdimensions.core.config.DimensionConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.server.CustomDimensions;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A definition's {@code exitShrines}: an {@code adventure:exit_shrine} ruin
 * carries a beacon under a portal frame. A chunk load notes every beacon in a
 * dimension with shrines enabled; the level's next tick checks the frame ring
 * in each of the four rotations, lays the dimension's frame block over it and
 * registers the 2x3 opening as a permanent exit zone with the shrine's target
 * ({@code bed} by default, the {@code ExitTarget} grammar). The zone is saved
 * with the portal links, and finding the same shrine again changes nothing.
 * <p>
 * Template contract ({@code scripts/gen-exit-shrine.py}): the beacon under the
 * first interior column, the second column one step along the frame axis, the
 * interior 2 wide by 3 tall, rails at beacon+1 and beacon+5.
 */
public final class ExitShrines {
    private static final Map<ResourceKey<Level>, Set<BlockPos>> PENDING = new ConcurrentHashMap<>();
    private static final Direction[] ROTATIONS = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};

    private ExitShrines() {
    }

    static boolean enabled(ResourceKey<Level> key) {
        DimensionConfig config = PortalCatalogue.dimension(key);
        return config != null && config.hasExitShrines();
    }

    /** Fabric's chunk-load event: beacons are queued, never handled inside the load. */
    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        if (!enabled(level.dimension())) {
            return;
        }
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            if (chunk.getBlockState(pos).is(Blocks.BEACON)) {
                PENDING.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
            }
        }
    }

    /** The level's tick: registers the shrines its chunk loads found. */
    static void tick(ServerLevel level) {
        Set<BlockPos> pending = PENDING.remove(level.dimension());
        if (pending == null || pending.isEmpty() || !enabled(level.dimension())) {
            return;
        }
        for (BlockPos beacon : pending) {
            register(level, beacon);
        }
    }

    /** Registers the shrine whose beacon is at {@code beacon}; the zone, or null when the frame is not intact. */
    public static PortalZone register(ServerLevel level, BlockPos beacon) {
        if (!level.getBlockState(beacon).is(Blocks.BEACON)) {
            return null;
        }
        DimensionConfig config = PortalCatalogue.dimension(level.dimension());
        for (Direction dir : ROTATIONS) {
            if (!ringIntact(level, beacon, dir)) {
                continue;
            }
            Set<BlockPos> interior = new HashSet<>();
            for (int dy = 2; dy <= 4; dy++) {
                interior.add(beacon.above(dy));
                interior.add(beacon.above(dy).relative(dir));
            }
            Direction.Axis axis = dir.getAxis();
            PortalRegistry registry = PortalRegistry.get();
            PortalZone existing = registry.zoneAt(level.dimension(), beacon.above(2));
            if (existing != null) {
                return existing;
            }
            PortalDefinition def = definition(config);
            String exitMode = config.getExitShrines().getTargetMode();
            PortalZone.ReturnTarget target = new PortalZone.ReturnTarget(Level.OVERWORLD, beacon.getY(),
                    Portals.parseColor(def.getColor()), def.getCooldown(), def.getParticleType(),
                    exitMode, null, null);
            for (BlockPos p : interior) {
                registry.putTarget(level.dimension(), p, target);
            }
            PortalZone zone = new PortalZone(interior, def, axis, level.dimension(), Level.OVERWORLD, true);
            registry.addIfAbsent(zone);
            relay(level, beacon, dir, def);
            PortalWorld.fill(level, interior, axis);
            registry.save();
            CustomDimensions.LOG.info("exit shrine registered in {} at {} (axis {}) to {}", level.dimension().identifier(),
                    beacon.toShortString(), axis, exitMode);
            return zone;
        }
        CustomDimensions.LOG.warn("exit shrine beacon in {} at {} has no intact frame in any rotation; not registered",
                level.dimension().identifier(), beacon.toShortString());
        return null;
    }

    /** The dimension's own portal, else crying obsidian, as an exit portal is built. */
    static PortalDefinition definition(DimensionConfig config) {
        PortalDefinition def = config.toPortalDefinition();
        return def != null ? def
                : new PortalDefinition(config.getName(), "minecraft:crying_obsidian", "", config.getDimensionId(), null, 11);
    }

    /** The ring rebuilt in the dimension's frame block, so the arrival frame check accepts it. */
    private static void relay(ServerLevel level, BlockPos beacon, Direction dir, PortalDefinition def) {
        Block place = PortalWorld.placeState(def, null).getBlock();
        if (level.getBlockState(beacon.above()).is(place)) {
            return;
        }
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos p : ring(beacon, dir)) {
            level.setBlock(p, place.defaultBlockState(), flags);
        }
    }

    private static Set<BlockPos> ring(BlockPos beacon, Direction dir) {
        Set<BlockPos> out = new HashSet<>();
        for (int step = 0; step <= 1; step++) {
            BlockPos col = beacon.relative(dir, step);
            out.add(col.above(1));
            out.add(col.above(5));
        }
        for (int dy = 2; dy <= 4; dy++) {
            out.add(beacon.relative(dir, -1).above(dy));
            out.add(beacon.relative(dir, 2).above(dy));
        }
        return out;
    }

    /** Rails under and over both columns and the side columns, all one non-air block. */
    static boolean ringIntact(ServerLevel level, BlockPos beacon, Direction dir) {
        Block frame = level.getBlockState(beacon.above()).getBlock();
        if (frame == Blocks.AIR) {
            return false;
        }
        for (BlockPos p : ring(beacon, dir)) {
            if (!level.getBlockState(p).is(frame)) {
                return false;
            }
        }
        return true;
    }

    static void clear() {
        PENDING.clear();
    }
}
