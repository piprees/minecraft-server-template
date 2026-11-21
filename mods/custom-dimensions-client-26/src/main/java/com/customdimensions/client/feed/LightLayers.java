package com.customdimensions.client.feed;

import java.util.BitSet;
import java.util.List;

/**
 * One chunk's sky or block light as vanilla's light packet carries it: a
 * nibble array per section, indexed from the section below the lowest one.
 * No Minecraft types.
 */
public final class LightLayers {
    private final byte[][] layers;
    private final boolean[] empty;
    private final int absent;

    private LightLayers(byte[][] layers, boolean[] empty, int absent) {
        this.layers = layers;
        this.empty = empty;
        this.absent = absent;
    }

    /**
     * From a packet's mask, empty mask and arrays for {@code sections + 2}
     * slots. A slot in neither mask answers {@code absent} (15 for sky, 0 for
     * block light).
     */
    public static LightLayers of(int sections, BitSet mask, BitSet emptyMask, List<byte[]> updates, int absent) {
        int slots = sections + 2;
        byte[][] layers = new byte[slots][];
        boolean[] empty = new boolean[slots];
        int k = 0;
        for (int i = mask.nextSetBit(0); i >= 0 && i < slots; i = mask.nextSetBit(i + 1)) {
            if (k < updates.size()) {
                layers[i] = updates.get(k++);
            }
        }
        for (int i = emptyMask.nextSetBit(0); i >= 0 && i < slots; i = emptyMask.nextSetBit(i + 1)) {
            empty[i] = true;
        }
        return new LightLayers(layers, empty, absent);
    }

    /** The level at a block, with {@code section} counted from the chunk's lowest section. */
    public int get(int section, int x, int y, int z) {
        int slot = section + 1;
        if (slot < 0 || slot >= layers.length) {
            return absent;
        }
        byte[] data = layers[slot];
        if (data == null) {
            return empty[slot] ? 0 : absent;
        }
        int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        return (data[index >> 1] >> ((index & 1) << 2)) & 15;
    }
}
