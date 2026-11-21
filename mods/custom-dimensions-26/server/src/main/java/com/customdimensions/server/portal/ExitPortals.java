package com.customdimensions.server.portal;

import com.customdimensions.core.config.DimensionConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.portal.PortalShape;
import com.customdimensions.server.CustomDimensions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Exit portals ({@code exitPortal}): a frame near the dimension's spawn,
 * built when the level is open and rebuilt when broken, checked every
 * {@link #CHECK_INTERVAL_TICKS}. It leads out by its exit mode and is never
 * broken from the far end. A cold site gets a ticket rather than a sync load.
 */
public final class ExitPortals {
    static final int CHECK_INTERVAL_TICKS = 100;
    static final int SPAWN_OFFSET_X = 8;

    private static final Map<ResourceKey<Level>, PortalZone> BUILT = new HashMap<>();

    private ExitPortals() {
    }

    static void clear() {
        BUILT.clear();
    }

    /** The exit portal standing in {@code level}, once built. */
    public static PortalZone of(ResourceKey<Level> level) {
        return BUILT.get(level);
    }

    static void tick(ServerLevel level) {
        if (level.getGameTime() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        DimensionConfig config = PortalCatalogue.dimension(level.dimension());
        if (config == null || !config.hasExitPortal()) {
            return;
        }
        ensure(level, config);
    }

    static void ensure(ServerLevel level, DimensionConfig config) {
        PortalZone current = BUILT.get(level.dimension());
        if (current != null && PortalRegistry.get().zones(level.dimension()).contains(current)) {
            return;
        }
        int[] explicit = config.getExitPortal().getExplicitPos();
        int[] spawn = config.getSpawn();
        int x = explicit != null ? explicit[0] : (spawn != null ? spawn[0] : 0) + SPAWN_OFFSET_X;
        int z = explicit != null ? explicit[2] : spawn != null ? spawn[2] : 0;
        PortalTickets.hold(level, x, z);
        if (!PortalWorld.resident(level, x - 2, z - 2, x + 4, z + 4)) {
            return;
        }
        PortalDefinition def = config.toPortalDefinition();
        if (def == null) {
            def = new PortalDefinition(config.getName(), "minecraft:crying_obsidian", "", config.getDimensionId(), null, 11);
        }
        String shape = PortalShape.normalise(def.getShape());
        Direction.Axis axis = PortalShape.END_EXIT.equals(shape) ? Direction.Axis.Y : Direction.Axis.X;
        PortalZone existing = PortalRegistry.get().arrivalNear(level.dimension(), x, z, 3,
                a -> {
                    PortalZone.ReturnTarget t = PortalRegistry.get().target(a.world, a.landingCell());
                    return t != null && t.exitMode() != null && t.exitMode().equals(config.getExitPortal().getTargetMode());
                });
        if (existing != null) {
            BUILT.put(level.dimension(), existing);
            return;
        }
        int y = PortalWorld.surfaceY(level, x, z);
        Set<BlockPos> interior = interior(shape, axis, x, y, z);
        PortalZone built = PortalTravel.build(level, interior, axis, def, Level.OVERWORLD, y,
                config.getExitPortal().getTargetMode(), null, null);
        BUILT.put(level.dimension(), built);
        CustomDimensions.LOG.info("Built exit portal in {} at ({}, {}, {})", level.dimension().identifier(), x, y, z);
    }

    private static Set<BlockPos> interior(String shape, Direction.Axis axis, int x, int y, int z) {
        Set<BlockPos> out = new HashSet<>();
        if (axis == Direction.Axis.Y) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    out.add(new BlockPos(x + dx, y, z + dz));
                }
            }
            return out;
        }
        boolean gateway = PortalShape.END_GATEWAY.equals(shape);
        boolean door = PortalShape.DOOR.equals(shape);
        int width = gateway || door ? 1 : 2;
        int height = gateway ? 1 : door ? 2 : 3;
        for (int dx = 0; dx < width; dx++) {
            for (int dy = 0; dy < height; dy++) {
                out.add(new BlockPos(x + dx, y + dy, z));
            }
        }
        return out;
    }
}
