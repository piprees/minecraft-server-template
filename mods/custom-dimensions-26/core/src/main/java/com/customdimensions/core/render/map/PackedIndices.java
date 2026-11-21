package com.customdimensions.core.render.map;

/**
 * Vanilla's packed palette indices: {@code bits} per entry, as many whole
 * entries per long as fit (none spans two longs), lowest bits first. Blocks
 * take at least 4 bits, biomes at least 1; a palette of one entry stores no
 * data. The width actually used is the smallest at least that whose long
 * count matches the data.
 */
public final class PackedIndices {
    private PackedIndices() {}

    /** Bits per entry for a palette of {@code size} entries, at least {@code min}; 0 for one entry. */
    public static int bits(int size, int min) {
        if (size <= 1) return 0;
        return Math.max(min, 32 - Integer.numberOfLeadingZeros(size - 1));
    }

    /** {@code count} indices from {@code data}; all zero when the palette has one entry or there is no data. */
    public static int[] unpack(long[] data, int paletteSize, int count, int minBits) {
        int[] out = new int[count];
        int bits = bits(paletteSize, minBits);
        if (bits == 0 || data == null || data.length == 0) return out;
        while (bits < 32 && Math.ceilDiv(count, 64 / bits) != data.length) bits++;
        if (Math.ceilDiv(count, 64 / bits) != data.length) throw new IllegalArgumentException(data.length + " longs fit no width");
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < count; i++) {
            long word = data[i / perLong];
            int v = (int) (word >>> ((i % perLong) * bits) & mask);
            out[i] = v < paletteSize ? v : 0;
        }
        return out;
    }

    /** The inverse of {@link #unpack}, for tests and fixtures. */
    public static long[] pack(int[] values, int paletteSize, int minBits) {
        int bits = bits(paletteSize, minBits);
        if (bits == 0) return new long[0];
        int perLong = 64 / bits;
        long[] out = new long[Math.ceilDiv(values.length, perLong)];
        for (int i = 0; i < values.length; i++) out[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        return out;
    }
}
