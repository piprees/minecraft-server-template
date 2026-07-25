package com.customdimensions.portal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;

/**
 * Where an arrival portal goes, and what shape it is.
 *
 * <h2>Why this exists</h2>
 * Arrivals used to be built at {@code findSurfaceY} — the
 * {@code MOTION_BLOCKING_NO_LEAVES} heightmap, plus one — in the shape of
 * whatever the player happened to build on the source side. Both halves were
 * wrong, and the first one traps people.
 *
 * <p><b>The heightmap is not the ground in a world with a ceiling.</b> In a
 * nether- or cave-type dimension it reports the top of the roof, so the
 * portal is built inside solid rock and the player arrives encased. Reported
 * in game 2026-07-25 arriving in the_ember_fields at y=248, walled in by
 * calcite, having to mine out. Vanilla does not do this: {@code PortalForcer}
 * scans for an actual open pocket and carves one when it cannot find any.
 *
 * <p><b>Mirroring the source shape propagates it forever.</b> An arrival the
 * same size as whatever frame someone happened to build is unpredictable —
 * and the shape then round-trips, so an odd frame on one side means an odd
 * frame on the other for good. Arrivals are a fixed, recognisable size: the
 * way home should look the same wherever you came from.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>Standard size: 2 wide x 3 tall for a vertical portal, 3x3 for a
 *       horizontal one.</li>
 *   <li>Site: the lowest-cost open pocket at or below the natural surface —
 *       interior clear, something solid under the floor row.</li>
 *   <li>Failing that, a carved pocket. Being able to step out is not
 *       negotiable; a portal that strands someone is worse than one in
 *       slightly rude terrain.</li>
 * </ul>
 */
public final class PortalSite {

    /** No open site found by the scan; the caller should carve. */
    public static final int NO_SITE = Integer.MIN_VALUE;

    /** Standard arrival interior: 2 wide, 3 tall (vertical portals). */
    public static final int STANDARD_WIDTH = 2;
    public static final int STANDARD_HEIGHT = 3;
    /** Standard arrival pad for a horizontal (Y-axis) portal. */
    public static final int STANDARD_PAD = 3;

    /** How far below the starting Y the scan is willing to look. */
    private static final int SCAN_DEPTH = 48;

    /** Clearance kept either side of the plane so arrivals can step out. */
    private static final int EGRESS_DEPTH = 1;

    private PortalSite() {
    }

    /**
     * The standard arrival interior for an axis, with its floor row at
     * {@code baseY} and centred on {@code (centreX, centreZ)}.
     *
     * <p>Deterministic given the same inputs, which matters: the immersive
     * projector reproduces arrival geometry independently and the two must
     * agree.
     */
    public static Set<BlockPos> standardInterior(int centreX, int baseY, int centreZ,
            Direction.Axis axis) {
        Set<BlockPos> out = new HashSet<>();
        if (axis == Direction.Axis.Y) {
            for (int dx = 0; dx < STANDARD_PAD; dx++) {
                for (int dz = 0; dz < STANDARD_PAD; dz++) {
                    out.add(new BlockPos(centreX + dx, baseY, centreZ + dz));
                }
            }
            return out;
        }
        for (int w = 0; w < STANDARD_WIDTH; w++) {
            for (int h = 0; h < STANDARD_HEIGHT; h++) {
                out.add(axis == Direction.Axis.X
                        ? new BlockPos(centreX + w, baseY + h, centreZ)
                        : new BlockPos(centreX, baseY + h, centreZ + w));
            }
        }
        return out;
    }

    /**
     * The Y the standard interior's floor row should sit at, or
     * {@link #NO_SITE} when nothing suitable was found within
     * {@link #SCAN_DEPTH}.
     *
     * <p>Starts from the roof in a ceilinged world and from the heightmap
     * surface otherwise, then walks DOWN looking for the first place a portal
     * genuinely fits. Walking down rather than up is what keeps an arrival on
     * the ground instead of on top of the terrain that happens to be above
     * it.
     *
     * <p>Reads only; the caller's chunk must already be loaded (this is called
     * from the teleport path, which has just forced it via
     * {@code findSurfaceY}).
     */
    public static int findArrivalY(ServerWorld world, int centreX, int centreZ,
            Direction.Axis axis, int surfaceY) {
        int start = world.getDimension().hasCeiling()
                // The heightmap reads the ROOF here, so it is worse than
                // useless — start just under the logical ceiling instead.
                ? world.getBottomY() + world.getDimension().logicalHeight() - 2
                : surfaceY;
        int floor = world.getBottomY() + 1;
        int lowest = Math.max(floor, start - SCAN_DEPTH);
        for (int y = Math.min(start, world.getTopY() - STANDARD_HEIGHT - 2); y >= lowest; y--) {
            if (fits(world, centreX, y, centreZ, axis)) {
                return y;
            }
        }
        return NO_SITE;
    }

    /**
     * Is the standard interior at this Y clear, with something solid holding
     * up its floor row? Solid support is part of the test because an arrival
     * hanging over a drop is its own kind of trap.
     */
    private static boolean fits(ServerWorld world, int centreX, int baseY, int centreZ,
            Direction.Axis axis) {
        Set<BlockPos> interior = standardInterior(centreX, baseY, centreZ, axis);
        boolean supported = false;
        for (BlockPos p : interior) {
            if (!isClear(world, p)) {
                return false;
            }
            if (p.getY() == baseY) {
                BlockState below = world.getBlockState(p.down());
                if (below.isOpaqueFullCube(world, p.down())) {
                    supported = true;
                }
            }
        }
        return supported;
    }

    private static boolean isClear(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.isReplaceable();
    }

    /**
     * Clear a standing pocket around a placed arrival: the interior's own
     * cells plus {@link #EGRESS_DEPTH} either side of the plane.
     *
     * <p>Runs on EVERY arrival, not just carved ones. A portal built at a
     * perfectly good surface site can still have a hillside pressed against
     * its face, and the failure mode is identical from inside — you arrive,
     * you cannot move, you mine out. Eighteen-ish cells is a small price for
     * "you can always step out of a portal".
     *
     * <p>Never touches the frame ring (the caller places that afterwards) and
     * never breaks blocks with drops — this is world-building, not mining, so
     * it sets air directly with the same flags as the rest of the arrival
     * construction (no neighbour cascade, no Supplementaries piston crash).
     */
    public static void carveEgress(ServerWorld world, Set<BlockPos> interior, Direction.Axis axis) {
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        Direction.Axis normal = axis == Direction.Axis.X ? Direction.Axis.Z
                : axis == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Y;
        Direction positive = Direction.get(Direction.AxisDirection.POSITIVE, normal);
        Direction negative = Direction.get(Direction.AxisDirection.NEGATIVE, normal);
        for (BlockPos p : interior) {
            for (int step = 1; step <= EGRESS_DEPTH; step++) {
                clear(world, p.offset(positive, step), flags);
                clear(world, p.offset(negative, step), flags);
            }
        }
    }

    private static void clear(ServerWorld world, BlockPos pos, int flags) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || PortalHelper.isPortalBlock(state) || state.isOf(Blocks.BEDROCK)
                || world.getBlockEntity(pos) != null) {
            // Never punch through bedrock, a portal, or somebody's chest.
            return;
        }
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), flags);
    }
}
