package com.customdimensions.core.survey;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Landform classes over the whole border from the noise render's coarse grid:
 * relief (max − min) in windows of about 16, 64 and 256 blocks and the
 * topographic position index (height minus the mean land height) at 256.
 * Every threshold is a percentile of the dimension's own land cells, with a
 * small absolute floor so a flat world stays plains: mountains reach the 80th
 * percentile of 256-relief (and 48 blocks) above the mean; plateaus stand at
 * the 75th percentile of the index (and 8 blocks) with locally median-or-less
 * relief; basins mirror plateaus below; hills reach the 60th percentile of
 * 64-relief (and 12 blocks); islands are land components under 2% of the grid
 * that do not touch its edge.
 */
public final class Landforms {
    public enum Form { PLAINS, HILLS, MOUNTAINS, PLATEAUS, BASINS, ISLANDS, WATER, VOID }

    /** A point the adaptive sampler should visit, and why. */
    public record Pick(int x, int z, String reason) {}

    public static final double RARE_SHARE = 5.0;

    private final int side;
    private final int step;
    private final int originX;
    private final int originZ;
    private final Form[] forms;
    private final int[] heights;
    private final double[] relief64;
    private final Map<Form, Integer> counts = new EnumMap<>(Form.class);
    private final double[] thresholds = new double[6];
    private final List<Integer> voidHoles = new ArrayList<>();

    /**
     * {@code heights} and {@code kind} are {@code side × side} (index {@code z * side + x});
     * kind 0 land, 1 fluid, 2 void. Cell (x, z) is at block {@code originX + x * step}.
     */
    public Landforms(int side, int step, int originX, int originZ, int[] heights, byte[] kind) {
        this.side = side;
        this.step = step;
        this.originX = originX;
        this.originZ = originZ;
        this.heights = heights;
        this.forms = new Form[side * side];
        int r16 = Math.max(1, Math.round(8f / step)), r64 = Math.max(1, Math.round(32f / step)), r256 = Math.max(1, Math.round(128f / step));
        double[] relief16 = relief(kind, r16);
        relief64 = relief(kind, r64);
        double[] relief256 = relief(kind, r256);
        double[] tpi = tpi(kind, r256);
        List<Double> l16 = new ArrayList<>(), l64 = new ArrayList<>(), l256 = new ArrayList<>(), lt = new ArrayList<>();
        for (int i = 0; i < forms.length; i++) {
            if (kind[i] != 0) continue;
            l16.add(relief16[i]);
            l64.add(relief64[i]);
            l256.add(relief256[i]);
            lt.add(tpi[i]);
        }
        double mountain = Math.max(Stats.percentile(arr(l256), 80), 48);
        double high = Math.max(Stats.percentile(arr(lt), 75), 8);
        double low = Math.min(Stats.percentile(arr(lt), 25), -8);
        double flat = Stats.percentile(arr(l16), 50);
        double hill = Math.max(Stats.percentile(arr(l64), 60), 12);
        double raised = Stats.percentile(arr(lt), 60);
        thresholds[0] = mountain;
        thresholds[1] = high;
        thresholds[2] = low;
        thresholds[3] = flat;
        thresholds[4] = hill;
        thresholds[5] = raised;
        for (int i = 0; i < forms.length; i++) {
            if (kind[i] == 1) forms[i] = Form.WATER;
            else if (kind[i] == 2) forms[i] = Form.VOID;
            else if (relief256[i] >= mountain && tpi[i] > 0 && tpi[i] >= raised) forms[i] = Form.MOUNTAINS;
            else if (tpi[i] >= high && relief16[i] <= flat) forms[i] = Form.PLATEAUS;
            else if (tpi[i] <= low && relief16[i] <= flat) forms[i] = Form.BASINS;
            else if (relief64[i] >= hill) forms[i] = Form.HILLS;
            else forms[i] = Form.PLAINS;
        }
        markIslands(kind);
        measureVoidHoles(kind);
        for (Form f : forms) counts.merge(f, 1, Integer::sum);
    }

    private static double[] arr(List<Double> l) {
        return l.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private double[] relief(byte[] kind, int r) {
        double[] out = new double[side * side];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                for (int dz = -r; dz <= r; dz++) {
                    int nz = z + dz;
                    if (nz < 0 || nz >= side) continue;
                    for (int dx = -r; dx <= r; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= side || kind[nz * side + nx] == 2) continue;
                        int h = heights[nz * side + nx];
                        min = Math.min(min, h);
                        max = Math.max(max, h);
                    }
                }
                out[z * side + x] = max < min ? 0 : max - min;
            }
        }
        return out;
    }

    private double[] tpi(byte[] kind, int r) {
        long[] sum = new long[(side + 1) * (side + 1)];
        int[] count = new int[(side + 1) * (side + 1)];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int i = (z + 1) * (side + 1) + x + 1;
                boolean land = kind[z * side + x] == 0;
                sum[i] = (land ? heights[z * side + x] : 0) + sum[i - 1] + sum[i - side - 1] - sum[i - side - 2];
                count[i] = (land ? 1 : 0) + count[i - 1] + count[i - side - 1] - count[i - side - 2];
            }
        }
        double[] out = new double[side * side];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(side - 1, x + r), z0 = Math.max(0, z - r), z1 = Math.min(side - 1, z + r);
                int a = z0 * (side + 1) + x0, b = z0 * (side + 1) + x1 + 1, c = (z1 + 1) * (side + 1) + x0, d = (z1 + 1) * (side + 1) + x1 + 1;
                long s = sum[d] - sum[b] - sum[c] + sum[a];
                int n = count[d] - count[b] - count[c] + count[a];
                out[z * side + x] = n == 0 ? 0 : heights[z * side + x] - (double) s / n;
            }
        }
        return out;
    }

    private void markIslands(byte[] kind) {
        boolean[] seen = new boolean[side * side];
        int limit = Math.max(1, side * side / 50);
        for (int start = 0; start < side * side; start++) {
            if (seen[start] || kind[start] != 0) continue;
            List<Integer> component = new ArrayList<>();
            boolean edge = false;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int c = queue.poll();
                component.add(c);
                int x = c % side, z = c / side;
                if (x == 0 || z == 0 || x == side - 1 || z == side - 1) edge = true;
                int[][] n = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
                for (int[] p : n) {
                    if (p[0] < 0 || p[1] < 0 || p[0] >= side || p[1] >= side) continue;
                    int i = p[1] * side + p[0];
                    if (!seen[i] && kind[i] == 0) {
                        seen[i] = true;
                        queue.add(i);
                    }
                }
            }
            if (!edge && component.size() < limit) for (int c : component) forms[c] = Form.ISLANDS;
        }
    }

    /** Sizes in cells of the 4-connected void regions, largest first. */
    private void measureVoidHoles(byte[] kind) {
        boolean[] seen = new boolean[side * side];
        for (int start = 0; start < side * side; start++) {
            if (seen[start] || kind[start] != 2) continue;
            int size = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int c = queue.poll();
                size++;
                int x = c % side, z = c / side;
                int[][] n = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
                for (int[] p : n) {
                    if (p[0] < 0 || p[1] < 0 || p[0] >= side || p[1] >= side) continue;
                    int i = p[1] * side + p[0];
                    if (!seen[i] && kind[i] == 2) {
                        seen[i] = true;
                        queue.add(i);
                    }
                }
            }
            voidHoles.add(size);
        }
        voidHoles.sort(java.util.Comparator.reverseOrder());
    }

    /** Void regions: how many, the largest's share of all void and its size as the diameter of a disc of equal area. */
    public JsonObject voidHolesJson() {
        JsonObject o = new JsonObject();
        int total = voidHoles.stream().mapToInt(Integer::intValue).sum();
        int largest = voidHoles.isEmpty() ? 0 : voidHoles.get(0);
        o.addProperty("count", voidHoles.size());
        o.addProperty("countOver1Ha", voidHoles.stream().filter(c -> c * step * step >= 10000).count());
        o.addProperty("largestPercentOfVoid", Stats.percent(largest, total));
        o.addProperty("largestDiameterBlocks", Math.round(2 * Math.sqrt(largest * (double) step * step / Math.PI)));
        o.addProperty("top3PercentOfVoid", Stats.percent(voidHoles.stream().limit(3).mapToInt(Integer::intValue).sum(), total));
        return o;
    }

    public Form form(int x, int z) {
        return forms[z * side + x];
    }

    /** The form at a block position, or null outside the grid. */
    public Form formAt(int blockX, int blockZ) {
        int x = Math.round((blockX - originX) / (float) step), z = Math.round((blockZ - originZ) / (float) step);
        if (x < 0 || z < 0 || x >= side || z >= side) return null;
        return forms[z * side + x];
    }

    public double share(Form f) {
        return Stats.percent(counts.getOrDefault(f, 0), forms.length);
    }

    /**
     * Points for adaptive patches: for each form present under
     * {@link #RARE_SHARE}% of the grid, the centre of each of its two largest
     * components, then the roughest cell (the 99th percentile of 64-relief).
     */
    public List<Pick> picks() {
        List<Pick> out = new ArrayList<>();
        for (Form f : Form.values()) {
            int n = counts.getOrDefault(f, 0);
            if (n == 0 || share(f) >= RARE_SHARE || f == Form.VOID) continue;
            List<List<Integer>> components = components(f);
            components.sort((a, b) -> b.size() - a.size());
            for (int i = 0; i < Math.min(2, components.size()); i++) {
                List<Integer> c = components.get(i);
                int best = c.get(0);
                double cx = c.stream().mapToInt(v -> v % side).average().orElse(0), cz = c.stream().mapToInt(v -> v / side).average().orElse(0);
                double bestD = Double.MAX_VALUE;
                for (int v : c) {
                    double d = Math.hypot(v % side - cx, v / side - cz);
                    if (d < bestD) {
                        bestD = d;
                        best = v;
                    }
                }
                out.add(new Pick(originX + (best % side) * step, originZ + (best / side) * step, "rare " + f.name().toLowerCase()));
            }
        }
        double cut = Stats.percentile(relief64, 99);
        int rough = -1;
        for (int i = 0; i < relief64.length; i++) if (relief64[i] >= cut && (rough < 0 || relief64[i] > relief64[rough])) rough = i;
        if (rough >= 0 && cut > thresholds[4]) {
            out.add(new Pick(originX + (rough % side) * step, originZ + (rough / side) * step, "roughest"));
        }
        return out;
    }

    private List<List<Integer>> components(Form f) {
        boolean[] seen = new boolean[forms.length];
        List<List<Integer>> out = new ArrayList<>();
        for (int s = 0; s < forms.length; s++) {
            if (seen[s] || forms[s] != f) continue;
            List<Integer> c = new ArrayList<>();
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(s);
            seen[s] = true;
            while (!q.isEmpty()) {
                int v = q.poll();
                c.add(v);
                int x = v % side, z = v / side;
                int[][] n = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
                for (int[] p : n) {
                    if (p[0] < 0 || p[1] < 0 || p[0] >= side || p[1] >= side) continue;
                    int i = p[1] * side + p[0];
                    if (!seen[i] && forms[i] == f) {
                        seen[i] = true;
                        q.add(i);
                    }
                }
            }
            out.add(c);
        }
        return out;
    }

    /** The highest land cell with the largest drop within 256 blocks: [x, height, z, targetX, targetHeight, targetZ]. */
    public int[] vista() {
        int best = -1;
        for (int i = 0; i < forms.length; i++) {
            if (forms[i] == Form.WATER || forms[i] == Form.VOID) continue;
            if (best < 0 || heights[i] > heights[best]) best = i;
        }
        if (best < 0) return null;
        int bx = best % side, bz = best / side;
        int r = Math.max(2, Math.round(256f / step));
        int target = best;
        double drop = -1;
        for (int a = 0; a < 16; a++) {
            double ang = a * Math.PI / 8;
            int dist = Math.max(1, Math.round(160f / step));
            int tx = (int) Math.round(bx + Math.cos(ang) * Math.min(dist, r)), tz = (int) Math.round(bz + Math.sin(ang) * Math.min(dist, r));
            if (tx < 0 || tz < 0 || tx >= side || tz >= side) continue;
            int lowest = Integer.MAX_VALUE;
            for (int s = 1; s <= Math.min(dist, r); s++) {
                int sx = (int) Math.round(bx + Math.cos(ang) * s), sz = (int) Math.round(bz + Math.sin(ang) * s);
                if (sx < 0 || sz < 0 || sx >= side || sz >= side) break;
                lowest = Math.min(lowest, heights[sz * side + sx]);
            }
            double d = heights[best] - lowest;
            if (d > drop) {
                drop = d;
                target = tz * side + tx;
            }
        }
        return new int[] {originX + bx * step, heights[best], originZ + bz * step, originX + (target % side) * step, heights[target],
                originZ + (target / side) * step};
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("side", side);
        o.addProperty("step", step);
        o.addProperty("method", "noise render; relief (max - min) at ~16/64/256-block windows, topographic position at 256");
        JsonObject shares = new JsonObject();
        for (Form f : Form.values()) shares.addProperty(f.name().toLowerCase(), share(f));
        o.add("sharesPercent", shares);
        o.add("voidHoles", voidHolesJson());
        JsonObject t = new JsonObject();
        t.addProperty("mountainRelief256", thresholds[0]);
        t.addProperty("plateauTpi", thresholds[1]);
        t.addProperty("basinTpi", thresholds[2]);
        t.addProperty("flatRelief16", thresholds[3]);
        t.addProperty("hillRelief64", thresholds[4]);
        o.add("thresholdsBlocks", t);
        int[] v = vista();
        if (v != null) {
            JsonArray a = new JsonArray();
            for (int x : v) a.add(x);
            o.add("vista", a);
        }
        return o;
    }

    public int side() {
        return side;
    }

    public int step() {
        return step;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }
}
