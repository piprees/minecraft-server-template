package com.customdimensions.core;

/** A chunk position; {@link #toLong} packs it the way Minecraft does. */
public final class ChunkPos {

    public final int x;
    public final int z;

    public ChunkPos(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public static long toLong(int x, int z) {
        return (x & 0xFFFFFFFFL) | (z & 0xFFFFFFFFL) << 32;
    }

    public long toLong() {
        return toLong(this.x, this.z);
    }

    public int x() {
        return this.x;
    }

    public int z() {
        return this.z;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChunkPos other && other.x == this.x && other.z == this.z;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(this.toLong());
    }

    @Override
    public String toString() {
        return "[" + this.x + ", " + this.z + "]";
    }
}
