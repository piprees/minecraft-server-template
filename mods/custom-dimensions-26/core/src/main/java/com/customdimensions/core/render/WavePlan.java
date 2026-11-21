package com.customdimensions.core.render;

import java.util.ArrayList;
import java.util.List;

/**
 * The passes of a fast-render wave: the first over the whole area at the
 * stride that gives about {@link #FIRST_SIDE} pixels across, each next at
 * half the stride (rounded up), until the image reaches {@code finalSide}
 * pixels, the stride reaches 1, or the next image would pass
 * {@link RenderGrid#WIDE_MAX_SIDE}.
 */
public record WavePlan(int radius, List<Pass> passes) {
    public static final int FIRST_SIDE = 512;
    /** The image side the last pass of a wave aims at by default. */
    public static final int FINAL_SIDE = 1024;

    /** One pass: its number from 1, its stride in blocks and its image side in pixels. */
    public record Pass(int index, int stride, int side) {}

    public WavePlan {
        passes = List.copyOf(passes);
    }

    /**
     * The radius a wave covers: the larger of the player and generation
     * borders (either may be absent), else {@code fallback}; rounded up to 16
     * blocks so tiles start on the density's cell lattice, within
     * {@link RenderGrid#MIN_RADIUS} to {@link RenderGrid#WIDE_MAX_RADIUS}.
     */
    public static int radius(Integer player, Integer generation, int fallback) {
        int r = 0;
        if (player != null && player > 0) r = player;
        if (generation != null && generation > r) r = generation;
        if (r == 0) r = fallback;
        return Math.clamp(Math.ceilDiv(r, 16) * 16L, RenderGrid.MIN_RADIUS, RenderGrid.WIDE_MAX_RADIUS);
    }

    public static WavePlan of(int radius, int finalSide) {
        return of(radius, FIRST_SIDE, finalSide);
    }

    public static WavePlan of(int radius, int firstSide, int finalSide) {
        if (radius < RenderGrid.MIN_RADIUS || radius > RenderGrid.WIDE_MAX_RADIUS) {
            throw new IllegalArgumentException("radius " + radius + " is outside " + RenderGrid.MIN_RADIUS + " to "
                    + RenderGrid.WIDE_MAX_RADIUS);
        }
        if (firstSide < 1 || finalSide < firstSide) throw new IllegalArgumentException("sides " + firstSide + " to " + finalSide);
        int diameter = 2 * radius;
        int stride = Math.max(1, Math.ceilDiv(diameter, firstSide));
        while (Math.ceilDiv(diameter, stride) > RenderGrid.WIDE_MAX_SIDE) stride++;
        List<Pass> out = new ArrayList<>();
        out.add(new Pass(1, stride, Math.ceilDiv(diameter, stride)));
        while (stride > 1 && Math.ceilDiv(diameter, stride) < finalSide) {
            int next = Math.ceilDiv(stride, 2);
            if (Math.ceilDiv(diameter, next) > RenderGrid.WIDE_MAX_SIDE) break;
            stride = next;
            out.add(new Pass(out.size() + 1, stride, Math.ceilDiv(diameter, stride)));
        }
        return new WavePlan(radius, out);
    }

    public long columns() {
        long n = 0;
        for (Pass p : passes) n += (long) p.side() * p.side();
        return n;
    }
}
