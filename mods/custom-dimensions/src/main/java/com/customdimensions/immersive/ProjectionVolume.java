package com.customdimensions.immersive;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure geometry for the immersive portal preview: which source positions
 * make up the projection slab behind a portal, where each of them samples
 * from in the target dimension, and which of them a given viewer can
 * actually see through the opening.
 *
 * <p>Deliberately registry-, world- and server-free: every method takes
 * plain values and returns plain values, so the geometry is unit-testable
 * without a live world.
 *
 * <p>The slab is a CANDIDATE set; {@link #seesThroughOpening} decides what a
 * player is actually sent — the slab is a property of the zone, computed
 * once, while visibility is a property of the viewer and changes every time
 * they move.
 *
 * <p>The mappings MIRROR the real teleport transforms exactly — {@code
 * ServerWorldMixin}'s outbound one (integer truncation, average column for
 * scaled portals, MIN corner for anchor portals) and {@code
 * EntityTickPortalMixin}'s return one. A preview that disagrees with where
 * the player actually lands is worse than no preview, so any change to
 * either teleport path must be made here in the same commit.
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
     * <p>The slab starts ONE block past the portal plane, never on it, so the
     * doorway and its frame ring keep their real blocks.
     *
     * <p><b>This is a CANDIDATE set, not the projection.</b> The padded
     * columns sit behind the frame WALL, not behind the opening; what a
     * player actually receives is this set filtered by {@link
     * #seesThroughOpening}, which is per-player and changes as they move.
     * {@code radius} only bounds how far the visible cone can widen behind
     * the opening — it does not decide what is shown.
     *
     * <p>Returned in a stable, spatially coherent order (the delta pass and
     * the unit tests both rely on it being deterministic).
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
     * <p>Must agree with {@link #computeSourcePositions}, which starts the
     * slab one block past the plane — hence max+1 / min-1 here, the same
     * arithmetic.
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
     * Widest cell span the containment test will walk on one in-plane axis
     * before giving up and calling the block hidden.
     *
     * A block's shadow on the plane is normally under one cell across
     * (perspective shrinks anything behind the plane), and reaches two or
     * three only for a block far off-axis seen from right against the frame.
     * Anything wider cannot fit inside an aperture worth projecting through,
     * so the cap costs nothing real and bounds the loop against a pathological
     * eye position from a tick path.
     */
    private static final int MAX_SHADOW_CELLS = 8;

    /**
     * A block's shadow that lands exactly on a cell boundary has not entered
     * the next cell. Without this the common case of a perfectly centred
     * viewer — whose shadows land on integers — would demand a cell beyond
     * the frame edge and hide blocks that are precisely inside it.
     */
    private static final double EDGE_EPSILON = 1.0e-6;

    /**
     * Can this viewer see ANY PART of {@code block} through the portal
     * opening, without any part of it also being visible AROUND the frame?
     *
     * <h2>Why this exists</h2>
     * {@link #computeSourcePositions} is a rectangular slab, most of which
     * sits behind the frame WALL rather than behind the doorway. Sending it
     * unconditionally would replace real blocks beside and above the frame
     * with destination terrain for anyone merely looking in the portal's
     * general direction. A portal is a hole: you can only see through it
     * along a line that goes through it.
     *
     * <h2>The test</h2>
     * The block's shadow — the perspective projection of its full cube from
     * {@code eye} onto the portal's mid-plane — is walked cell by cell. The
     * block is shown when the shadow <b>touches the aperture at least once</b>
     * and <b>never leaves {@code interior} ∪ {@code frameRing}</b>. Cells are
     * looked up in those sets themselves, never a bounding box, so an
     * irregular flood-filled frame masks per cell, like {@code
     * EntityPassthrough}'s swept-path test.
     *
     * <p>The ring is what makes this correct where a centre-ray test (leaks
     * geometry at grazing angles) and a whole-shadow-inside-aperture test
     * (hides blocks genuinely visible through the opening) are not: the parts
     * of a partially-visible block that do NOT come through the aperture are
     * behind the frame ring, real solid geometry the client draws in front of
     * them — occluded, not leaked. A shadow that runs past the ring is the
     * actual leak, where the rays miss the frame and reach the block in open
     * air.
     *
     * <p>The occluder set is whatever actually blocks sight in the portal's
     * plane (see {@link #occluders}), not {@link #frameRing} alone: geometry
     * misses a frame's diagonal CORNER blocks and undercounts a portal set
     * into a wider WALL.
     *
     * <h2>How the shadow is computed</h2>
     * Only the block's two faces on the normal axis matter for the crossing
     * parameter, giving two values of {@code t}; each in-plane axis then has
     * two candidate coordinates. The four combinations per axis bound the
     * shadow, and its axis-aligned bounding rectangle is what gets walked —
     * a superset of the true (hexagonal) shadow, so the ring bound errs
     * towards hiding and the aperture touch errs towards showing.
     *
     * <h2>Boundary behaviour</h2>
     * A block is in or out as a whole, so geometry visibly snaps as a player
     * walks. That is deliberate: hysteresis or fading would reintroduce
     * stuck fake blocks. Only a client-side renderer can clip sub-block
     * accurately.
     *
     * <h2>The plane band</h2>
     * {@link #viewerFarSide} only flips the slab on a BLOCK boundary, so
     * there is a one-block band on the normal axis where the eye can be past
     * the plane's midpoint while the slab still sits on the far side.
     * Treating that whole band as "the eye is in the doorway" would show the
     * entire padded slab to a player walking sideways past the portal who
     * cannot see the opening at all, because the frame wall is between them
     * and it. The shortcut instead asks whether the eye is inside the
     * APERTURE, not merely level with its plane.
     *
     * <h2>Eye POSITION, never camera angle</h2>
     * This takes a {@link Vec3d} position and nothing else, and the result
     * must never depend on where the player is looking: what is
     * geometrically visible through a hole is a function of where your eye
     * IS. Keying off camera angle would make real blocks pop in and out
     * every time a player turned around. The projection therefore legitimately
     * changes as a player WALKS and does not as they LOOK — if that asymmetry
     * is ever reported as a bug, the bug is something else keyed to this mask
     * that should not be.
     *
     * @param occluders in-plane positions that block sight ({@link #occluders},
     *                  or {@link #frameRing} for the geometry-only fallback);
     *                  an empty or null set degrades to the strict
     *                  whole-shadow-inside-the-aperture rule
     */
    public static boolean seesThroughOpening(Vec3d eye, BlockPos block, Direction.Axis normalAxis,
            int planeCoord, Set<BlockPos> interior, Set<BlockPos> occluders, BlockPos.Mutable scratch) {
        if (eye == null || block == null || normalAxis == null || interior == null || interior.isEmpty()) {
            return false;
        }
        double plane = planeCoord + 0.5;
        double eyeN = eyeOn(eye, normalAxis);
        int blockN = coordOn(block, normalAxis);
        double toPlane = plane - eyeN;
        // Which side of the plane the slab is on, from this block.
        double blockSide = (blockN + 0.5) - plane;
        if (toPlane * blockSide <= 0.0) {
            // The eye is level with, or past, the plane. Standing IN the
            // doorway there is nothing left to mask; standing beside it there
            // is nothing to see. Only the aperture test can tell those apart
            // — see "The plane band" above.
            return eyeInsideAperture(eye, normalAxis, planeCoord, interior, scratch);
        }

        // The block's two faces on the normal axis give the two crossing
        // parameters that bound its shadow on the plane.
        double tLow = toPlane / (blockN - eyeN);
        double tHigh = toPlane / ((blockN + 1.0) - eyeN);
        return shadowFitsOpening(eye, block, normalAxis, planeCoord, tLow, tHigh,
                interior, occluders, scratch);
    }

    /**
     * Does the block's shadow, taken over the crossing parameters
     * {@code t0..t1} and the block's full extent on each in-plane axis, touch
     * the opening at least once and stay within the opening plus its ring?
     */
    private static boolean shadowFitsOpening(Vec3d eye, BlockPos block, Direction.Axis normalAxis,
            int planeCoord, double t0, double t1, Set<BlockPos> interior, Set<BlockPos> occluders,
            BlockPos.Mutable scratch) {
        double uEye;
        double vEye;
        int uBlock;
        int vBlock;
        if (normalAxis == Direction.Axis.X) {
            uEye = eye.y;
            vEye = eye.z;
            uBlock = block.getY();
            vBlock = block.getZ();
        } else if (normalAxis == Direction.Axis.Y) {
            uEye = eye.x;
            vEye = eye.z;
            uBlock = block.getX();
            vBlock = block.getZ();
        } else {
            uEye = eye.x;
            vEye = eye.y;
            uBlock = block.getX();
            vBlock = block.getY();
        }

        long uSpan = shadowSpan(uEye, uBlock, t0, t1);
        long vSpan = shadowSpan(vEye, vBlock, t0, t1);
        int uMin = (int) (uSpan >> 32);
        int uMax = (int) uSpan;
        int vMin = (int) (vSpan >> 32);
        int vMax = (int) vSpan;
        if (uMax - uMin >= MAX_SHADOW_CELLS || vMax - vMin >= MAX_SHADOW_CELLS) {
            // A shadow this wide cannot fit inside any aperture worth
            // projecting through; refusing it also bounds the loop below.
            return false;
        }

        boolean touchesOpening = false;
        for (int u = uMin; u <= uMax; u++) {
            for (int v = vMin; v <= vMax; v++) {
                int x;
                int y;
                int z;
                if (normalAxis == Direction.Axis.X) {
                    x = planeCoord;
                    y = u;
                    z = v;
                } else if (normalAxis == Direction.Axis.Y) {
                    x = u;
                    y = planeCoord;
                    z = v;
                } else {
                    x = u;
                    y = v;
                    z = planeCoord;
                }
                // A Mutable hashes and compares as its coordinates (Vec3i), so
                // a reused probe is a valid key for either set.
                BlockPos probe = scratch != null ? scratch.set(x, y, z) : new BlockPos(x, y, z);
                if (interior.contains(probe)) {
                    touchesOpening = true;
                    continue;
                }
                if (occluders == null || !occluders.contains(probe)) {
                    // Past the frame: these rays reach the block in open air,
                    // which is the leak the whole mask exists to prevent.
                    return false;
                }
            }
        }
        return touchesOpening;
    }

    /**
     * Is the eye's own cell, projected onto the portal plane, part of the
     * opening? The test that distinguishes "standing in the doorway" from
     * "standing beside it at the same depth" — see the plane-band section on
     * {@link #seesThroughOpening}.
     */
    private static boolean eyeInsideAperture(Vec3d eye, Direction.Axis normalAxis, int planeCoord,
            Set<BlockPos> interior, BlockPos.Mutable scratch) {
        int x;
        int y;
        int z;
        if (normalAxis == Direction.Axis.X) {
            x = planeCoord;
            y = (int) Math.floor(eye.y);
            z = (int) Math.floor(eye.z);
        } else if (normalAxis == Direction.Axis.Y) {
            x = (int) Math.floor(eye.x);
            y = planeCoord;
            z = (int) Math.floor(eye.z);
        } else {
            x = (int) Math.floor(eye.x);
            y = (int) Math.floor(eye.y);
            z = planeCoord;
        }
        return interior.contains(scratch != null ? scratch.set(x, y, z) : new BlockPos(x, y, z));
    }

    /**
     * The in-plane positions around an opening that actually block sight,
     * asked of the world rather than assumed from the frame's shape.
     *
     * <p>Walks the aperture's in-plane bounding box grown by {@code margin},
     * skips the aperture itself, and keeps every cell {@code isOccluding}
     * accepts. The caller supplies that predicate so this class stays
     * world-free; in the projector it is "the real block here is an opaque
     * full cube, on a loaded chunk".
     *
     * <p><b>Why not {@link #frameRing}.</b> Geometry alone gets this wrong in
     * both directions: a ring built by offsetting each aperture cell along
     * the four in-plane directions never reaches a frame's diagonal CORNER
     * blocks, and it stops at one cell while most portals worth looking
     * through are set into a WALL that occludes far more. Both are the same
     * mistake of describing the occluder instead of measuring it.
     *
     * <p>Anything the predicate declines — unloaded chunk, glass, air — is
     * treated as see-through, so the mask stays conservative exactly where
     * the evidence is missing.
     */
    public static Set<BlockPos> occluders(Set<BlockPos> interior, Direction.Axis portalAxis,
            int margin, Predicate<BlockPos> isOccluding) {
        if (interior == null || interior.isEmpty() || isOccluding == null) {
            return Set.of();
        }
        Direction.Axis normal = normalAxis(portalAxis);
        int planeCoord = minOn(interior, normal);
        Direction.Axis uAxis = normal == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis vAxis = normal == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        int pad = Math.max(0, margin);
        int uMin = minOn(interior, uAxis) - pad;
        int uMax = maxOn(interior, uAxis) + pad;
        int vMin = minOn(interior, vAxis) - pad;
        int vMax = maxOn(interior, vAxis) + pad;

        Set<BlockPos> out = new HashSet<>();
        for (int u = uMin; u <= uMax; u++) {
            for (int v = vMin; v <= vMax; v++) {
                BlockPos pos = fromPlane(normal, planeCoord, u, v);
                if (interior.contains(pos)) {
                    continue;
                }
                if (isOccluding.test(pos)) {
                    out.add(pos);
                }
            }
        }
        return out;
    }

    /** Rebuild a plane position from its normal coordinate and (u, v) pair. */
    private static BlockPos fromPlane(Direction.Axis normal, int planeCoord, int u, int v) {
        if (normal == Direction.Axis.X) {
            return new BlockPos(planeCoord, u, v);
        }
        if (normal == Direction.Axis.Y) {
            return new BlockPos(u, planeCoord, v);
        }
        return new BlockPos(u, v, planeCoord);
    }

    /**
     * The opening's in-plane ring: every position adjacent to the aperture
     * within the portal's plane that is not itself part of the aperture.
     *
     * <p>For a valid zone these are exactly the frame blocks — zone validity
     * is defined as this ring being made of frame material — which is what
     * makes it usable as an occluder set by {@link #seesThroughOpening}
     * without reading a single block state.
     *
     * <p>Derived from the normal axis rather than from
     * {@code PortalHelper.planeDirections} so this class stays world- and
     * mod-free; the two produce the same four directions by construction.
     */
    public static Set<BlockPos> frameRing(Set<BlockPos> interior, Direction.Axis portalAxis) {
        if (interior == null || interior.isEmpty()) {
            return Set.of();
        }
        Direction.Axis normal = normalAxis(portalAxis);
        Set<BlockPos> ring = new HashSet<>();
        for (BlockPos p : interior) {
            for (Direction dir : Direction.values()) {
                if (dir.getAxis() == normal) {
                    continue;
                }
                BlockPos neighbour = p.offset(dir);
                if (!interior.contains(neighbour)) {
                    ring.add(neighbour);
                }
            }
        }
        return ring;
    }

    /**
     * The inclusive cell range one in-plane axis of the block's shadow covers,
     * packed as {@code (min &lt;&lt; 32) | max} so the caller pays no
     * allocation per position.
     *
     * The extremes are found over the four combinations of the block's two
     * face coordinates on this axis with the two crossing parameters — the
     * axis-aligned bound of the shadow. {@link #EDGE_EPSILON} keeps a shadow
     * that ends exactly on a cell boundary from claiming the next cell, which
     * matters because a perfectly centred viewer's shadows land on integers.
     */
    private static long shadowSpan(double eyeU, int blockU, double t0, double t1) {
        double lo = blockU;
        double hi = blockU + 1.0;
        double a = eyeU + t0 * (lo - eyeU);
        double b = eyeU + t0 * (hi - eyeU);
        double c = eyeU + t1 * (lo - eyeU);
        double d = eyeU + t1 * (hi - eyeU);
        double min = Math.min(Math.min(a, b), Math.min(c, d));
        double max = Math.max(Math.max(a, b), Math.max(c, d));
        int cellMin = (int) Math.floor(min);
        int cellMax = (int) Math.floor(max - EDGE_EPSILON);
        if (cellMax < cellMin) {
            cellMax = cellMin;
        }
        return ((long) cellMin << 32) | (cellMax & 0xFFFFFFFFL);
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
     * The cells a body occupies, padded by {@code pad} in every direction. A
     * fake block must NEVER be painted into one of these.
     *
     * <p>Client-side collision against a fake block is inherent to the
     * server-side approach: the client believes the block is real and won't
     * let the player walk through it, while the server knows there is
     * nothing there to mine — an unmineable wall that only they can see.
     *
     * <p>Padding of 1 covers the step a player takes between refresh passes
     * (4 ticks by default); suppressing only the exact occupied cells would
     * still paint a block into the space they are walking into.
     *
     * <p>Pure over plain doubles: no entity, no world, no MC runtime.
     */
    public static Set<BlockPos> occupiedCells(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, int pad) {
        Set<BlockPos> out = new HashSet<>();
        int x0 = (int) Math.floor(minX) - pad;
        int y0 = (int) Math.floor(minY) - pad;
        int z0 = (int) Math.floor(minZ) - pad;
        // Ceil-minus-one so a box ending exactly on a boundary does not claim
        // the next cell along, which would widen every body by one for free.
        int x1 = (int) Math.ceil(maxX) - 1 + pad;
        int y1 = (int) Math.ceil(maxY) - 1 + pad;
        int z1 = (int) Math.ceil(maxZ) - 1 + pad;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
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
        // DIVIDE on entry: `scale` means "one block walked in the
        // DESTINATION is worth `scale` blocks at home" (Nether-style "8:1").
        // Going IN divides, coming OUT multiplies — e.g. a portal at
        // overworld 1888 arrives at 1888/8 = 236 in a scale-8 dimension.
        // Multiplying here would place arrivals outside the destination's
        // own world border, where vanilla forbids breaking or placing any
        // block, since every dimension border is authored as
        // overworldBorder / scale.
        int arrivalX = (int) Math.round(centreX / scale);
        int arrivalZ = (int) Math.round(centreZ / scale);
        return new TargetMapping(arrivalX - centreX, arrivalZ - centreZ,
                minOn(interior, Direction.Axis.Y), arrivalX, arrivalZ);
    }

    /**
     * RETURN mapping, for projecting an ARRIVAL portal's far side — the world
     * you would go back to.
     *
     * <p>{@code EntityTickPortalMixin.onTickPortal} resolves a player's
     * return in this order: configured exit modes ({@code bed} /
     * {@code worldSpawn} / {@code dim!…}), then the player's own tracked
     * ORIGIN, then the portal's registered fallback — {@code
     * target.sourceWorld} at {@code target.sourceY}, keeping the X/Z of the
     * portal block they stood in. This mirrors the LAST of those: a
     * translation-free horizontal map with the aperture's floor row landing
     * on {@code sourceY}.
     *
     * <p>The fallback is the right one to preview even though the origin
     * usually wins at teleport time: a projection is shared between every
     * viewer, so keying it to one player's travel history would make the
     * preview depend on who happened to look first, and at scale 1 the
     * fallback and origin columns are the same place anyway (they diverge
     * only for scaled portals, where the fallback is also what a player who
     * has lost their origin actually gets).
     *
     * <p>Callers must not use this for an arrival carrying an {@code
     * exitMode}: {@code bed} is per-player, and {@code worldSpawn}/{@code
     * dim!…} land somewhere that is not this portal's column at all.
     */
    public static TargetMapping returnMapping(Set<BlockPos> aperture) {
        return returnMapping(aperture, null, null);
    }

    /**
     * RETURN mapping — a rigid TRANSLATION to the source portal's column,
     * exactly like {@link #scaledMapping} in the other direction.
     *
     * <p>A preview is never scaled: N blocks out from a portal is N blocks on
     * the other side, both ways. The only question is where to translate TO
     * — the source portal's column when it is known, or a translation-free
     * fallback when it is not. Translating by zero is only correct at scale
     * 1, where the arrival column and the source column coincide; at any
     * other scale it samples the wrong place entirely. Legacy records that
     * predate the persisted {@code sourceX}/{@code sourceZ} carry no column,
     * so they fall back to the translation-free behaviour rather than
     * guessing.
     */
    public static TargetMapping returnMapping(Set<BlockPos> aperture, Integer sourceX, Integer sourceZ) {
        if (aperture == null || aperture.isEmpty()) {
            return new TargetMapping(0, 0, 0, 0, 0);
        }
        // Same integer accumulate-then-divide idiom as scaledMapping, so the
        // two directions pick their representative column identically.
        int centreX = 0;
        int centreZ = 0;
        for (BlockPos p : aperture) {
            centreX += p.getX();
            centreZ += p.getZ();
        }
        int count = aperture.size();
        centreX /= count;
        centreZ /= count;
        if (sourceX == null || sourceZ == null) {
            // Pre-column record: translate by zero, as before.
            return new TargetMapping(0, 0, minOn(aperture, Direction.Axis.Y), centreX, centreZ);
        }
        return new TargetMapping(sourceX - centreX, sourceZ - centreZ,
                minOn(aperture, Direction.Axis.Y), sourceX, sourceZ);
    }

    /**
     * The set of portal blocks making up one arrival portal's aperture, grown
     * from {@code seed} through {@code planeDirs} for as long as
     * {@code isPortalPosition} keeps saying yes.
     *
     * <p>The caller passes {@code PortalHelper::isRegisteredPortalPosition},
     * a pure in-memory map read, rather than {@code
     * PortalHelper.collectPortalArea}, which flood-fills over real BLOCK
     * STATES and can walk out of the loaded region — nothing in the
     * projector may load a chunk (Rule 1). The trade-off is that the
     * registry can be stale, since a destroyed portal is never
     * de-registered, so the caller separately checks that the seed still
     * carries a portal block on a loaded chunk.
     *
     * <p>Bounded by {@code limit}, mirroring {@code PortalHelper}'s {@code
     * MAX_PORTAL_BLOCKS}: a corrupted registry must not be able to walk this
     * into a long loop from a tick path.
     */
    public static Set<BlockPos> collectAperture(BlockPos seed, Direction[] planeDirs,
            Predicate<BlockPos> isPortalPosition, int limit) {
        if (seed == null || planeDirs == null || isPortalPosition == null || limit <= 0
                || !isPortalPosition.test(seed)) {
            return Set.of();
        }
        Set<BlockPos> found = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        found.add(seed);
        queue.add(seed);
        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos pos = queue.poll();
            for (Direction dir : planeDirs) {
                BlockPos next = pos.offset(dir);
                if (found.contains(next) || !isPortalPosition.test(next)) {
                    continue;
                }
                found.add(next);
                queue.add(next);
                if (found.size() >= limit) {
                    break;
                }
            }
        }
        return found;
    }

    /**
     * The lexicographic-minimum position of a set, by (x, y, z) — a canonical
     * identity for an aperture that does not depend on which of its blocks was
     * used to discover it.
     *
     * Two source portals sharing one arrival (anchor dimensions, or two
     * sources close enough to reuse a portal) seed the same aperture from
     * different blocks; keyed on this they collapse to one projection instead
     * of two racing ones.
     */
    public static BlockPos minCorner(Set<BlockPos> positions) {
        BlockPos best = null;
        for (BlockPos p : positions) {
            if (best == null
                    || p.getX() < best.getX()
                    || (p.getX() == best.getX() && p.getY() < best.getY())
                    || (p.getX() == best.getX() && p.getY() == best.getY() && p.getZ() < best.getZ())) {
                best = p;
            }
        }
        return best;
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
