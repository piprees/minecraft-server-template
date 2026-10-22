package com.customdimensions.core;

/** A chunk position; {@link #toLong} packs it the way Minecraft does. */
public record ChunkPos(int x, int z) {

    public static long toLong(int x, int z) {
        return (x & 0xFFFFFFFFL) | (z & 0xFFFFFFFFL) << 32;
    }

    public long toLong() {
        return toLong(this.x, this.z);
    }
}
