package com.customdimensions.server.render.fast;

/**
 * What a fast render keeps per pixel beyond the standard samples: the top of
 * the topmost run and the block seen there from above, the block at the
 * surface (the floor under a roof), the block a horizontal cut shows, and
 * the second to fourth runs from the top. Blocks are palette indices,
 * {@link #NONE} for nothing, heights {@link #NO_Y}; a fluid's depth over its run is kept with it.
 * Workers write disjoint pixels.
 */
public final class FastPixels {
    public static final short NONE = -1;
    /** A height for no run. */
    public static final short NO_Y = Short.MIN_VALUE;
    /** Runs below the topmost kept per pixel, as tier layers. */
    public static final int TIERS = 3;

    final int side;
    final short[] top;
    final short[] topBlock;
    final byte[] topDepth;
    /** The topmost run's own top block, under any fluid over it. */
    final short[] topGround;
    final short[] floorBlock;
    final byte[] floorDepth;
    /** The surface run's own top block, under any fluid over it. */
    final short[] floorGround;
    final short[] cutTop;
    final short[] cutBlock;
    final byte[] runs;
    final boolean[] roofed;
    final short[][] tierTop = new short[TIERS][];
    final short[][] tierBlock = new short[TIERS][];
    /** Each tier run's own top block, under any fluid over it. */
    final short[][] tierGround = new short[TIERS][];

    public FastPixels(int side) {
        this.side = side;
        int n = side * side;
        top = filled(n, NO_Y);
        topBlock = filled(n, NONE);
        topDepth = new byte[n];
        topGround = filled(n, NONE);
        floorBlock = filled(n, NONE);
        floorDepth = new byte[n];
        floorGround = filled(n, NONE);
        cutTop = filled(n, NO_Y);
        cutBlock = filled(n, NONE);
        runs = new byte[n];
        roofed = new boolean[n];
        for (int t = 0; t < TIERS; t++) {
            tierTop[t] = filled(n, NO_Y);
            tierBlock[t] = filled(n, NONE);
            tierGround[t] = filled(n, NONE);
        }
    }

    private static short[] filled(int n, short value) {
        short[] a = new short[n];
        java.util.Arrays.fill(a, value);
        return a;
    }

    public int side() {
        return side;
    }

    /** Pixels holding more than {@code index} runs. */
    public int withRun(int index) {
        int n = 0;
        for (byte r : runs) if ((r & 0xFF) > index) n++;
        return n;
    }

    public int roofedCount() {
        int n = 0;
        for (boolean r : roofed) if (r) n++;
        return n;
    }
}
