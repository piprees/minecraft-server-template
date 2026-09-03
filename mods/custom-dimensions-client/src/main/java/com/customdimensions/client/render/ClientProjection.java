package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

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
    private final double rectMinA;
    private final double rectMaxA;
    private final double rectMinB;
    private final double rectMaxB;

    private ProjectionMesh mesh;

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
        // The slab starts one block past the opening, so the boundary between
        // the two is the aperture's far face — the same arithmetic
        // ProjectionVolume.computeSourcePositions uses to place the slab.
        this.planeCoord = isPositive(this.normal) ? plane + 1 : plane;
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

    /** Grid state, air outside the described box. */
    public BlockState stateAt(int x, int y, int z) {
        BlockPos origin = origin();
        int lx = x - origin.getX();
        int ly = y - origin.getY();
        int lz = z - origin.getZ();
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX() || ly >= sizeY() || lz >= sizeZ()) {
            return Blocks.AIR.getDefaultState();
        }
        return this.states[((lx * sizeZ()) + lz) * sizeY() + ly];
    }

    /** Packed {@code sky << 4 | block}, 0 outside the described box. */
    public int lightAt(int x, int y, int z) {
        BlockPos origin = origin();
        int lx = x - origin.getX();
        int ly = y - origin.getY();
        int lz = z - origin.getZ();
        if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX() || ly >= sizeY() || lz >= sizeZ()) {
            return 0;
        }
        return this.payload.light()[((lx * sizeZ()) + lz) * sizeY() + ly] & 0xFF;
    }

    public ProjectionMesh mesh() {
        if (this.mesh == null) {
            this.mesh = ProjectionMesh.build(this);
        }
        return this.mesh;
    }

    public void discardMesh() {
        this.mesh = null;
    }
}
