package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.realtime.CompositeQuad;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One portal's destination as this client holds it: the decoded grid, the
 * aperture it is seen through, and the mesh built from it.
 *
 * <p>Every coordinate is a source-world block position — the server has
 * already applied the portal's scale transform, so nothing here knows the
 * destination's real coordinates.
 */
public final class ClientProjection {

    private final CompanionPayloads.Projection payload;
    private final BlockState[] states;
    private final Direction normal;
    private final Direction.Axis portalAxis;

    /**
     * The opening's outer rectangle in block units, on the two axes the
     * portal's plane spans. {@code axisA}/{@code axisB} are those two axes in
     * X, Y, Z order — derived from the normal, not from the portal axis, so a
     * horizontal portal (normal Y, plane spanning X and Z) is described the
     * same way as an upright one.
     */
    private final Direction.Axis axisA;
    private final Direction.Axis axisB;
    private final double planeCoord;
    private final double apertureCoord;
    private final double rectMinA;
    private final double rectMaxA;
    private final double rectMinB;
    private final double rectMaxB;

    /**
     * How far past the aperture block's near face the depth slice reaches:
     * {@code ProjectionRenderer.SLICE_FRACTION} of the block's own one-block
     * depth. Past this reach the slice no longer covers the opening; see
     * {@link #bandOpens}.
     */
    public static final double BAND_LIMIT = 0.90;

    private volatile ProjectionMesh mesh;
    private final AtomicBoolean building = new AtomicBoolean();

    public ClientProjection(CompanionPayloads.Projection payload) {
        this.payload = payload;
        this.normal = Direction.values()[payload.normal()];
        this.portalAxis = Direction.Axis.values()[payload.portalAxis()];

        this.states = new BlockState[payload.states().length];
        for (int i = 0; i < this.states.length; i++) {
            BlockState state = Block.getStateFromRawId(payload.states()[i]);
            this.states[i] = state == null ? Blocks.AIR.getDefaultState() : state;
        }

        Direction.Axis normalAxis = this.normal.getAxis();
        this.axisA = normalAxis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        this.axisB = normalAxis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minB = Integer.MAX_VALUE;
        int maxB = Integer.MIN_VALUE;
        int plane = 0;
        for (BlockPos cell : payload.aperture()) {
            plane = axisOf(cell, normalAxis);
            int a = axisOf(cell, this.axisA);
            int b = axisOf(cell, this.axisB);
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
        }
        // The destination begins where the wall ends, so the surface is the
        // aperture block's destination-side face and the frame's inner faces
        // are drawn for their full depth from both sides.
        this.apertureCoord = plane;
        this.planeCoord = CompositeQuad.surface(plane, isPositive(this.normal));
        this.rectMinA = minA;
        this.rectMaxA = maxA + 1.0;
        this.rectMinB = minB;
        this.rectMaxB = maxB + 1.0;
    }

    public CompanionPayloads.Projection payload() {
        return this.payload;
    }

    public BlockPos apertureOrigin() {
        return this.payload.apertureOrigin();
    }

    /**
     * The opening's most central cell — where a light probe belongs. A corner
     * cell is the one the frame shadows, so it measures the frame rather than
     * the portal. Ties break on x, then y, then z, so a shuffled aperture set
     * still answers the same cell.
     */
    public BlockPos apertureCentre() {
        List<BlockPos> cells = this.payload.aperture();
        if (cells.isEmpty()) {
            return apertureOrigin();
        }
        double cx = 0.0;
        double cy = 0.0;
        double cz = 0.0;
        for (BlockPos cell : cells) {
            cx += cell.getX() / (double) cells.size();
            cy += cell.getY() / (double) cells.size();
            cz += cell.getZ() / (double) cells.size();
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos cell : cells) {
            double distance = square(cell.getX() - cx) + square(cell.getY() - cy)
                    + square(cell.getZ() - cz);
            if (best == null || distance < bestDistance - 1.0e-9
                    || (distance < bestDistance + 1.0e-9 && earlier(cell, best))) {
                best = cell;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean earlier(BlockPos candidate, BlockPos held) {
        if (candidate.getX() != held.getX()) {
            return candidate.getX() < held.getX();
        }
        if (candidate.getY() != held.getY()) {
            return candidate.getY() < held.getY();
        }
        return candidate.getZ() < held.getZ();
    }

    private static double square(double value) {
        return value * value;
    }

    public BlockPos origin() {
        return this.payload.origin();
    }

    public Direction normal() {
        return this.normal;
    }

    public Direction.Axis portalAxis() {
        return this.portalAxis;
    }

    public Direction.Axis axisA() {
        return this.axisA;
    }

    public Direction.Axis axisB() {
        return this.axisB;
    }

    public Direction.Axis normalAxis() {
        return this.normal.getAxis();
    }

    public double planeCoord() {
        return this.planeCoord;
    }

    /** The aperture block's low face on the normal axis. */
    public double apertureMinCoord() {
        return this.apertureCoord;
    }

    /** The aperture block's high face on the normal axis. */
    public double apertureMaxCoord() {
        return this.apertureCoord + 1.0;
    }

    public double rectMinA() {
        return this.rectMinA;
    }

    public double rectMaxA() {
        return this.rectMaxA;
    }

    public double rectMinB() {
        return this.rectMinB;
    }

    public double rectMaxB() {
        return this.rectMaxB;
    }

    public int sizeX() {
        return this.payload.sizeX();
    }

    public int sizeY() {
        return this.payload.sizeY();
    }

    public int sizeZ() {
        return this.payload.sizeZ();
    }

    /** How far the described slab reaches past the opening. */
    public int depthExtent() {
        Direction.Axis axis = normalAxis();
        if (axis == Direction.Axis.X) {
            return sizeX();
        }
        return axis == Direction.Axis.Y ? sizeY() : sizeZ();
    }

    /**
     * How far the slab moves along the normal axis for its near face to land on
     * the portal surface. The server starts the slab at the aperture block's
     * destination-side face, which is where the surface is, so the shift is
     * zero — kept as arithmetic rather than a constant because it is the server's
     * layout that makes it so, and the assertion is what would catch that moving.
     */
    public double surfaceOffset() {
        double nearFace = isPositive(this.normal) ? 0.0 : depthExtent();
        return (this.planeCoord - axisOf(origin(), normalAxis())) - nearFace;
    }

    /**
     * How far past the near face of a {@code depth}-thick frame a sightline
     * reaches through an opening {@code span} blocks across, maximised over
     * view angle. The maximum of {@code (span - depth * tan(t)) * sin(t)} is at
     * {@code tan(t) = t} solving {@code t^3 + 2t = span / depth}.
     */
    public static double sightlineReach(double span, double depth) {
        if (span <= 0.0 || depth <= 0.0) {
            return 0.0;
        }
        double half = span / (2.0 * depth);
        double root = Math.sqrt(half * half + 8.0 / 27.0);
        double tangent = Math.cbrt(half + root) + Math.cbrt(half - root);
        return (span - depth * tangent) * tangent / Math.sqrt(1.0 + tangent * tangent);
    }

    /** The worse of the opening's two in-plane spans. */
    public double bandReach() {
        double depth = apertureMaxCoord() - apertureMinCoord();
        return Math.max(sightlineReach(this.rectMaxA - this.rectMinA, depth),
                sightlineReach(this.rectMaxB - this.rectMinB, depth));
    }

    /**
     * True when the depth slice cannot cover the opening at every view angle.
     * The slice is anchored on the nearest point of the aperture block's near
     * face, so past this the destination is drawn in front of source terrain
     * seen obliquely through the far side of the opening.
     */
    public boolean bandOpens() {
        return bandReach() > BAND_LIMIT;
    }

    public static boolean isPositive(Direction direction) {
        return direction.getOffsetX() + direction.getOffsetY() + direction.getOffsetZ() > 0;
    }

    public static int axisOf(BlockPos pos, Direction.Axis axis) {
        switch (axis) {
            case X:
                return pos.getX();
            case Y:
                return pos.getY();
            default:
                return pos.getZ();
        }
    }

    /**
     * Grid index for a SOURCE-world position, or -1 outside the described box.
     * Both arrays are indexed in source space with destination contents: the
     * server walks source cells and samples {@code toTarget} of each, so this
     * subtracts the source-space {@code origin} and never a destination one.
     */
    int indexOf(int x, int y, int z) {
        BlockPos origin = origin();
        int lx = x - origin.getX();
        int ly = y - origin.getY();
        int lz = z - origin.getZ();
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX() || ly >= sizeY() || lz >= sizeZ()) {
            return -1;
        }
        return ((lx * sizeZ()) + lz) * sizeY() + ly;
    }

    /** Grid state, air outside the described box. */
    public BlockState stateAt(int x, int y, int z) {
        int index = indexOf(x, y, z);
        return index < 0 ? Blocks.AIR.getDefaultState() : this.states[index];
    }

    /** Packed {@code sky << 4 | block}, 0 outside the described box. */
    public int lightAt(int x, int y, int z) {
        int index = indexOf(x, y, z);
        return index < 0 ? 0 : this.payload.light()[index] & 0xFF;
    }

    /** The built mesh, or null while there is not one yet. Never builds. */
    public ProjectionMesh meshIfReady() {
        return this.mesh;
    }

    /**
     * Queues the mesh build off the render thread, at most one at a time.
     * Returns null when a build is already running or the mesh is already
     * built, so a caller that runs every frame queues nothing.
     */
    public Future<?> requestMesh() {
        if (this.mesh != null || !this.building.compareAndSet(false, true)) {
            return null;
        }
        return ProjectionMesh.buildAsync(this);
    }

    void adoptMesh(ProjectionMesh built) {
        this.mesh = built;
        this.building.set(false);
    }

    /** Frees the claim so a build that could not run is retried next frame. */
    void abandonBuild() {
        this.building.set(false);
    }
}
