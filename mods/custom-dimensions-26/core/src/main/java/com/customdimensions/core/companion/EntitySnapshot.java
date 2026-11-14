package com.customdimensions.core.companion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of a destination's entities a companion is told about, and when
 * nothing needs saying. A disc round the far portal, nearest-first and capped;
 * a snapshot that matches the last one at wire resolution sends nothing.
 */
public final class EntitySnapshot {
    /** Ticks between snapshots. */
    public static final int INTERVAL = 4;
    public static final double RADIUS = 48.0;
    public static final double HEIGHT = 32.0;
    public static final int MAX_ENTITIES = 24;
    public static final double POSITION_QUANTUM = 1.0 / 256.0;
    public static final double ANGLE_QUANTUM = 360.0 / 256.0;

    /** One entity's identity and pose, in destination coordinates. */
    public record Seen(int id, double x, double y, double z, float yaw, float pitch, float headYaw) {
    }

    private EntitySnapshot() {
    }

    /** Whether a snapshot is owed; {@link Long#MIN_VALUE} is "never fed". */
    public static boolean due(long tick, long lastTick, int interval) {
        return lastTick == Long.MIN_VALUE || tick - lastTick >= Math.max(1, interval);
    }

    /** The entities to send, nearest the centre first (ties by id), capped. */
    public static List<Seen> select(List<Seen> candidates, double cx, double cy, double cz,
                                    double radius, double height, int cap) {
        if (candidates == null || cap <= 0) {
            return new ArrayList<>();
        }
        List<Seen> inside = new ArrayList<>();
        for (Seen s : candidates) {
            double dx = s.x() - cx;
            double dz = s.z() - cz;
            if (dx * dx + dz * dz <= radius * radius && Math.abs(s.y() - cy) <= height) {
                inside.add(s);
            }
        }
        Seen[] order = inside.toArray(new Seen[0]);
        Arrays.sort(order, (l, r) -> {
            int by = Double.compare(distSq(l, cx, cy, cz), distSq(r, cx, cy, cz));
            return by != 0 ? by : Integer.compare(l.id(), r.id());
        });
        List<Seen> picked = new ArrayList<>();
        for (int i = 0; i < order.length && picked.size() < cap; i++) {
            picked.add(order[i]);
        }
        return picked;
    }

    private static double distSq(Seen s, double cx, double cy, double cz) {
        double dx = s.x() - cx;
        double dy = s.y() - cy;
        double dz = s.z() - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Whether the new snapshot differs from the last at wire resolution. */
    public static boolean changed(List<Seen> previous, List<Seen> current) {
        if (previous == null || current == null || previous.size() != current.size()) {
            return true;
        }
        for (int i = 0; i < current.size(); i++) {
            if (!samePose(previous.get(i), current.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** Ids the client holds that this snapshot no longer carries. */
    public static int[] departed(List<Seen> previous, List<Seen> current) {
        if (previous == null || previous.isEmpty()) {
            return new int[0];
        }
        Set<Integer> here = new LinkedHashSet<>();
        if (current != null) {
            current.forEach(s -> here.add(s.id()));
        }
        Set<Integer> before = new LinkedHashSet<>();
        previous.forEach(s -> before.add(s.id()));
        return before.stream().filter(id -> !here.contains(id)).mapToInt(Integer::intValue).toArray();
    }

    private static long q(double v, double quantum) {
        return Math.round(v / quantum);
    }

    private static boolean samePose(Seen a, Seen b) {
        return a.id() == b.id()
                && q(a.x(), POSITION_QUANTUM) == q(b.x(), POSITION_QUANTUM)
                && q(a.y(), POSITION_QUANTUM) == q(b.y(), POSITION_QUANTUM)
                && q(a.z(), POSITION_QUANTUM) == q(b.z(), POSITION_QUANTUM)
                && q(a.yaw(), ANGLE_QUANTUM) == q(b.yaw(), ANGLE_QUANTUM)
                && q(a.pitch(), ANGLE_QUANTUM) == q(b.pitch(), ANGLE_QUANTUM)
                && q(a.headYaw(), ANGLE_QUANTUM) == q(b.headYaw(), ANGLE_QUANTUM);
    }
}
