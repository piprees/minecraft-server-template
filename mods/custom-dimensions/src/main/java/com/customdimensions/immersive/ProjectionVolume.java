package com.customdimensions.immersive;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure geometry for the immersive portal preview (Phase 1): which source
 * positions make up the projection slab behind a portal, and where each of
 * them samples from in the target dimension.
 *
 * Deliberately registry-, world- and server-free: every method takes plain
 * values (positions, axis, ints, scale) and returns plain values, so the
 * geometry is unit-testable without a live world.
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
     * The rectangular slab of SOURCE-world positions to overwrite with
     * target-world blocks: the interior's in-plane bounding box padded by
     * {@code radius} on both in-plane axes, extended {@code depth} blocks
     * along {@code normal}.
     *
     * The slab starts ONE block past the portal plane, never on it — the
     * doorway and its frame ring keep their real blocks, so the frame stays
     * visible and the interior stays walkable-looking.
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
