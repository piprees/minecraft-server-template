package com.customdimensions.core.render.map;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One region file's explored chunks at the map's finest scale: a cell is
 * {@code step × step} blocks, {@code 512 / step} cells a side, holding the
 * mean colour and height of the columns with something visible and whether
 * most of them are water on top. A cell no saved chunk covers stays empty.
 * The cache form is gzipped, big-endian: int magic {@value #MAGIC}, int step,
 * int region x, int region z, then per cell a u8 count of columns (0 empty),
 * and for a filled cell int RGB, i16 height, u8 water.
 */
public final class ExploredRegion {
    public static final int MAGIC = 0x43444D31;
    public static final int BLOCKS = 512;

    private final int rx;
    private final int rz;
    private final int step;
    private final int side;
    private final long[] r, g, b, h;
    private final int[] columns;
    private final int[] water;

    public ExploredRegion(int rx, int rz, int step) {
        if (step < 1 || BLOCKS % step != 0) throw new IllegalArgumentException("step " + step);
        this.rx = rx;
        this.rz = rz;
        this.step = step;
        this.side = BLOCKS / step;
        int n = side * side;
        r = new long[n];
        g = new long[n];
        b = new long[n];
        h = new long[n];
        columns = new int[n];
        water = new int[n];
    }

    public int rx() {
        return rx;
    }

    public int rz() {
        return rz;
    }

    public int side() {
        return side;
    }

    /** Adds chunk {@code (cx, cz)} (chunk coordinates within the region, 0 to 31). */
    public void add(int cx, int cz, ExploredChunk.Columns c) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                if (c.height()[i] == MapShade.NO_HEIGHT) continue;
                int cell = ((cz * 16 + z) / step) * side + (cx * 16 + x) / step;
                int rgb = c.rgb()[i];
                r[cell] += rgb >> 16 & 0xFF;
                g[cell] += rgb >> 8 & 0xFF;
                b[cell] += rgb & 0xFF;
                h[cell] += c.height()[i];
                columns[cell]++;
                if (c.water()[i]) water[cell]++;
            }
        }
    }

    public boolean filled(int cell) {
        return columns[cell] > 0;
    }

    public int rgb(int cell) {
        int n = columns[cell];
        return n == 0 ? 0 : (int) (r[cell] / n) << 16 | (int) (g[cell] / n) << 8 | (int) (b[cell] / n);
    }

    public short height(int cell) {
        int n = columns[cell];
        return n == 0 ? MapShade.NO_HEIGHT : (short) Math.round((double) h[cell] / n);
    }

    public boolean water(int cell) {
        return columns[cell] > 0 && 2 * water[cell] > columns[cell];
    }

    public long filledCells() {
        long n = 0;
        for (int c : columns) if (c > 0) n++;
        return n;
    }

    public void write(OutputStream out) throws IOException {
        try (DataOutputStream d = new DataOutputStream(new GZIPOutputStream(out, 1 << 14))) {
            d.writeInt(MAGIC);
            d.writeInt(step);
            d.writeInt(rx);
            d.writeInt(rz);
            for (int c = 0; c < columns.length; c++) {
                int n = Math.min(columns[c], 255);
                d.writeByte(n);
                if (n == 0) continue;
                d.writeInt(rgb(c));
                d.writeShort(height(c));
                d.writeByte(water(c) ? 1 : 0);
            }
        }
    }

    public static ExploredRegion read(InputStream in) throws IOException {
        try (DataInputStream d = new DataInputStream(new GZIPInputStream(in, 1 << 14))) {
            if (d.readInt() != MAGIC) throw new IOException("not an explored region");
            int step = d.readInt();
            ExploredRegion out = new ExploredRegion(d.readInt(), d.readInt(), step);
            for (int c = 0; c < out.columns.length; c++) {
                int n = d.readUnsignedByte();
                if (n == 0) continue;
                int rgb = d.readInt();
                short height = d.readShort();
                boolean wet = d.readByte() != 0;
                out.columns[c] = 1;
                out.r[c] = rgb >> 16 & 0xFF;
                out.g[c] = rgb >> 8 & 0xFF;
                out.b[c] = rgb & 0xFF;
                out.h[c] = height;
                out.water[c] = wet ? 1 : 0;
            }
            return out;
        }
    }
}
