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
    private final byte[] kind;
    private List<com.customdimensions.core.zone.ZoneSpec> zones = List.of();

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
        this.kind = kind;
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

    /** Zones whose areas {@link #toJson} describes. */
    public Landforms withZones(List<com.customdimensions.core.zone.ZoneSpec> zones) {
        this.zones = List.copyOf(zones);
        return this;
    }

    /** Whether a land cell's ground falls more than one block per block to a land neighbour: steeper than 45 degrees. */
    boolean steep(int i) {
        int x = i % side, z = i / side, drop = 0;
        int[][] n = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
        for (int[] p : n) {
            if (p[0] < 0 || p[1] < 0 || p[0] >= side || p[1] >= side) continue;
            int j = p[1] * side + p[0];
            if (kind[j] == 0) drop = Math.max(drop, Math.abs(heights[i] - heights[j]));
        }
        return drop > step;
    }

    /** Slope with distance from the void: steep share and median height of land within 48 blocks of a void cell and 150+ out. */
    public JsonObject voidRimJson() {
        int[] dist = new int[side * side];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] == 2) {
                dist[i] = 0;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int c = queue.poll(), x = c % side, z = c / side;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, nz = z + dz;
                    if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                    int j = nz * side + nx;
                    if (dist[j] == Integer.MAX_VALUE) {
                        dist[j] = dist[c] + 1;
                        queue.add(j);
                    }
                }
            }
        }
        List<Double> nearH = new ArrayList<>(), farH = new ArrayList<>();
        int nearSteep = 0, farSteep = 0;
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] != 0 || dist[i] == Integer.MAX_VALUE) continue;
            double d = (double) dist[i] * step;
            if (d <= 48) {
                nearH.add((double) heights[i]);
                if (steep(i)) nearSteep++;
            } else if (d >= 150) {
                farH.add((double) heights[i]);
                if (steep(i)) farSteep++;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("nearCells", nearH.size());
        o.addProperty("nearSteepPercent", Stats.percent(nearSteep, nearH.size()));
        o.addProperty("nearHeightP50", nearH.isEmpty() ? 0 : Stats.percentile(arr(nearH), 50));
        o.addProperty("farCells", farH.size());
        o.addProperty("farSteepPercent", Stats.percent(farSteep, farH.size()));
        o.addProperty("farHeightP50", farH.isEmpty() ? 0 : Stats.percentile(arr(farH), 50));
        return o;
    }

    /** The land at or above the land's 75th height percentile: its 8-connected regions, the largest's share, and how many. */
    public JsonObject highGroundJson() {
        List<Double> land = new ArrayList<>();
        for (int i = 0; i < kind.length; i++) if (kind[i] == 0) land.add((double) heights[i]);
        JsonObject o = new JsonObject();
        if (land.isEmpty()) {
            o.addProperty("count", 0);
            o.addProperty("largestPercent", 0.0);
            o.addProperty("top3Percent", 0.0);
            return o;
        }
        double threshold = Stats.percentile(arr(land), 75);
        boolean[] seen = new boolean[side * side];
        List<Integer> sizes = new ArrayList<>();
        for (int start = 0; start < side * side; start++) {
            if (seen[start] || kind[start] != 0 || heights[start] < threshold) continue;
            int size = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int c = queue.poll(), x = c % side, z = c / side;
                size++;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, nz = z + dz;
                        if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                        int j = nz * side + nx;
                        if (!seen[j] && kind[j] == 0 && heights[j] >= threshold) {
                            seen[j] = true;
                            queue.add(j);
                        }
                    }
                }
            }
            sizes.add(size);
        }
        sizes.sort(java.util.Comparator.reverseOrder());
        int total = sizes.stream().mapToInt(Integer::intValue).sum();
        o.addProperty("thresholdY", threshold);
        o.addProperty("count", sizes.size());
        o.addProperty("countOver1Ha", sizes.stream().filter(c -> c * step * step >= 10000).count());
        o.addProperty("largestPercent", Stats.percent(sizes.get(0), total));
        o.addProperty("top3Percent", Stats.percent(sizes.stream().limit(3).mapToInt(Integer::intValue).sum(), total));
        return o;
    }

    /**
     * Per zone, the ground inside its shape against the ring 48 blocks beyond its edge: median heights, how far the
     * inside sits below the ring, its tallest rise over its own median and the share of it 40+ blocks above that median.
     */
    public JsonArray zonesJson() {
        JsonArray out = new JsonArray();
        for (com.customdimensions.core.zone.ZoneSpec zone : zones) {
            List<Double> in = new ArrayList<>(), ring = new ArrayList<>();
            int inCells = 0, inVoid = 0;
            for (int i = 0; i < kind.length; i++) {
                double bx = originX + (i % side) * step, bz = originZ + (i / side) * step;
                double outside = zone.mask().shape().outside(bx - zone.mask().centreX(), bz - zone.mask().centreZ());
                if (outside <= 0) {
                    inCells++;
                    if (kind[i] == 2) inVoid++;
                    else in.add((double) heights[i]);
                } else if (outside <= 48 && kind[i] != 2) {
                    ring.add((double) heights[i]);
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("index", zone.index());
            o.addProperty("shape", zone.shapeName());
            o.addProperty("cells", inCells);
            o.addProperty("voidPercent", Stats.percent(inVoid, inCells));
            double inP50 = in.isEmpty() ? 0 : Stats.percentile(arr(in), 50), ringP50 = ring.isEmpty() ? 0 : Stats.percentile(arr(ring), 50);
            double max = in.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            o.addProperty("insideHeightP50", inP50);
            o.addProperty("ringHeightP50", ringP50);
            o.addProperty("depthBelowRing", in.isEmpty() || ring.isEmpty() ? 0 : ringP50 - inP50);
            o.addProperty("insideMax", max);
            o.addProperty("tallestRise", in.isEmpty() ? 0 : max - inP50);
            o.addProperty("risePercent", Stats.percent(in.stream().filter(h -> h >= inP50 + 40).count(), in.size()));
            out.add(o);
        }
        return out;
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
        o.add("voidRim", voidRimJson());
        o.add("highGround", highGroundJson());
        if (!zones.isEmpty()) o.add("zones", zonesJson());
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
