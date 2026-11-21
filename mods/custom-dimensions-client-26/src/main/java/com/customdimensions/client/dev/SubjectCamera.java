package com.customdimensions.client.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Where the rig stands to show a subject (a structure's bounding box) and whether it can see it: the open space in a
 * camera column that holds the subject's base height, and the rays from the eye that reach the box. Under a roof or an
 * island the open space is the cavern or the air between floor and ceiling, never the top of the column.
 */
final class SubjectCamera {
    /** Blocks searched upward for open space when the subject's base height is inside terrain. */
    static final int SEARCH = 48;
    /** Blocks between the feet and the ceiling: the clear box round the eye reaches two blocks over the feet. */
    static final int HEADROOM = 3;
    /** Blocks a floor may lie under the subject's base and still be its ground. */
    static final int DROP = 8;
    /** Rays that must reach the box for the subject to count as seen. */
    static final int SEEN_MIN = 3;
    /** Blocks round the box a ray may stop and still count as reaching it. */
    static final double MARGIN = 1.5;

    private SubjectCamera() {}

    /**
     * The feet height in a column: {@code above} blocks over the floor of the first open space at or above {@code start}
     * with room for the camera, kept {@link #HEADROOM} blocks under its ceiling. A floor more than {@link #DROP} blocks
     * under {@code start} (a chasm, or void) is not the subject's ground: the feet are then {@code above} over
     * {@code start}. {@link Integer#MIN_VALUE} when no such space lies within {@link #SEARCH} blocks; {@code solid}
     * answers for a block height in the column.
     */
    static int open(IntPredicate solid, int start, int minY, int maxY, int above) {
        int y = Math.max(minY, Math.min(maxY, start));
        int limit = Math.min(maxY, y + SEARCH);
        while (y <= limit) {
            if (solid.test(y)) {
                y++;
                continue;
            }
            int floor = y;
            while (floor > minY && !solid.test(floor - 1)) {
                floor--;
            }
            int ceiling = y;
            while (ceiling <= maxY && !solid.test(ceiling)) {
                ceiling++;
            }
            int top = ceiling > maxY ? Integer.MAX_VALUE : ceiling - HEADROOM;
            int base = floor < start - DROP ? Math.max(y, start) : floor;
            int feet = Math.min(base + above, top);
            if (feet >= floor) {
                return feet;
            }
            y = ceiling;
        }
        return Integer.MIN_VALUE;
    }

    /** The points rays are cast to: the box's centre and the eight points a quarter in from its corners. */
    static List<double[]> points(int[] box) {
        List<double[]> out = new ArrayList<>();
        out.add(new double[] {mid(box[0], box[3], 0.5), mid(box[1], box[4], 0.5), mid(box[2], box[5], 0.5)});
        for (double fx : new double[] {0.25, 0.75}) {
            for (double fy : new double[] {0.25, 0.75}) {
                for (double fz : new double[] {0.25, 0.75}) {
                    out.add(new double[] {mid(box[0], box[3], fx), mid(box[1], box[4], fy), mid(box[2], box[5], fz)});
                }
            }
        }
        return out;
    }

    /** True when a ray that stopped at {@code hit} (null: it met nothing) reached the box, within {@link #MARGIN}. */
    static boolean reaches(double[] hit, int[] box) {
        if (hit == null) {
            return true;
        }
        return hit[0] >= box[0] - MARGIN && hit[0] <= box[3] + 1 + MARGIN && hit[1] >= box[1] - MARGIN && hit[1] <= box[4] + 1 + MARGIN
                && hit[2] >= box[2] - MARGIN && hit[2] <= box[5] + 1 + MARGIN;
    }

    /** The point the camera faces: the box's centre, a third of the way up from its base. */
    static double[] aim(int[] box) {
        return new double[] {mid(box[0], box[3], 0.5), box[1] + (box[4] + 1 - box[1]) / 3.0, mid(box[2], box[5], 0.5)};
    }

    private static double mid(int min, int max, double f) {
        return min + (max + 1 - min) * f;
    }
}
