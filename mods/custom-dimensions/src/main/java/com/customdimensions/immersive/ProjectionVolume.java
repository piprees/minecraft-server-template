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
 * The mappings MIRROR the real teleport transforms exactly — {@code
 * ServerWorldMixin}'s outbound one (including its integer truncation, and its
 * use of the interior's average column for scaled portals but its MIN corner
 * for anchor portals) and {@code EntityTickPortalMixin}'s return one for the
 * arrival-side projection. A preview that disagrees with where the player
 * actually lands is worse than no preview, so any change to either teleport
 * path must be made here in the same commit.
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
     * 4a needs to name that layer without walking the volume, to gate its
     * {@link #lightPositions} lookup on a cheap int compare. It must agree
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
     * {@link #computeSourcePositions} is a rectangular slab, so most of it
     * sits behind the frame WALL rather than behind the doorway. Sending all
     * of it replaces real blocks beside and above the frame with destination
     * terrain — reported in-game as "the server is rendering stuff when I
     * just look in the general direction of the portal". A portal is a hole,
     * and you can only see through a hole along a line that goes through it.
     *
     * <h2>The test, and why it is neither of the two obvious ones</h2>
     * The block's shadow — the perspective projection of its full cube from
     * {@code eye} onto the portal's mid-plane — is walked cell by cell. The
     * block is shown when the shadow <b>touches the aperture at least once</b>
     * and <b>never leaves {@code interior} ∪ {@code frameRing}</b>. Cells are
     * looked up in those sets themselves, never in a bounding box, so an
     * irregular flood-filled frame (an L, an arch, a frame with a notch) masks
     * per cell, exactly like {@code EntityPassthrough}'s swept-path test.
     *
     * <p>Both simpler rules were tried in game and both were reported:
     * <ul>
     *   <li><b>Centre only</b> — a block whose centre-ray clears the aperture
     *       still renders as a whole cube, so at grazing angles its outer half
     *       hangs past the frame edge: "i'm side-on to the portal, it shouldn't
     *       have those leaves there".</li>
     *   <li><b>Whole shadow inside the aperture</b> — correct but far too
     *       conservative, because a block only fractionally behind the opening
     *       is genuinely visible through it: "any block that is even slightly
     *       in-frame should be rendered rather than only blocks that are fully
     *       in frame".</li>
     * </ul>
     *
     * <p>The ring is what reconciles them, and the reason is physical rather
     * than a fudge: the parts of a partially-visible block that do NOT come
     * through the aperture are behind the frame ring, which is real, solid,
     * opaque geometry the client draws in front of them. They are occluded,
     * not leaked. A shadow that runs past the ring is the case that actually
     * leaks — those rays miss the frame altogether and reach the block in open
     * air — and that is exactly the rule's cutoff.
     *
     * <p>The ring is one cell thick, matching the frame. A portal built into a
     * wider WALL occludes more than that, so this stays slightly conservative
     * for those; correcting it would mean probing real block states in the
     * plane, which is a world read this class deliberately does not do.
     *
     * <h2>How the shadow is computed</h2>
     * Only the block's two faces ON THE NORMAL AXIS matter for the crossing
     * parameter, giving two values of {@code t}; each in-plane axis then has
     * two candidate coordinates. The four combinations per axis bound the
     * shadow, and its axis-aligned bounding rectangle is what gets walked.
     * That rectangle is a superset of the true (hexagonal) shadow, so the
     * ring bound errs towards hiding and the aperture touch errs towards
     * showing — each in the direction that costs least if it is wrong.
     *
     * <h2>Cost</h2>
     * Two divisions, eight multiply-adds and typically one to six {@code Set}
     * lookups per position, in a single walk that rejects on the first cell
     * outside the ring. (The old centre pre-test is gone: it was a valid cheap
     * reject only while full containment was required, and under this rule a
     * block whose centre misses can still be visible.) No allocation when the
     * caller passes a {@code scratch} it reuses across the volume;
     * {@code scratch} may be null (tests, one-off calls), which costs one
     * {@link BlockPos} per lookup.
     *
     * <h2>Boundary behaviour</h2>
     * A block is still in or out as a whole — that granularity is inherent, and
     * it is why geometry visibly snaps as a player walks. That snapping is NOT
     * a defect to be smoothed: hysteresis or fading would reintroduce the
     * stuck-fake-block class this feature spent three rounds eliminating. Only
     * a client-side renderer can clip sub-block accurately; see
     * {@code immersive/PHASE-5-CLIENT-COMPANION.md}.
     *
     * <h2>The plane band, and the inversion that lived in it</h2>
     * {@link #viewerFarSide} only flips the slab when the viewer's BLOCK
     * coordinate passes the plane, so there is a one-block band on the normal
     * axis inside which the eye can already be past the plane's midpoint while
     * the slab still sits on the far side. This method used to treat that
     * whole band as "the eye is in the doorway, nothing left to mask" and
     * return true for every position in it.
     *
     * <p>That band is infinite in the in-plane axes. Walking sideways PAST a
     * portal crosses it, and the entire slab — padding included — appeared at
     * once, for a player who could not see the opening at all because the
     * frame wall was between them and it. Reported in game 2026-07-25 with the
     * exact signature: "I cannot see the portal from my direction at all
     * because the planks are in the way, but it suddenly pops; one more step
     * forward and it goes away", symmetric on both sides. It is also where the
     * fake blocks a player could walk into and mine came from: those are the
     * padded columns, which nothing but this branch ever showed.
     *
     * <p>So the shortcut now asks the question it always meant to ask — is the
     * eye inside the APERTURE, not merely level with its plane — and answers
     * false when it is not. A player in the doorway still keeps their preview
     * through the last half-block before a traversal, which is what the
     * shortcut is for.
     *
     * <h2>Eye POSITION, never camera angle — do not "fix" this</h2>
     * This takes a {@link Vec3d} position and nothing else. It must never
     * grow a yaw/pitch parameter, and the result must never depend on where
     * the player is looking. What is geometrically visible through a hole is
     * a function of where your eye IS, not of which way it is pointing: turn
     * your head and the same blocks are still on the far side of the same
     * opening. Keying off camera angle would make real blocks pop in and out
     * every time a player turned around, and would be a far worse artefact
     * than the one this method fixes.
     *
     * Consequently the projection legitimately changes as a player WALKS and
     * legitimately does not as they LOOK. If that asymmetry is ever reported
     * as a bug, the bug is something else keyed to this mask that should not
     * be — as {@code PlayerProjectionState}'s 4a light layer once was.
     *
     * @param frameRing the opening's in-plane ring ({@link #frameRing}); an
     *                  empty or null set degrades to the old strict
     *                  whole-shadow-inside-the-aperture rule
     */
    public static boolean seesThroughOpening(Vec3d eye, BlockPos block, Direction.Axis normalAxis,
            int planeCoord, Set<BlockPos> interior, Set<BlockPos> frameRing, BlockPos.Mutable scratch) {
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
                interior, frameRing, scratch);
    }

    /**
     * Does the block's shadow, taken over the crossing parameters
     * {@code t0..t1} and the block's full extent on each in-plane axis, touch
     * the opening at least once and stay within the opening plus its ring?
     */
    private static boolean shadowFitsOpening(Vec3d eye, BlockPos block, Direction.Axis normalAxis,
            int planeCoord, double t0, double t1, Set<BlockPos> interior, Set<BlockPos> frameRing,
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
                if (frameRing == null || !frameRing.contains(probe)) {
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

    /**
     * The 4a light layer: the OPENING's own cross-section extruded one block
     * along {@code normal} — the positions directly behind the doorway, with
     * no {@code previewRadius} padding.
     *
     * <h2>Why this is not just the first slab layer</h2>
     * 4a's invisible {@code Blocks.LIGHT} substitution used to be applied
     * inside the mask-filtered send loop, which made the set of level-15
     * light sources a function of where the player stood. Every step changed
     * which first-layer positions passed {@link #seesThroughOpening}, adding
     * and removing light sources, and the client relit the area each time —
     * reported in-game as "when I WALK around the portal the level of light
     * coming out of it changes a lot, but when I stand still and LOOK around
     * it doesn't". The mask was right; putting the lights behind it was not.
     *
     * So the light layer is derived from the zone alone. It is the same set
     * for every viewer and every position they can stand in, which is what
     * makes the lighting stable.
     *
     * Two properties make it the right set:
     * <ul>
     *   <li><b>It is per-cell, not a bounding box.</b> An irregular opening
     *       gets an irregular light layer, so an arch does not light the
     *       solid corners it does not have.</li>
     *   <li><b>It has no radius padding.</b> The padded positions sit behind
     *       the frame WALL; a light source there spills through real
     *       geometry around the frame, which is precisely the glow that was
     *       reported. Restricting to the aperture cuts the default doorway
     *       from 42 sources to 6 and aims what is left through the hole.</li>
     * </ul>
     *
     * Every returned position lies on {@link #firstLayerCoord} and inside
     * {@link #computeSourcePositions} for any depth &gt;= 1 and radius &gt;= 0,
     * so the caller can send these without a second bookkeeping path.
     */
    public static Set<BlockPos> lightPositions(Set<BlockPos> interior, Direction normal) {
        if (interior == null || interior.isEmpty() || normal == null) {
            return Set.of();
        }
        Set<BlockPos> out = new HashSet<>(interior.size() * 2);
        for (BlockPos p : interior) {
            out.add(p.offset(normal));
        }
        return out;
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
     * RETURN mapping, for projecting an ARRIVAL portal's far side — the world
     * you would go back to.
     *
     * <h2>Which of the return paths this mirrors, and why</h2>
     * {@code EntityTickPortalMixin.onTickPortal} resolves a player's return in
     * this order: configured exit modes ({@code bed} / {@code worldSpawn} /
     * {@code dim!…}), then the player's own tracked ORIGIN, then the portal's
     * registered fallback — {@code target.sourceWorld} at {@code
     * target.sourceY}, keeping the X/Z of the portal block they were standing
     * in. This mirrors the LAST of those: a translation-free horizontal map
     * ({@code dx = dz = 0}) with the aperture's floor row landing on
     * {@code sourceY}, which the caller supplies as the {@code arrivalY}
     * argument to {@link #toTarget}.
     *
     * The fallback is the right one to preview even though the origin usually
     * wins at teleport time, for two reasons:
     * <ul>
     *   <li><b>A projection is shared.</b> Everyone who can see through this
     *       portal sees the same fake blocks. Keying it to one player's travel
     *       history would make the preview depend on who happened to look
     *       first — worse than being slightly wrong.</li>
     *   <li><b>At scale 1 they agree.</b> The origin is the source portal
     *       block the player stepped into, and {@code sourceY} is that same
     *       block's Y ({@code createTargetPortal} is handed {@code
     *       pos.getY()}); the arrival is built at the SCALED source column, so
     *       for an unscaled portal the fallback column and the origin column
     *       are the same place. They diverge only for scaled portals, where
     *       the fallback is also what any player who has lost their origin
     *       (a restart) actually gets.</li>
     * </ul>
     *
     * Callers must not use this for an arrival carrying an {@code exitMode}:
     * {@code bed} is per-player, and {@code worldSpawn}/{@code dim!…} land
     * somewhere that is not this portal's column at all.
     */
    public static TargetMapping returnMapping(Set<BlockPos> aperture) {
        if (aperture == null || aperture.isEmpty()) {
            return new TargetMapping(0, 0, 0, 0, 0);
        }
        // Same integer accumulate-then-divide idiom as scaledMapping. Only
        // picks the representative column for the chunk ticket — the mapping
        // itself is horizontal identity, so truncation cannot misplace a block.
        int centreX = 0;
        int centreZ = 0;
        for (BlockPos p : aperture) {
            centreX += p.getX();
            centreZ += p.getZ();
        }
        int count = aperture.size();
        centreX /= count;
        centreZ /= count;
        return new TargetMapping(0, 0, minOn(aperture, Direction.Axis.Y), centreX, centreZ);
    }

    /**
     * The set of portal blocks making up one arrival portal's aperture, grown
     * from {@code seed} through {@code planeDirs} for as long as
     * {@code isPortalPosition} keeps saying yes.
     *
     * <h2>Why a predicate and not a world</h2>
     * The caller passes {@code PortalHelper::isRegisteredPortalPosition},
     * which is a pure in-memory map read. That is deliberate: the obvious
     * alternative, {@code PortalHelper.collectPortalArea}, flood-fills over
     * real BLOCK STATES and can therefore walk out of the loaded region — and
     * nothing in the projector may load a chunk (Rule 1). Growing over the
     * registry instead reads nothing, works for an aperture that straddles a
     * chunk border with one side unloaded, and reproduces the aperture
     * exactly, because the registry entries ARE the interior positions
     * {@code createTargetPortal} registered.
     *
     * The trade-off is that the registry can be stale — a destroyed portal is
     * never de-registered — so the caller separately checks that the seed
     * still carries a portal block, on a loaded chunk only. That check is the
     * feature's portal-destruction teardown trigger.
     *
     * Bounded by {@code limit}, mirroring {@code PortalHelper}'s
     * {@code MAX_PORTAL_BLOCKS}: a corrupted registry must not be able to walk
     * this into a long loop from a tick path.
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
