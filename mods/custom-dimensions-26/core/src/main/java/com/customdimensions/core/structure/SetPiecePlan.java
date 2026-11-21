package com.customdimensions.core.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * What a set piece places, decided from its spec, a seed and the piece's
 * rotation alone: every element copy and every scatter item as a
 * {@link Placement} at an offset from the origin, with its block or template,
 * its own rotation and mirror, and its age's rolls. The same seed gives the
 * same plan; the runtime only finds each placement's height and writes it.
 */
public final class SetPiecePlan {

    /** One thing to place: {@code dx}, {@code dz} from the origin (rotated), {@code dy} above the height found there. */
    public record Placement(int element, String kind, int dx, int dy, int dz, SetPieceSpec.Element source, String block,
                            int rotation, int mirror, Age age, long seed, double roll) {}

    /** Where a write lands. */
    public interface Sink {
        void set(int x, int y, int z, String what);
    }

    /** The height of the seat at a column. */
    public interface Ground {
        int at(int x, int z);
    }

    private SetPiecePlan() {
    }

    /** The placements of {@code spec} for a start with {@code seed}, the whole piece turned {@code rotation} quarter turns clockwise. */
    public static List<Placement> plan(SetPieceSpec spec, long seed, int rotation) {
        List<Placement> out = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < spec.elements().size(); i++) {
            SetPieceSpec.Element e = spec.elements().get(i);
            SplittableRandom er = random.split();
            for (int c = 0; c < e.count(); c++) {
                boolean kept = er.nextDouble() < e.chance();
                double angle = e.around() > 0 ? 2 * Math.PI * c / e.count() : 0;
                if (!kept) continue;
                int ox = e.offset()[0] + (int) Math.round(e.around() * Math.cos(angle));
                int oz = e.offset()[2] + (int) Math.round(e.around() * Math.sin(angle));
                Age age = e.age() != null ? e.age() : spec.age();
                if ("scatter".equals(e.kind())) {
                    scatter(out, i, spec, e, ox, e.offset()[1], oz, rotation, age, er.split());
                } else {
                    out.add(placement(i, spec, e, ox, e.offset()[1], oz, rotation, age, er));
                }
            }
        }
        return out;
    }

    private static void scatter(List<Placement> out, int index, SetPieceSpec spec, SetPieceSpec.Element e, int cx, int cy, int cz,
                                int rotation, Age age, SplittableRandom r) {
        int count = e.integer("count", 1);
        int radius = e.integer("radius", 0);
        int spacing = e.integer("spacing", 0);
        int total = 0;
        for (SetPieceSpec.Element item : e.items()) total += item.weight();
        List<int[]> taken = new ArrayList<>();
        int tries = 0;
        while (taken.size() < count && tries++ < count * 12) {
            double a = r.nextDouble() * 2 * Math.PI;
            double d = radius * Math.sqrt(r.nextDouble());
            int x = cx + (int) Math.round(d * Math.cos(a));
            int z = cz + (int) Math.round(d * Math.sin(a));
            boolean near = false;
            for (int[] t : taken) {
                if (Math.max(Math.abs(t[0] - x), Math.abs(t[1] - z)) < Math.max(1, spacing)) {
                    near = true;
                    break;
                }
            }
            if (near) continue;
            taken.add(new int[] {x, z});
            int pick = r.nextInt(total);
            SetPieceSpec.Element item = e.items().getFirst();
            for (SetPieceSpec.Element i : e.items()) {
                if ((pick -= i.weight()) < 0) {
                    item = i;
                    break;
                }
            }
            if (r.nextDouble() >= item.chance()) continue;
            Age itemAge = item.age() != null ? item.age() : age;
            out.add(placement(index, spec, item, x + item.offset()[0], cy + item.offset()[1], z + item.offset()[2], rotation, itemAge, r));
        }
    }

    private static Placement placement(int index, SetPieceSpec spec, SetPieceSpec.Element e, int x, int y, int z, int rotation,
                                       Age age, SplittableRandom r) {
        int[] d = rotate(x, z, rotation);
        String block = null;
        if ("block".equals(e.kind())) {
            String b = e.string("block", "minecraft:air");
            List<SetPieceSpec.Weighted> list = spec.palette().get(b);
            block = list == null ? b : pick(list, r);
        } else if ("chest".equals(e.kind())) {
            block = "minecraft:" + e.string("block", "chest");
        } else if ("spawner".equals(e.kind())) {
            block = "minecraft:spawner";
        }
        int own = switch (e.string("rotation", "random")) {
            case "none" -> 0;
            case "clockwise_90" -> 1;
            case "180" -> 2;
            case "counterclockwise_90" -> 3;
            default -> r.nextInt(4);
        };
        int mirror = switch (e.string("mirror", "none")) {
            case "left_right" -> 1;
            case "front_back" -> 2;
            case "random" -> r.nextInt(3);
            default -> 0;
        };
        return new Placement(index, e.kind(), d[0], y, d[1], e, block, (own + rotation) & 3, mirror, age, r.nextLong(), r.nextDouble());
    }

    private static String pick(List<SetPieceSpec.Weighted> list, SplittableRandom r) {
        int total = 0;
        for (SetPieceSpec.Weighted w : list) total += w.weight();
        int n = r.nextInt(total);
        for (SetPieceSpec.Weighted w : list) {
            if ((n -= w.weight()) < 0) return w.block();
        }
        return list.getLast().block();
    }

    /** ({@code x}, {@code z}) turned {@code rotation} quarter turns clockwise, as Minecraft's rotations turn a template. */
    public static int[] rotate(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> new int[] {-z, x};
            case 2 -> new int[] {-x, -z};
            case 3 -> new int[] {z, -x};
            default -> new int[] {x, z};
        };
    }

    /** The block a block, chest or spawner placement writes at a position: its block weathered to its age there. */
    public static String blockAt(Placement p, int x, int y, int z) {
        if (p.block() == null) return null;
        if (p.age() == null || !"block".equals(p.kind())) return p.block();
        String id = p.block().contains("[") ? p.block().substring(0, p.block().indexOf('[')) : p.block();
        String weathered = p.age().weather(id, roll(p.seed(), x, y, z, 1), roll(p.seed(), x, y, z, 2));
        return weathered.equals(id) ? p.block() : weathered;
    }

    /** A roll in 0–1 for a position, a seed and a channel: the same everywhere it is asked, whichever chunk asks. */
    public static double roll(long seed, int x, int y, int z, int channel) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (channel * 0x27D4EB2F165667C5L);
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /**
     * Writes every placement round an origin on {@code ground}: a block, chest
     * or spawner as its block (a chest with {@code |loot}, a spawner with
     * {@code |entity}), a template as {@code template:<id>|<rotation>|<mirror>}
     * at its position. What the runtime writes is these and the templates' own
     * blocks; this is the deterministic part of it.
     */
    public static void write(List<Placement> plan, int ox, int oz, Ground ground, Sink sink) {
        for (Placement p : plan) {
            int x = ox + p.dx();
            int z = oz + p.dz();
            int y = ground.at(x, z) + p.dy();
            switch (p.kind()) {
                case "template" -> sink.set(x, y, z, "template:" + p.source().string("id", "") + "|" + p.rotation() + "|" + p.mirror());
                case "chest" -> sink.set(x, y, z, blockAt(p, x, y, z) + "|" + p.source().string("loot", ""));
                case "spawner" -> sink.set(x, y, z, blockAt(p, x, y, z) + "|" + p.source().string("entity", ""));
                default -> sink.set(x, y, z, p.block() == null ? "thing:" + p.kind() : blockAt(p, x, y, z));
            }
        }
    }
}
