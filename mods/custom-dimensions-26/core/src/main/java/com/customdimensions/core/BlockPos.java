package com.customdimensions.core;

/** A block position, ordered as Minecraft orders them: by y, then z, then x. */
public record BlockPos(int x, int y, int z) implements Comparable<BlockPos> {

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public int getZ() {
        return this.z;
    }

    public BlockPos up() {
        return new BlockPos(this.x, this.y + 1, this.z);
    }

    public BlockPos add(int dx, int dy, int dz) {
        return new BlockPos(this.x + dx, this.y + dy, this.z + dz);
    }

    public int getManhattanDistance(BlockPos other) {
        return Math.abs(other.x - this.x) + Math.abs(other.y - this.y) + Math.abs(other.z - this.z);
    }

    @Override
    public int compareTo(BlockPos other) {
        if (this.y != other.y) {
            return this.y - other.y;
        }
        return this.z != other.z ? this.z - other.z : this.x - other.x;
    }
}
