package com.customdimensions.immersive;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure geometry for the immersive portal preview (Phase 1): which source
 * positions make up the projection slab behind a portal, where each of them
 * samples from in the target dimension, and which of them a given viewer can
 * actually see through the opening.
 *
 * Deliberately registry-, world- and server-free: every method takes plain
 * values (positions, axis, ints, scale) and returns plain values, so the
 * geometry is unit-testable without a live world.
 *
 * The slab is a CANDIDATE set; {@link #seesThroughOpening} is what decides
 * what a player is sent. That split is deliberate — the slab is a property of
 * the zone and is computed once, while visibility is a property of the viewer
 * and changes every time they move.
 *
 * The target mapping MIRRORS {@code ServerWorldMixin}'s teleport transform
 * exactly — including its integer truncation and its use of the interior's
 * average column for scaled portals but its MIN corner for anchor portals.
 * A preview that disagrees with where the player actually lands is worse
 * than no preview, so any change to the teleport maths must be made here in
 * the same commit.
 */
public final class ProjectionVolume {

    private ProjectionVolume() {
    }

    /**
     * The axis the portal's plane is normal to. A portal whose zone axis is
     * X spans X and Y, so its normal is +/-Z; axis Z spans Z and Y (normal
     * +/-X); axis Y is horizontal (normal +/-Y). This matches
     * {@code PortalHelper.planeDirections}.
     */
    public static Direction.Axis normalAxis(Direction.Axis portalAxis) {
        if (portalAxis == Direction.Axis.X) {
            return Direction.Axis.Z;
        }
        if (portalAxis == Direction.Axis.Z) {
            return Direction.Axis.X;
        }
        return Direction.Axis.Y;
    }

    /**
     * Which way the slab extends for one viewer: always the FAR side of the
     * portal plane, so the projected terrain reads as "through" the frame
     * instead of swallowing the player who is looking at it.
     *
     * Returns {@code current} unchanged when the viewer sits exactly in the
     * plane — they are standing in the doorway, about to teleport, and
     * flipping the slab there would only thrash packets. {@code current}
     * may be null on first activation; the positive direction is used then.
     */
    public static Direction viewerFarSide(Set<BlockPos> interior, Direction.Axis portalAxis,
            BlockPos viewer, Direction current) {
        Direction.Axis normal = normalAxis(portalAxis);
        Direction positive = Direction.get(Direction.AxisDirection.POSITIVE, normal);
        if (interior == null || interior.isEmpty() || viewer == null) {
            return current != null ? current : positive;
        }
        int viewerCoord = coordOn(viewer, normal);
        if (viewerCoord < minOn(interior, normal)) {
            return positive;
        }
        if (viewerCoord > maxOn(interior, normal)) {
            return Direction.get(Direction.AxisDirection.NEGATIVE, normal);
        }
        return current != null ? current : positive;
    }

    /**
     * The rectangular slab of SOURCE-world positions that MIGHT be overwritten
     * with target-world blocks: the interior's in-plane bounding box padded by
     * {@code radius} on both in-plane axes, extended {@code depth} blocks
     * along {@code normal}.
     *
     * The slab starts ONE block past the portal plane, never on it — the
     * doorway and its frame ring keep their real blocks, so the frame stays
     * visible and the interior stays walkable-looking.
     *
     * <b>This is a CANDIDATE set, not the projection.</b> The padded columns
     * sit behind the frame WALL, not behind the opening, and sending them
     * unconditionally is what made destination blocks appear beside and above
     * the frame — the projection bleeding out into the real world for anyone
     * merely looking in the portal's general direction. What a given player
     * actually receives is this set filtered by {@link #seesThroughOpening},
     * which is per-player and changes as they move. {@code radius} therefore
     * only bounds how far the visible cone is allowed to widen behind the
     * opening; it no longer decides what is shown.
     *
     * Returned in a stable, spatially coherent order (the delta pass and the
     * unit tests both rely on it being deterministic).
     */
    public static List<BlockPos> computeSourcePositions(Set<BlockPos> interior,
            Direction.Axis portalAxis, Direction normal, int depth, int radius) {
        if (interior == null || interior.isEmpty() || normal == null || depth <= 0) {
            return List.of();
        }
        Direction.Axis normalAxis = normal.getAxis();
        int minX = minOn(interior, Direction.Axis.X);
        int maxX = maxOn(interior, Direction.Axis.X);
        int minY = minOn(interior, Direction.Axis.Y);
        int maxY = maxOn(interior, Direction.Axis.Y);
        int minZ = minOn(interior, Direction.Axis.Z);
        int maxZ = maxOn(interior, Direction.Axis.Z);
        int pad = Math.max(0, radius);

        if (normalAxis != Direction.Axis.X) {
            minX -= pad;
            maxX += pad;
        }
        if (normalAxis != Direction.Axis.Y) {
            minY -= pad;
            maxY += pad;
        }
        if (normalAxis != Direction.Axis.Z) {
            minZ -= pad;
            maxZ += pad;
        }

        boolean positive = normal.getOffsetX() + normal.getOffsetY() + normal.getOffsetZ() > 0;
        if (normalAxis == Direction.Axis.X) {
            int lo = positive ? maxX + 1 : minX - depth;
            minX = lo;
            maxX = lo + depth - 1;
        } else if (normalAxis == Direction.Axis.Y) {
            int lo = positive ? maxY + 1 : minY - depth;
            minY = lo;
            maxY = lo + depth - 1;
        } else {
            int lo = positive ? maxZ + 1 : minZ - depth;
            minZ = lo;
            maxZ = lo + depth - 1;
        }

        List<BlockPos> out = new ArrayList<>((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    /**
     * The coordinate, along {@code normal}'s axis, of the slab layer nearest
     * the portal plane — the first block of projected depth.
     *
     * Two Phase 4 passes need to name that layer without walking the volume:
     * 4a replaces it with invisible LIGHT blocks, and 4e samples it to decide
     * whether the far side is worth showing at full depth. Both must agree
     * with {@link #computeSourcePositions}, which starts the slab one block
     * past the plane — hence max+1 / min-1 here, the same arithmetic.
     */
    public static int firstLayerCoord(Set<BlockPos> interior, Direction normal) {
        if (interior == null || interior.isEmpty() || normal == null) {
            return 0;
        }
        Direction.Axis axis = normal.getAxis();
        // Same positivity idiom as computeSourcePositions, deliberately: one
        // notion of "which way is forward" for the whole class.
        boolean positive = normal.getOffsetX() + normal.getOffsetY() + normal.getOffsetZ() > 0;
        return positive ? maxOn(interior, axis) + 1 : minOn(interior, axis) - 1;
    }

    /**
     * The coordinate, along the portal's NORMAL axis, of the plane the
     * opening lies in. A zone's interior is one block thick by construction
     * (the flood fill runs in a plane), so min and max agree on that axis.
     *
     * Paired with {@link #seesThroughOpening}, which needs the plane as a
     * number rather than as a set so the caller can resolve it once per pass
     * instead of once per position.
     */
    public static int planeCoord(Set<BlockPos> interior, Direction.Axis normalAxis) {
        if (interior == null || interior.isEmpty() || normalAxis == null) {
            return 0;
        }
        return minOn(interior, normalAxis);
    }

    /**
     * Can this viewer see {@code block} THROUGH the portal opening?
     *
     * <h2>Why this exists</h2>
     * {@link #computeSourcePositions} is a rectangular slab, so most of it
     * sits behind the frame WALL rather than behind the doorway. Sending all
     * of it replaces real blocks beside and above the frame with destination
     * terrain — reported in-game as "the server is rendering stuff when I
     * just look in the general direction of the portal". A portal is a hole,
     * and you can only see through a hole along a line that goes through it.
     *
     * <h2>The test</h2>
     * The straight segment from {@code eye} to the block's CENTRE must cross
     * the portal's mid-plane at a point lying inside the opening. The
     * crossing point is floored to a block position and looked up in
     * {@code interior} itself — never in its bounding box, so an irregular
     * flood-filled frame (an L, an arch, a frame with a notch) masks per
     * cell, exactly like {@code EntityPassthrough}'s swept-path test does.
     *
     * The result is a view frustum: a narrow window at depth 1 that widens
     * with depth, and that slides sideways as the player walks — which is
     * also where the parallax comes from. Positions outside it keep their
     * real blocks.
     *
     * <h2>Cost</h2>
     * One division, two multiply-floors and one {@code Set} lookup per
     * position per player per refresh, with no allocation when the caller
     * passes a {@code scratch} it reuses across the volume. {@code scratch}
     * may be null (tests, one-off calls), which costs one {@link BlockPos}.
     *
     * <h2>Boundary behaviour</h2>
     * Blocks are sampled by their centre, so the frustum edge is decided per
     * block rather than per fragment — a block straddling the edge is in or
     * out as a whole. Testing all eight corners would cost 8x for a
     * half-block of accuracy on a cosmetic effect.
     *
     * An eye AT or PAST the plane (the player standing in the doorway, about
     * to teleport) yields {@code t <= 0} and everything is visible: from
     * inside the aperture there is nothing left to mask, and returning false
     * there would blank the whole preview in the last half-block before a
     * traversal.
     */
    public static boolean seesThroughOpening(Vec3d eye, BlockPos block, Direction.Axis normalAxis,
            int planeCoord, Set<BlockPos> interior, BlockPos.Mutable scratch) {
        if (eye == null || block == null || normalAxis == null || interior == null || interior.isEmpty()) {
            return false;
        }
        double plane = planeCoord + 0.5;
        double eyeN = eyeOn(eye, normalAxis);
        double blockN = coordOn(block, normalAxis) + 0.5;
        double denom = blockN - eyeN;
        if (Math.abs(denom) < 1.0e-6) {
            // Eye level with the block along the normal: the segment runs
            // parallel to the plane and never crosses it. Only reachable
            // with the eye already inside the slab, i.e. in the doorway.
            return true;
        }
        double t = (plane - eyeN) / denom;
        if (t <= 0.0) {
            // Crossing is behind the eye: the eye is at or past the plane.
            return true;
        }
        if (t > 1.0) {
            // Crossing is past the block, so the block is in FRONT of the
            // plane. viewerFarSide keeps the slab on the far side, so this
            // is unreachable in practice — masked rather than trusted.
            return false;
        }
        int x;
        int y;
        int z;
        if (normalAxis == Direction.Axis.X) {
            x = planeCoord;
            y = crossingOn(eye.y, block.getY(), t);
            z = crossingOn(eye.z, block.getZ(), t);
        } else if (normalAxis == Direction.Axis.Y) {
            x = crossingOn(eye.x, block.getX(), t);
            y = planeCoord;
            z = crossingOn(eye.z, block.getZ(), t);
        } else {
            x = crossingOn(eye.x, block.getX(), t);
            y = crossingOn(eye.y, block.getY(), t);
            z = planeCoord;
        }
        // A Mutable hashes and compares as its coordinates (Vec3i), so a
        // reused probe is a valid key for the interior set.
        return interior.contains(scratch != null ? scratch.set(x, y, z) : new BlockPos(x, y, z));
    }

    /** The block coordinate the segment is passing through at parameter {@code t}. */
    private static int crossingOn(double from, int toBlock, double t) {
        return (int) Math.floor(from + t * (toBlock + 0.5 - from));
    }

    /** One coordinate of an eye position, chosen by axis. */
    private static double eyeOn(Vec3d eye, Direction.Axis axis) {
        if (axis == Direction.Axis.X) {
            return eye.x;
        }
        if (axis == Direction.Axis.Y) {
            return eye.y;
        }
        return eye.z;
    }

    /** One coordinate of a position, chosen by axis. */
    public static int coordOn(BlockPos pos, Direction.Axis axis) {
        if (axis == Direction.Axis.X) {
            return pos.getX();
        }
        if (axis == Direction.Axis.Y) {
            return pos.getY();
        }
        return pos.getZ();
    }

    /**
     * The source -&gt; target transform for one zone: horizontal offsets, the
     * interior's floor Y (the row that lands on the arrival surface), and
     * the arrival column whose heightmap supplies that surface.
     */
    public record TargetMapping(int dx, int dz, int interiorMinY, int arrivalX, int arrivalZ) {
    }

    /**
     * Scaled (non-anchor) mapping. Mirrors {@code ServerWorldMixin}: the
     * interior's integer-averaged column is scaled and rounded, and the
     * difference becomes a flat horizontal offset. The int accumulate-then-
     * divide is reproduced exactly (it truncates towards zero rather than
     * flooring) so preview and arrival never disagree.
     */
    public static TargetMapping scaledMapping(Set<BlockPos> interior, double scale) {
        int centreX = 0;
        int centreZ = 0;
        for (BlockPos p : interior) {
            centreX += p.getX();
            centreZ += p.getZ();
        }
        int count = interior.size();
        if (count > 0) {
            centreX /= count;
            centreZ /= count;
        }
        int arrivalX = (int) Math.round((double) centreX * scale);
        int arrivalZ = (int) Math.round((double) centreZ * scale);
        return new TargetMapping(arrivalX - centreX, arrivalZ - centreZ,
                minOn(interior, Direction.Axis.Y), arrivalX, arrivalZ);
    }

    /**
     * Anchor mapping. Mirrors {@code ServerWorldMixin.teleportToAnchor},
     * which translates the interior's MIN corner onto the anchor position —
     * not its centre. Getting this wrong would preview terrain from a
     * different column than the one the player arrives in.
     */
    public static TargetMapping anchorMapping(Set<BlockPos> interior, int anchorX, int anchorZ) {
        int minX = minOn(interior, Direction.Axis.X);
        int minZ = minOn(interior, Direction.Axis.Z);
        return new TargetMapping(anchorX - minX, anchorZ - minZ,
                minOn(interior, Direction.Axis.Y), anchorX, anchorZ);
    }

    /**
     * Every target-world chunk column a projection of this zone can read
     * from: the slab footprint for BOTH possible sides (two players can
     * stand on opposite sides of one frame) plus the arrival column itself,
     * which feeds the surface heightmap and can sit just outside the slab.
     *
     * Chunk columns are Y-independent, so this needs no arrival height —
     * which is what lets the caller take a chunk ticket BEFORE it is able
     * to resolve the arrival surface. Bounded by config: at most
     * {@code (interiorSpan + 2*4)} x {@code (1 + 2*16)} blocks, i.e. a
     * handful of chunks.
     */
    public static List<ChunkPos> targetChunks(Set<BlockPos> interior, Direction.Axis portalAxis,
            TargetMapping mapping, int depth, int radius) {
        if (interior == null || interior.isEmpty() || mapping == null) {
            return List.of();
        }
        Direction.Axis normalAxis = normalAxis(portalAxis);
        int pad = Math.max(0, radius);
        int reach = Math.max(0, depth);
        int minX = minOn(interior, Direction.Axis.X);
        int maxX = maxOn(interior, Direction.Axis.X);
        int minZ = minOn(interior, Direction.Axis.Z);
        int maxZ = maxOn(interior, Direction.Axis.Z);

        // In-plane horizontal axes get the radius pad; the normal axis (when
        // horizontal) extends the full depth BOTH ways. A Y-normal portal
        // has no horizontal reach at all — both axes are in-plane.
        if (normalAxis == Direction.Axis.X) {
            minX -= reach;
            maxX += reach;
        } else {
            minX -= pad;
            maxX += pad;
        }
        if (normalAxis == Direction.Axis.Z) {
            minZ -= reach;
            maxZ += reach;
        } else {
            minZ -= pad;
            maxZ += pad;
        }

        int targetMinX = Math.min(minX + mapping.dx(), mapping.arrivalX());
        int targetMaxX = Math.max(maxX + mapping.dx(), mapping.arrivalX());
        int targetMinZ = Math.min(minZ + mapping.dz(), mapping.arrivalZ());
        int targetMaxZ = Math.max(maxZ + mapping.dz(), mapping.arrivalZ());

        List<ChunkPos> out = new ArrayList<>();
        for (int cx = targetMinX >> 4; cx <= targetMaxX >> 4; cx++) {
            for (int cz = targetMinZ >> 4; cz <= targetMaxZ >> 4; cz++) {
                out.add(new ChunkPos(cx, cz));
            }
        }
        return out;
    }

    /** Where a source position samples from in the target dimension. */
    public static BlockPos toTarget(BlockPos sourcePos, TargetMapping mapping, int arrivalY) {
        return new BlockPos(
                sourcePos.getX() + mapping.dx(),
                arrivalY + (sourcePos.getY() - mapping.interiorMinY()),
                sourcePos.getZ() + mapping.dz());
    }

    private static int minOn(Set<BlockPos> positions, Direction.Axis axis) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : positions) {
            min = Math.min(min, coordOn(p, axis));
        }
        return min;
    }

    private static int maxOn(Set<BlockPos> positions, Direction.Axis axis) {
        int max = Integer.MIN_VALUE;
        for (BlockPos p : positions) {
            max = Math.max(max, coordOn(p, axis));
        }
        return max;
    }
}
