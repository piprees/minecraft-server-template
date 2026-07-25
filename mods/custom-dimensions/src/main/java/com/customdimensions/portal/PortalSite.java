package com.customdimensions.portal;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

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
    public static final int EGRESS_DEPTH = 1;

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
        int highest = Math.min(start, world.getTopY() - STANDARD_HEIGHT - 2);
        return findArrivalY(centreX, centreZ, axis, highest, lowest,
                pos -> isClear(world, pos),
                pos -> world.getBlockState(pos).isOpaqueFullCube(world, pos));
    }

    /**
     * Pure core of {@link #findArrivalY(ServerWorld, int, int,
     * Direction.Axis, int)}: walk DOWN from {@code highest} to {@code lowest}
     * and return the first Y whose standard interior fits, else
     * {@link #NO_SITE}.
     *
     * <p>Split out so the decision that governs whether a player arrives able
     * to move is testable without a world. The entombment case — every
     * candidate Y solid — is a table entry here, not something you find by
     * standing in it (see {@code TEST-COVERAGE-AUDIT.md}).
     *
     * @param isClear  may a portal cell occupy this position (air/replaceable)
     * @param isOpaque is this position an opaque full cube (floor support)
     */
    public static int findArrivalY(int centreX, int centreZ, Direction.Axis axis,
            int highest, int lowest, Predicate<BlockPos> isClear, Predicate<BlockPos> isOpaque) {
        for (int y = highest; y >= lowest; y--) {
            if (fits(centreX, y, centreZ, axis, isClear, isOpaque)) {
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
    static boolean fits(int centreX, int baseY, int centreZ, Direction.Axis axis,
            Predicate<BlockPos> isClear, Predicate<BlockPos> isOpaque) {
        Set<BlockPos> interior = standardInterior(centreX, baseY, centreZ, axis);
        boolean supported = false;
        for (BlockPos p : interior) {
            if (!isClear.test(p)) {
                return false;
            }
            if (p.getY() == baseY && isOpaque.test(p.down())) {
                supported = true;
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
        for (BlockPos pos : egressCells(interior, axis, EGRESS_DEPTH)) {
            clear(world, pos, flags);
        }
    }

    /**
     * Pure: which positions an egress carve covers — {@code depth} cells out
     * from every interior cell along BOTH normals of the portal plane, and
     * nothing else. Never includes an interior cell (the frame ring lies in
     * the plane, so it is excluded by construction).
     *
     * <p>Extracted so "can a player step out of this portal" is a decidable
     * question rather than an in-game discovery. See {@link #hasEgress}.
     */
    public static Set<BlockPos> egressCells(Set<BlockPos> interior, Direction.Axis axis, int depth) {
        Set<BlockPos> out = new HashSet<>();
        if (interior == null || interior.isEmpty()) {
            return out;
        }
        Direction.Axis normal = axis == Direction.Axis.X ? Direction.Axis.Z
                : axis == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Y;
        Direction positive = Direction.get(Direction.AxisDirection.POSITIVE, normal);
        Direction negative = Direction.get(Direction.AxisDirection.NEGATIVE, normal);
        for (BlockPos p : interior) {
            for (int step = 1; step <= depth; step++) {
                BlockPos plus = p.offset(positive, step);
                BlockPos minus = p.offset(negative, step);
                if (!interior.contains(plus)) {
                    out.add(plus);
                }
                if (!interior.contains(minus)) {
                    out.add(minus);
                }
            }
        }
        return out;
    }

    /**
     * Pure: can somebody standing in this portal actually leave it?
     *
     * <p>True when at least one FULL COLUMN of egress cells on one face is
     * passable — a player needs a body-height gap, not a single hole, and a
     * gap at head height over a solid floor cell is not a way out.
     *
     * <p>This is the invariant behind the worst failure this code has. It was
     * guaranteed only at creation time ({@code createTargetPortal}), so a
     * portal reused by a later traversal — which is every traversal after the
     * first — was never re-checked. A pre-fix arrival, or one later buried by
     * terrain edits or an aura, strands the player permanently. Reported in
     * game 2026-07-25 in {@code adventure:the_ember_fields}.
     */
    public static boolean hasEgress(Set<BlockPos> interior, Direction.Axis axis,
            Predicate<BlockPos> isPassable) {
        if (interior == null || interior.isEmpty()) {
            return true;
        }
        if (axis == Direction.Axis.Y) {
            // A horizontal portal is stepped out of upwards; one clear cell
            // above any interior cell is enough.
            for (BlockPos p : interior) {
                if (isPassable.test(p.up())) {
                    return true;
                }
            }
            return false;
        }
        Direction.Axis normal = axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        int floorY = Integer.MAX_VALUE;
        for (BlockPos p : interior) {
            floorY = Math.min(floorY, p.getY());
        }
        for (Direction.AxisDirection sign : Direction.AxisDirection.values()) {
            Direction face = Direction.get(sign, normal);
            for (BlockPos p : interior) {
                if (p.getY() != floorY) {
                    continue;
                }
                BlockPos foot = p.offset(face);
                if (isPassable.test(foot) && isPassable.test(foot.up())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Egress guarantee for an arrival that ALREADY EXISTS — the reuse path.
     *
     * <p>{@link #carveEgress} runs inside {@code createTargetPortal}, so it
     * only ever fired for a portal being built. Every traversal after the
     * first reuses the existing arrival ({@code findExistingPortal}) and took
     * no egress code path at all, which means:
     *
     * <ul>
     *   <li>an arrival built before the {@code PortalSite} fix stays entombed
     *       forever, and strands the player on EVERY visit;</li>
     *   <li>an arrival later buried — terrain edits, another mod, an aura
     *       converting the cells against its face — is never repaired.</li>
     * </ul>
     *
     * <p>Reported in game 2026-07-25: {@code adventure:the_ember_fields} at
     * y=248, a pre-fix 4x3 arrival with solid calcite on both faces. The
     * player could not move and had to be teleported out.
     *
     * <p>Checks before it writes, so a healthy portal costs a handful of
     * block reads and no world mutation at all — this is on the teleport
     * path, which is rare, but it must not churn blocks on every traversal.
     */
    public static void ensureEgress(ServerWorld world, Set<BlockPos> interior, Direction.Axis axis) {
        if (interior == null || interior.isEmpty()) {
            return;
        }
        if (hasEgress(interior, axis, pos -> isClear(world, pos))) {
            return;
        }
        carveEgress(world, interior, axis);
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
