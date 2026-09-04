package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

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
        // The portal surface bisects the aperture block, so half the frame's
        // depth reads on each side. The slab itself still starts one block past
        // the opening, which puts the surface half a block proud of it.
        this.apertureCoord = plane;
        this.planeCoord = plane + 0.5;
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
     * the portal surface. The server starts the slab at the aperture block's far
     * face; the surface bisects that block, so the slab arrives half a block
     * behind the opening and shows the frame's inner faces around the image.
     */
    public double surfaceOffset() {
        double nearFace = isPositive(this.normal) ? 0.0 : depthExtent();
        return (this.planeCoord - axisOf(origin(), normalAxis())) - nearFace;
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
