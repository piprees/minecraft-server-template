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
 * Where the other side is: the single answer to "what Y does this zone's
 * arrival sit at", shared by the immersive preview ({@link
 * ImmersiveProjector}) and entity pass-through ({@link EntityPassthrough}).
 *
 * <h2>Why this is not just the heightmap</h2>
 * It used to be, in a private copy in each of those two classes, and both
 * were wrong the moment a portal had been used once.
 *
 * The player path in {@code ServerWorldMixin} resolves the arrival in two
 * steps: {@code findSurfaceY} for a column that has no portal yet, then
 * {@code findExistingPortal} — and <b>when an arrival portal already exists
 * it lands the player AT that portal</b> ({@code landY = existing.getY()}),
 * never at the recomputed surface. That distinction turns out to matter,
 * because building the arrival portal changes the very heightmap the surface
 * was derived from: {@code createTargetPortal} places SOLID frame blocks on
 * the up-neighbour of the top interior row, so
 * {@code MOTION_BLOCKING_NO_LEAVES} at the arrival column afterwards reports
 * the top of our own frame, not the ground.
 *
 * Measured on the live server (2026-07-25, overworld -&gt;
 * adventure:the_blossom_gardens at scale 1): ground at y=62, arrival portal
 * interior y=63..65 with frame rows at 62 and 66. A traversing player lands
 * at y=63; the heightmap answers 67. The preview was therefore built four
 * blocks above the destination and showed the empty sky over it — which
 * Phase 4e then correctly diagnosed as "this side is all air" and shrank to
 * a 2-block preview. Every portal in the game would have degraded that way
 * after its first use, silently.
 *
 * So: <b>registered arrival portal first, heightmap only as the fallback for
 * a column that has none</b> — which is exactly the order the player path
 * uses, and the fallback then agrees with the portal the player would cause
 * to be built.
 *
 * <h2>Rule 1 still holds: never sync-load a chunk</h2>
 * The registry lookup ({@link PortalHelper#findRegisteredPortalNear}) is a
 * pure in-memory map read — no block states, no chunk access, no mutation.
 * The heightmap read uses the non-loading accessor and reports {@link
 * #NO_ARRIVAL} rather than generating anything. {@code findExistingPortal}
 * is deliberately NOT used here despite being the player path's tool: it
 * scans up to 11x11x33 real block states and would touch unloaded chunks.
 */
public final class ArrivalResolver {

    /** Arrival column's chunk isn't loaded yet — no arrival data this pass. */
    public static final int NO_ARRIVAL = Integer.MIN_VALUE;

    /**
     * Mirrors {@code ServerWorldMixin}'s {@code findExistingPortal(…, 5, 16, …)}
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
     * @param axis the source zone's axis, used only to check that a
     *             registered position still carries the right kind of portal
     *             block
     */
    public static int arrivalY(ServerWorld targetWorld, int arrivalX, int arrivalZ, Direction.Axis axis) {
        int surfaceY = heightmapSurfaceY(targetWorld, arrivalX, arrivalZ);
        if (surfaceY == NO_ARRIVAL) {
            return NO_ARRIVAL;
        }
        // Centred on the heightmap surface, exactly like the player path —
        // including the case where our own frame has pushed that surface up,
        // which is why the vertical radius is 16 and not something tighter.
        BlockPos existing = PortalHelper.findRegisteredPortalNear(
                targetWorld.getRegistryKey(), arrivalX, surfaceY, arrivalZ,
                SEARCH_RADIUS_H, SEARCH_RADIUS_V);
        if (existing != null && stillAPortal(targetWorld, existing, axis)) {
            return existing.getY();
        }
        // No arrival portal (or the registry is stale and the blocks are
        // gone): the player path would build one at the surface and land
        // there, so the surface is the right answer again.
        return surfaceY;
    }

    /**
     * Is the registry's answer still true on the ground?
     *
     * <p>Registrations are not removed when a portal block is destroyed, so a
     * stale entry could otherwise pin the preview to a portal that no longer
     * exists. Verified only when the chunk happens to be loaded — an unloaded
     * chunk is not evidence against the registry, and this must never load
     * one to find out.
     *
     * <p>The {@code NetherPortalBlock.AXIS} property is deliberately NOT
     * compared. Anchor arrivals legitimately end up on the other horizontal
     * axis (the first source portal's shape wins, and
     * {@code teleportToAnchor} explicitly re-searches the other axis to reuse
     * them), so an axis-strict check would reject a portal the player path
     * happily lands in. Gateways are excluded outright: no source zone that
     * reaches this resolver is a gateway zone.
     */
    private static boolean stillAPortal(ServerWorld targetWorld, BlockPos pos, Direction.Axis axis) {
        WorldChunk chunk = targetWorld.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
        if (chunk == null) {
            return true;
        }
        BlockState state = chunk.getBlockState(pos);
        return axis == Direction.Axis.Y ? state.isOf(Blocks.END_PORTAL) : state.isOf(Blocks.NETHER_PORTAL);
    }

    /**
     * {@link PortalHelper#findSurfaceY}'s maths — void fallback and
     * build-limit headroom included, so a column with no portal resolves
     * exactly where the player path would put one — read off an
     * already-loaded chunk instead of force-generating one.
     */
    public static int heightmapSurfaceY(ServerWorld targetWorld, int x, int z) {
        WorldChunk chunk = targetWorld.getChunkManager().getWorldChunk(x >> 4, z >> 4, false);
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
