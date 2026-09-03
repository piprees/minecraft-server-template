package com.customdimensions.immersive;

import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * The Y this zone's arrival sits at, shared by {@link ImmersiveProjector}
 * and {@link EntityPassthrough}.
 *
 * <p>Checks the registered arrival first, heightmap only as fallback — the
 * same order {@code ServerWorldMixin}'s player path uses. A player who has
 * already built an arrival lands AT it, not at a recomputed surface:
 * {@code createTargetPortal} places solid frame blocks above the interior,
 * so the heightmap there afterwards reports the top of that frame, not the
 * ground — a heightmap-only read drifts upward once a portal has been
 * used.
 *
 * <p>Never waits on a chunk: the registry lookup is a pure in-memory read,
 * and the heightmap read goes through {@link PortalHelper#residentChunk},
 * returning {@link #NO_ARRIVAL} for an unloaded one. The three-argument
 * {@code getWorldChunk(x, z, false)} would NOT do — it waits for a ticketed
 * chunk to finish generating.
 */
public final class ArrivalResolver {

    /** Arrival column's chunk isn't loaded yet — no arrival data this pass. */
    public static final int NO_ARRIVAL = Integer.MIN_VALUE;

    /**
     * Mirrors {@code ServerWorldMixin}'s {@code findRegisteredPortalNear(…, 5, 16)}
     * search box. Same numbers on purpose: a portal the player path would
     * reuse must be one this resolver finds, and vice versa.
     */
    private static final int SEARCH_RADIUS_H = 5;
    private static final int SEARCH_RADIUS_V = 16;

    private ArrivalResolver() {
    }

    /**
     * The Y that this zone's arrival sits at — the row a source interior's
     * floor maps onto — or {@link #NO_ARRIVAL} when the arrival column's
     * chunk is not loaded.
     *
     * @param axis kept so callers read alike; the registry is axis-agnostic
     */
    public static int arrivalY(ServerWorld targetWorld, int arrivalX, int arrivalZ, Direction.Axis axis) {
        int surfaceY = heightmapSurfaceY(targetWorld, arrivalX, arrivalZ);
        if (surfaceY == NO_ARRIVAL) {
            return NO_ARRIVAL;
        }
        // Vertical radius is 16, not tighter, because our own frame can have
        // pushed the heightmap surface up.
        BlockPos existing = PortalHelper.findRegisteredPortalNear(
                targetWorld.getRegistryKey(), arrivalX, surfaceY, arrivalZ,
                SEARCH_RADIUS_H, SEARCH_RADIUS_V);
        if (existing != null) {
            return existing.getY();
        }
        // No arrival yet: the player path would build one at the surface, so
        // the surface is correct here too.
        return surfaceY;
    }

    /**
     * Mirrors {@link PortalHelper#findSurfaceY}'s maths (void fallback,
     * build-limit headroom), but reads an already-loaded chunk instead of
     * force-generating one.
     */
    public static int heightmapSurfaceY(ServerWorld targetWorld, int x, int z) {
        WorldChunk chunk = PortalHelper.residentChunk(targetWorld, x >> 4, z >> 4);
        if (chunk == null) {
            return NO_ARRIVAL;
        }
        int surfaceY = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        if (surfaceY <= targetWorld.getBottomY() + 1) {
            return PortalHelper.VOID_FALLBACK_Y;
        }
        return Math.min(surfaceY, targetWorld.getTopY() - 8);
    }
}
