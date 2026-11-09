package com.customdimensions.core.survey;

import com.customdimensions.core.survey.PatchGrid.Kind;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One patch reduced to what the survey keeps: per column its vertical profile
 * and layer facts, per 16-block band the solid, open, sky-exposed and fluid
 * cells, per layer the notable-block histogram, trees, notable clusters, and
 * per 16×16 cell and layer a feature vector with a clear camera.
 *
 * <p>A column's terrain is its runs of {@link Kind#SOLID} cells. Under a
 * dimension ceiling the topmost run is the roof. Runs closer than
 * {@link #GAP} blocks, or across a gap that is not open to the sky, whose two sides are one connected mass of
 * terrain (a cave), or whose upper mass spans the patch from edge to edge over terrain that reaches the floor of the world (ground over a
 * cavern), form one block; the lowest block's top is the surface,
 * the tops of blocks above it are sky surfaces. An air pocket in the top block
 * whose ceiling is within {@link #GAP} of that block's top is an overhang; a
 * pocket of three or more deeper in the surface block is cave space.
 */
public final class PatchAnalysis {
    public static final int GAP = 16;
    public static final int CELL = 16;
    public static final int NEAR_BELOW = 8;
    public static final int NEAR_ABOVE = 32;
    /** Cells of one notable block in one patch above which it is not clustered (it fills the patch). */
    public static final int CLUSTER_CAP = 50_000;

    public enum Layer { SKY, SURFACE, UNDERGROUND, ROOF }

    public enum ColumnClass { VOID, ROOFED, STACKED, OVERHANG, CAVE, SINGLE }

    public static final int NONE = Integer.MIN_VALUE;

    /** A tree: its species (the commonest log id), base column and layer; log components on one base column (within a block, 8 high) are one tree. */
    public record Tree(String species, int x, int y, int z, int logs, Layer layer, String biome, int patch) {}

    /** A notable cluster: block id, size in blocks, centre (block coordinates), layer, and a clear camera when one was found. */
    public record Found(String id, int size, double x, double y, double z, Layer layer, int patch, double[] camera) {}

    /** One 16×16 cell of one layer: its centre, the share of its columns that have the layer, its features and a camera. */
    public record Cell(Layer layer, int patch, int x, int z, double presence, Map<String, Double> numeric, Map<String, String> categorical,
                       double[] camera) {}

    public final SurveyPlan.Patch patch;
    public final int side;
    public final int minY;
    public final int height;
    // per column
    public final byte[] columnClass;
    public final boolean[] overhang;
    public final boolean[] stacked;
    public final boolean[] cave;
    public final int[] surface;
    public final int[] top;
    public final int[] second;
    public final int[] renderSurface;
    public final int[] skyTop;
    public final int[] skyBlocks;
    public final int[] roofUnderside;
    public final String[] ground;
    public final String[] topBlock;
    public final String[] biome;
    public final String[] renderBiome;
    public final String[] fluid;
    /** Blocks of fluid over the surface block where the top is a fluid, else -1. */
    public final int[] fluidDepth;
    public final boolean[] snow;
    public final boolean[] canopy;
    public final boolean[] bare;
    public final boolean[] skyExposed;
    public final String[] plant;
    public final byte[] precipitation;
    public final String[] skyGround;
    public final String[] skyBiome;
    public final int[] undergroundAir;
    public final int[] undergroundFluid;
    public final int[] undergroundCells;
    // per patch
    public final long[][] bands;
    public final Map<Layer, Map<String, Long>> notable = new LinkedHashMap<>();
    public final Map<String, Long> undergroundBiomes = new LinkedHashMap<>();
    public final List<Tree> trees = new ArrayList<>();
    public final List<Found> clusters = new ArrayList<>();
    public final Map<String, Integer> saturated = new LinkedHashMap<>();
    public final List<Cell> cells = new ArrayList<>();
    public final Map<String, Integer> structures;
    public final Map<Integer, byte[]> slices = new LinkedHashMap<>();
    public final Map<String, Long> exposedOres = new LinkedHashMap<>();

    private final PatchGrid g;
    private final int[] roofLow;
    private final int[] skyLow;
    private final int[] surfLow;
    private final int[] blockBottom;
    private BitSet skyAir;
    private int[] mass;
    private boolean[] spansPatch;

    /** Analyses {@code grid}; {@code sliceYs} are the absolute y levels of the occupancy slices. */
    public static PatchAnalysis of(PatchGrid grid, Set<String> common, int[] sliceYs) {
        return new PatchAnalysis(grid, common, sliceYs);
    }

    private PatchAnalysis(PatchGrid g, Set<String> common, int[] sliceYs) {
        this.g = g;
        this.patch = g.patch;
        this.side = g.side;
        this.minY = g.minY;
        this.height = g.height;
        int n = side * side;
        columnClass = new byte[n];
        overhang = new boolean[n];
        stacked = new boolean[n];
        cave = new boolean[n];
        surface = fill(n);
        top = fill(n);
        second = fill(n);
        renderSurface = fill(n);
        skyTop = fill(n);
        skyBlocks = new int[n];
        roofUnderside = fill(n);
        ground = new String[n];
        topBlock = new String[n];
        biome = new String[n];
        renderBiome = new String[n];
        fluid = new String[n];
        fluidDepth = new int[n];
        java.util.Arrays.fill(fluidDepth, -1);
        snow = new boolean[n];
        canopy = new boolean[n];
        bare = new boolean[n];
        skyExposed = new boolean[n];
        plant = new String[n];
        precipitation = g.precipitation.clone();
        skyGround = new String[n];
        skyBiome = new String[n];
        undergroundAir = new int[n];
        undergroundFluid = new int[n];
        undergroundCells = new int[n];
        roofLow = new int[n];
        skyLow = new int[n];
        surfLow = new int[n];
        blockBottom = new int[n];
        bands = new long[(height + 15) / 16][4];
        structures = new LinkedHashMap<>(g.structures);
        for (Layer l : Layer.values()) notable.put(l, new LinkedHashMap<>());
        skyAir = skyConnected();
        mass = masses();
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) profile(x, z);
        cellsPass(common);
        trees();
        notableClusters(common);
        for (int y : sliceYs) slice(y);
        features();
    }

    private static int[] fill(int n) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, NONE);
        return a;
    }

    private boolean solid(int x, int y, int z) {
        return g.kinds[(y * side + z) * side + x] == Kind.SOLID.ordinal();
    }

    private int kind(int x, int y, int z) {
        return g.kinds[(y * side + z) * side + x];
    }

    private void profile(int x, int z) {
        int c = z * side + x;
        int limit = height - 1;
        boolean roofed = false;
        if (g.ceiling) {
            int y = height - 1;
            while (y >= 0 && !solid(x, y, z)) y--;
            if (y >= 0) {
                int roofTop = y;
                while (y >= 0 && solid(x, y, z)) y--;
                if (y >= 0) {
                    roofed = true;
                    roofUnderside[c] = minY + y + 1;
                    limit = y;
                    if (roofTop < 0) limit = y;
                }
            }
        }
        roofLow[c] = roofed ? roofUnderside[c] - minY - NEAR_BELOW : Integer.MAX_VALUE;
        List<int[]> runs = new ArrayList<>();
        int y = limit;
        while (y >= 0) {
            while (y >= 0 && !solid(x, y, z)) y--;
            if (y < 0) break;
            int t = y;
            while (y >= 0 && solid(x, y, z)) y--;
            runs.add(new int[] {y + 1, t});
        }
        // the render's surface: one above the first solid below the top (or the roof's underside)
        if (!runs.isEmpty()) {
            renderSurface[c] = minY + runs.get(0)[1] + 1;
            renderBiome[c] = g.biomeAt(x, Math.min(height - 1, runs.get(0)[1] + 1), z);
            top[c] = minY + runs.get(0)[1];
            if (runs.size() > 1) second[c] = minY + runs.get(1)[1];
        }
        if (runs.isEmpty()) {
            columnClass[c] = (byte) (roofed ? ColumnClass.ROOFED : ColumnClass.VOID).ordinal();
            skyLow[c] = Integer.MAX_VALUE;
            surfLow[c] = 0;
            biome[c] = g.biomeAt(x, height / 2, z);
            skyExposed[c] = !roofed;
            return;
        }
        boolean floored = runs.get(runs.size() - 1)[0] == 0;
        List<List<int[]>> blocks = new ArrayList<>();
        for (int[] r : runs) {
            if (blocks.isEmpty()) {
                blocks.add(new ArrayList<>(List.of(r)));
                continue;
            }
            List<int[]> last = blocks.get(blocks.size() - 1);
            int gapTop = last.get(last.size() - 1)[0] - 1, gapBottom = r[1] + 1;
            if (gapTop - gapBottom + 1 < GAP || !openToSky(x, z, gapBottom, gapTop) || mass[(r[1] * side + z) * side + x] == mass[((gapTop + 1) * side + z) * side + x]
                    || (spansPatch[mass[((gapTop + 1) * side + z) * side + x]] && floored)) last.add(r);
            else blocks.add(new ArrayList<>(List.of(r)));
        }
        List<int[]> lowest = blocks.get(blocks.size() - 1);
        int s = lowest.get(0)[1];
        blockBottom[c] = lowest.get(lowest.size() - 1)[0];
        stacked[c] = blocks.size() > 1;
        skyBlocks[c] = blocks.size() - 1;
        List<int[]> topBlockRuns = blocks.get(0);
        int topOfTop = topBlockRuns.get(0)[1];
        for (int i = 1; i < topBlockRuns.size(); i++) {
            int ceilingY = topBlockRuns.get(i - 1)[0] - 1;
            if (ceilingY >= topOfTop - GAP) overhang[c] = true;
        }
        for (int i = 1; i < lowest.size(); i++) {
            int ceilingY = lowest.get(i - 1)[0] - 1;
            int gap = lowest.get(i - 1)[0] - lowest.get(i)[1] - 1;
            if (gap >= 3 && ceilingY < s - GAP) cave[c] = true;
        }
        ColumnClass cls = roofed ? ColumnClass.ROOFED : stacked[c] ? ColumnClass.STACKED : overhang[c] ? ColumnClass.OVERHANG
                : cave[c] ? ColumnClass.CAVE : ColumnClass.SINGLE;
        columnClass[c] = (byte) cls.ordinal();
        surface[c] = minY + s;
        surfLow[c] = s - NEAR_BELOW;
        int rangeTop = blocks.size() > 1 ? blocks.get(blocks.size() - 2).get(blocks.get(blocks.size() - 2).size() - 1)[0] - 1 : limit;
        skyLow[c] = blocks.size() > 1 ? rangeTop + 1 : Integer.MAX_VALUE;
        surfaceFacts(c, x, z, s, rangeTop, false);
        boolean exposed = true;
        for (int yy = s + 1; yy < height; yy++) {
            int k = kind(x, yy, z);
            if (k == Kind.SOLID.ordinal() || k == Kind.FOLIAGE.ordinal()) {
                exposed = false;
                break;
            }
        }
        skyExposed[c] = exposed;
        if (blocks.size() > 1) {
            int st = blocks.get(0).get(0)[1];
            skyTop[c] = minY + st;
            skyGround[c] = g.info(x, st, z).id();
            skyBiome[c] = g.biomeAt(x, Math.min(height - 1, st + 1), z);
        }
        for (int yy = blockBottom[c]; yy < s - NEAR_BELOW; yy++) {
            int k = kind(x, yy, z);
            undergroundCells[c]++;
            if (k == Kind.AIR.ordinal() || k == Kind.PLANT.ordinal()) undergroundAir[c]++;
            else if (k == Kind.FLUID.ordinal()) undergroundFluid[c]++;
        }
    }

    /**
     * The open cells (air, plants, liquid, foliage) connected through open
     * cells to the sky: from every cell above a column's highest terrain, or,
     * under a ceiling, from the cell just below the roof. A large cave is not
     * connected to them and so never splits the terrain into layers.
     */
    private BitSet skyConnected() {
        BitSet open = new BitSet(g.kinds.length);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int y = height - 1;
                if (g.ceiling) {
                    while (y >= 0 && !solid(x, y, z)) y--;
                    while (y >= 0 && solid(x, y, z)) y--;
                    if (y >= 0) seed(open, q, x, y, z);
                    continue;
                }
                for (; y >= 0 && !solid(x, y, z); y--) seed(open, q, x, y, z);
            }
        }
        while (!q.isEmpty()) {
            int c = q.poll();
            int x = c % side, z = (c / side) % side, y = c / (side * side);
            int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] o : d) {
                int nx = x + o[0], ny = y + o[1], nz = z + o[2];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= side || nz >= side || ny >= height || solid(nx, ny, nz)) continue;
                seed(open, q, nx, ny, nz);
            }
        }
        return open;
    }

    /** Labels each 6-connected mass of terrain cells; a cave's floor and roof share one, a floating island has its own. */
    private int[] masses() {
        int[] label = new int[g.kinds.length];
        int next = 0;
        int[] stack = new int[1024];
        for (int start = 0; start < label.length; start++) {
            if (label[start] != 0 || g.kinds[start] != Kind.SOLID.ordinal()) continue;
            next++;
            int top = 0;
            stack[top++] = start;
            label[start] = next;
            while (top > 0) {
                int c = stack[--top];
                int x = c % side, z = (c / side) % side, y = c / (side * side);
                int[] n = {x + 1 < side ? c + 1 : -1, x > 0 ? c - 1 : -1, z + 1 < side ? c + side : -1, z > 0 ? c - side : -1,
                        y + 1 < height ? c + side * side : -1, y > 0 ? c - side * side : -1};
                for (int ni : n) {
                    if (ni < 0 || label[ni] != 0 || g.kinds[ni] != Kind.SOLID.ordinal()) continue;
                    label[ni] = next;
                    if (top == stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                    stack[top++] = ni;
                }
            }
        }
        int[] minX = new int[next + 1], maxX = new int[next + 1], minZ = new int[next + 1], maxZ = new int[next + 1];
        java.util.Arrays.fill(minX, side);
        java.util.Arrays.fill(minZ, side);
        java.util.Arrays.fill(maxX, -1);
        java.util.Arrays.fill(maxZ, -1);
        for (int c = 0; c < label.length; c++) {
            int l = label[c];
            if (l == 0) continue;
            int x = c % side, z = (c / side) % side;
            minX[l] = Math.min(minX[l], x);
            maxX[l] = Math.max(maxX[l], x);
            minZ[l] = Math.min(minZ[l], z);
            maxZ[l] = Math.max(maxZ[l], z);
        }
        spansPatch = new boolean[next + 1];
        for (int l = 1; l <= next; l++) spansPatch[l] = (minX[l] == 0 && maxX[l] == side - 1) || (minZ[l] == 0 && maxZ[l] == side - 1);
        return label;
    }

    private void seed(BitSet open, java.util.ArrayDeque<Integer> q, int x, int y, int z) {
        int i = (y * side + z) * side + x;
        if (open.get(i)) return;
        open.set(i);
        q.add(i);
    }

    /** Whether at least half of a column's gap from {@code bottom} to {@code top} is sky-connected air. */
    private boolean openToSky(int x, int z, int bottom, int top) {
        int n = 0;
        for (int y = bottom; y <= top; y++) if (skyAir.get((y * side + z) * side + x)) n++;
        return n * 2 >= top - bottom + 1;
    }

    private void surfaceFacts(int c, int x, int z, int s, int rangeTop, boolean sky) {
        PatchGrid.BlockInfo groundInfo = g.info(x, s, z);
        ground[c] = groundInfo.id();
        int t = -1;
        for (int yy = Math.min(rangeTop, height - 1); yy > s; yy--) {
            if (kind(x, yy, z) != Kind.AIR.ordinal()) {
                t = yy;
                break;
            }
        }
        PatchGrid.BlockInfo topInfo = t < 0 ? groundInfo : g.info(x, t, z);
        topBlock[c] = topInfo.id();
        if (topInfo.kind() == Kind.FLUID) {
            fluid[c] = topInfo.fluid() == null ? "other" : topInfo.fluid();
            fluidDepth[c] = t - s;
        }
        PatchGrid.BlockInfo above = s + 1 < height ? g.info(x, s + 1, z) : null;
        snow[c] = topInfo.snowIce() || groundInfo.snowIce() || (above != null && above.snowIce());
        for (int yy = s + 1; yy <= Math.min(rangeTop, height - 1); yy++) {
            if (kind(x, yy, z) == Kind.FOLIAGE.ordinal()) {
                canopy[c] = true;
                break;
            }
        }
        bare[c] = groundInfo.bareStone() && (above == null || above.kind() == Kind.AIR);
        if (above != null && above.kind() == Kind.PLANT && above.plant() != null) plant[c] = above.plant();
        biome[c] = g.biomeAt(x, Math.min(height - 1, s + 1), z);
    }

    /** The layer of local cell (x, y, z). */
    public Layer layer(int x, int y, int z) {
        int c = z * side + x;
        if (y >= roofLow[c]) return Layer.ROOF;
        if (y >= skyLow[c]) return Layer.SKY;
        if (y >= surfLow[c]) return Layer.SURFACE;
        return Layer.UNDERGROUND;
    }

    private void cellsPass(Set<String> common) {
        int[] highest = new int[side * side];
        for (int c = 0; c < side * side; c++) highest[c] = g.topSolid(c % side, c / side);
        long[] counts = new long[g.palette.size()];
        Map<Layer, long[]> perLayer = new LinkedHashMap<>();
        for (Layer l : Layer.values()) perLayer.put(l, new long[g.palette.size()]);
        for (int y = 0; y < height; y++) {
            long[] band = bands[y / 16];
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    int i = (y * side + z) * side + x;
                    int k = g.kinds[i];
                    if (k == Kind.SOLID.ordinal() || k == Kind.FOLIAGE.ordinal()) band[0]++;
                    else if (k == Kind.FLUID.ordinal()) band[3]++;
                    else {
                        band[1]++;
                        if (y > highest[z * side + x]) band[2]++;
                    }
                    int b = g.blocks[i];
                    counts[b]++;
                    perLayer.get(layer(x, y, z))[b]++;
                    if ((k == Kind.AIR.ordinal() || k == Kind.PLANT.ordinal()) && layer(x, y, z) == Layer.UNDERGROUND && (y & 3) == 0
                            && (x & 3) == 0 && (z & 3) == 0) {
                        undergroundBiomes.merge(g.biomeAt(x, y, z), 1L, Long::sum);
                    }
                    if (k == Kind.SOLID.ordinal() && g.palette.get(b).id().endsWith("_ore") && touchesAir(x, y, z)) {
                        exposedOres.merge(g.palette.get(b).id(), 1L, Long::sum);
                    }
                }
            }
        }
        for (Layer l : Layer.values()) {
            long[] a = perLayer.get(l);
            for (int b = 0; b < a.length; b++) {
                String id = g.palette.get(b).id();
                if (a[b] > 0 && !common.contains(id)) notable.get(l).put(id, a[b]);
            }
        }
    }

    private boolean touchesAir(int x, int y, int z) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            int nx = x + o[0], ny = y + o[1], nz = z + o[2];
            if (nx < 0 || ny < 0 || nz < 0 || nx >= side || nz >= side || ny >= height) continue;
            if (kind(nx, ny, nz) == Kind.AIR.ordinal()) return true;
        }
        return false;
    }

    private void trees() {
        BitSet logs = new BitSet(side * side * height);
        for (int i = 0; i < g.blocks.length; i++) if (g.palette.get(g.blocks[i]).log()) logs.set(i);
        if (logs.isEmpty()) return;
        BitSet seen = new BitSet(logs.length());
        for (int start = logs.nextSetBit(0); start >= 0; start = logs.nextSetBit(start + 1)) {
            if (seen.get(start)) continue;
            List<Integer> comp = new ArrayList<>();
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(start);
            seen.set(start);
            while (!q.isEmpty()) {
                int c = q.poll();
                comp.add(c);
                int x = c % side, z = (c / side) % side, y = c / (side * side);
                for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= side || nz >= side || ny >= height) continue;
                    int ni = (ny * side + nz) * side + nx;
                    if (logs.get(ni) && !seen.get(ni)) {
                        seen.set(ni);
                        q.add(ni);
                    }
                }
            }
            Map<String, Integer> ids = new HashMap<>();
            int base = comp.get(0);
            for (int c : comp) {
                ids.merge(g.palette.get(g.blocks[c]).id(), 1, Integer::sum);
                if (c / (side * side) < base / (side * side)) base = c;
            }
            int bx = base % side, bz = (base / side) % side, by = base / (side * side);
            String species = Stats.dominant(ids);
            boolean merged = false;
            for (int i = 0; i < trees.size(); i++) {
                Tree t = trees.get(i);
                if (t.species().equals(species) && Math.abs(t.x() - patch.blockX() - bx) <= 1 && Math.abs(t.z() - patch.blockZ() - bz) <= 1
                        && Math.abs(t.y() - minY - by) <= 8) {
                    trees.set(i, new Tree(t.species(), t.x(), Math.min(t.y(), minY + by), t.z(), t.logs() + comp.size(), t.layer(), t.biome(), t.patch()));
                    merged = true;
                    break;
                }
            }
            if (merged) continue;
            trees.add(new Tree(species, patch.blockX() + bx, minY + by, patch.blockZ() + bz, comp.size(), layer(bx, by, bz),
                    g.biomeAt(bx, by, bz), patch.index()));
        }
    }

    private void notableClusters(Set<String> common) {
        Map<Integer, BitSet> byBlock = new HashMap<>();
        Map<Integer, Integer> sizes = new HashMap<>();
        for (int i = 0; i < g.blocks.length; i++) {
            int b = g.blocks[i];
            PatchGrid.BlockInfo info = g.palette.get(b);
            if (common.contains(info.id()) || info.kind() == Kind.FOLIAGE || info.kind() == Kind.AIR || "grass".equals(info.plant())) continue;
            int n = sizes.merge(b, 1, Integer::sum);
            if (n > CLUSTER_CAP) continue;
            byBlock.computeIfAbsent(b, k -> new BitSet(g.blocks.length)).set(i);
        }
        for (Map.Entry<Integer, BitSet> e : byBlock.entrySet()) {
            String id = g.palette.get(e.getKey()).id();
            if (sizes.get(e.getKey()) > CLUSTER_CAP) {
                saturated.put(id, sizes.get(e.getKey()));
                continue;
            }
            List<Stats.Cluster> found = Stats.clusters(e.getValue(), side, side, height);
            found.sort((a, b) -> b.size() - a.size());
            for (int i = 0; i < found.size(); i++) {
                Stats.Cluster c = found.get(i);
                int cx = (int) Math.round(c.x()), cy = (int) Math.round(c.y()), cz = (int) Math.round(c.z());
                double[] camera = i < 3 ? view(e.getKey(), cx, cy, cz) : null;
                clusters.add(new Found(id, c.size(), patch.blockX() + c.x() + 0.5, minY + c.y(), patch.blockZ() + c.z() + 0.5,
                        layer(cx, cy, cz), patch.index(), camera));
            }
        }
    }

    /** Whether a camera's eye can sit in local cell (x, y, z): it and the 3×3×3 box round it have no collision, the eye cell no liquid. */
    boolean clearEye(int x, int y, int z) {
        if (x < 1 || z < 1 || y < 1 || x >= side - 1 || z >= side - 1 || y >= height - 1) return false;
        int own = kind(x, y, z);
        if (own != Kind.AIR.ordinal() && own != Kind.PLANT.ordinal()) return false;
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int k = kind(x + dx, y + dy, z + dz);
            if (k == Kind.SOLID.ordinal() || k == Kind.FOLIAGE.ordinal()) return false;
        }
        return true;
    }

    private boolean open(int x, int y, int z) {
        if (x < 0 || z < 0 || y < 0 || x >= side || z >= side || y >= height) return false;
        int k = kind(x, y, z);
        return k == Kind.AIR.ordinal() || k == Kind.PLANT.ordinal() || k == Kind.FLUID.ordinal();
    }

    /**
     * A camera 6 to 16 blocks from a cluster centre with a clear line back to
     * it: along the 26 directions, past the cluster's own blocks, as far as
     * open cells go; [feetX, feetY, feetZ, atX, atY, atZ] in block coordinates, or null.
     */
    private double[] view(int block, int cx, int cy, int cz) {
        double[] best = null;
        int bestRun = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean outside = false;
            int run = 0;
            int lastClear = -1;
            for (int s = 1; s <= 16; s++) {
                int x = cx + (int) Math.round(dx * s / len), y = cy + (int) Math.round(dy * s / len), z = cz + (int) Math.round(dz * s / len);
                if (x < 0 || z < 0 || y < 0 || x >= side || z >= side || y >= height) break;
                int i = (y * side + z) * side + x;
                if (!outside && g.blocks[i] == block) continue;
                if (!open(x, y, z)) break;
                outside = true;
                run = s;
                if (s >= 6 && clearEye(x, y, z)) lastClear = s;
            }
            if (lastClear >= 6 && run > bestRun) {
                bestRun = run;
                int s = Math.min(lastClear, 12);
                while (s > 6 && !clearEye(cx + (int) Math.round(dx * s / len), cy + (int) Math.round(dy * s / len), cz + (int) Math.round(dz * s / len))) s--;
                int x = cx + (int) Math.round(dx * s / len), y = cy + (int) Math.round(dy * s / len), z = cz + (int) Math.round(dz * s / len);
                if (!clearEye(x, y, z)) continue;
                best = new double[] {patch.blockX() + x + 0.5, minY + y - 1.12, patch.blockZ() + z + 0.5, patch.blockX() + cx, minY + cy, patch.blockZ() + cz};
            }
        }
        return best;
    }

    private void slice(int absoluteY) {
        int y = absoluteY - minY;
        if (y < 0 || y >= height) return;
        byte[] s = new byte[side * side];
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) s[z * side + x] = (byte) kind(x, y, z);
        slices.put(absoluteY, s);
    }

    // ---- cells ----------------------------------------------------------------------------------

    private void features() {
        int cellsPerSide = Math.max(1, side / CELL);
        int patchMin = Integer.MAX_VALUE, patchMax = Integer.MIN_VALUE;
        for (int v : surface) {
            if (v == NONE) continue;
            patchMin = Math.min(patchMin, v);
            patchMax = Math.max(patchMax, v);
        }
        double relief48 = patchMax < patchMin ? 0 : patchMax - patchMin;
        for (int cz = 0; cz < cellsPerSide; cz++) {
            for (int cx = 0; cx < cellsPerSide; cx++) {
                for (Layer layer : Layer.values()) {
                    Cell cell = cell(layer, cx, cz, relief48);
                    if (cell != null) cells.add(cell);
                }
            }
        }
    }

    private Cell cell(Layer layer, int cx, int cz, double relief48) {
        int x0 = cx * CELL, z0 = cz * CELL, total = 0, has = 0;
        Map<String, Double> num = new LinkedHashMap<>();
        Map<String, String> cat = new LinkedHashMap<>();
        Map<String, Integer> biomes = new HashMap<>(), grounds = new HashMap<>(), tops = new HashMap<>();
        double sumH = 0, sumAbs = 0;
        int absN = 0, minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        int canopyN = 0, plantN = 0, fluidN = 0, snowN = 0, bareN = 0, exposedN = 0, overN = 0, stackN = 0, caveN = 0, roofN = 0;
        long air = 0, fl = 0, cellsU = 0;
        double thick = 0;
        for (int z = z0; z < z0 + CELL && z < side; z++) {
            for (int x = x0; x < x0 + CELL && x < side; x++) {
                int c = z * side + x;
                total++;
                int h;
                switch (layer) {
                    case SURFACE -> h = surface[c];
                    case SKY -> h = skyTop[c];
                    case ROOF -> h = roofUnderside[c];
                    default -> h = surface[c] != NONE && undergroundCells[c] > 0 ? surface[c] : NONE;
                }
                if (h == NONE) continue;
                has++;
                sumH += h;
                minH = Math.min(minH, h);
                maxH = Math.max(maxH, h);
                if (x + 1 < x0 + CELL && x + 1 < side) {
                    int e = layerHeight(layer, c + 1);
                    if (e != NONE) {
                        sumAbs += Math.abs(e - h);
                        absN++;
                    }
                }
                switch (layer) {
                    case SURFACE -> {
                        biomes.merge(biome[c], 1, Integer::sum);
                        grounds.merge(ground[c], 1, Integer::sum);
                        tops.merge(topBlock[c], 1, Integer::sum);
                        if (canopy[c]) canopyN++;
                        if (plant[c] != null) plantN++;
                        if (fluid[c] != null) fluidN++;
                        if (snow[c]) snowN++;
                        if (bare[c]) bareN++;
                        if (skyExposed[c]) exposedN++;
                        if (overhang[c]) overN++;
                        if (stacked[c]) stackN++;
                        if (cave[c]) caveN++;
                        if (roofUnderside[c] != NONE) roofN++;
                    }
                    case SKY -> {
                        biomes.merge(skyBiome[c], 1, Integer::sum);
                        grounds.merge(skyGround[c], 1, Integer::sum);
                        thick += skyBlocks[c];
                    }
                    case ROOF -> thick += roofUnderside[c];
                    default -> {
                        air += undergroundAir[c];
                        fl += undergroundFluid[c];
                        cellsU += undergroundCells[c];
                        if (cave[c]) caveN++;
                    }
                }
            }
        }
        if (has == 0) return null;
        double presence = has / (double) total;
        int treeCount = 0;
        for (Tree t : trees) {
            int lx = t.x() - patch.blockX(), lz = t.z() - patch.blockZ();
            if (t.layer() == layer && lx >= x0 && lx < x0 + CELL && lz >= z0 && lz < z0 + CELL) treeCount++;
        }
        long notableCells = 0;
        Map<String, Integer> notableIds = new HashMap<>();
        for (Found f : clusters) {
            int lx = (int) Math.floor(f.x()) - patch.blockX(), lz = (int) Math.floor(f.z()) - patch.blockZ();
            if (f.layer() == layer && lx >= x0 && lx < x0 + CELL && lz >= z0 && lz < z0 + CELL) {
                notableCells += f.size();
                notableIds.merge(f.id(), f.size(), Integer::sum);
            }
        }
        num.put("presence", presence);
        num.put("height", sumH / has);
        num.put("relief4", absN == 0 ? 0 : sumAbs / absN);
        num.put("relief16", (double) (maxH - minH));
        if (layer == Layer.SURFACE) num.put("relief48", relief48);
        num.put("trees", (double) treeCount);
        num.put("notable", Math.log1p(notableCells / (double) has));
        switch (layer) {
            case SURFACE -> {
                num.put("canopy", canopyN / (double) has);
                num.put("plants", plantN / (double) has);
                num.put("fluid", fluidN / (double) has);
                num.put("snow", snowN / (double) has);
                num.put("bare", bareN / (double) has);
                num.put("skyExposed", exposedN / (double) has);
                num.put("overhang", overN / (double) has);
                num.put("stacked", stackN / (double) has);
                num.put("cave", caveN / (double) has);
                num.put("roofed", roofN / (double) has);
                cat.put("biome", Stats.dominant(biomes));
                cat.put("ground", Stats.dominant(grounds));
                cat.put("top", Stats.dominant(tops));
            }
            case SKY -> {
                num.put("islands", thick / has);
                cat.put("biome", Stats.dominant(biomes));
                cat.put("ground", Stats.dominant(grounds));
            }
            case ROOF -> cat.put("notableId", Stats.dominant(notableIds));
            default -> {
                num.put("caveAir", cellsU == 0 ? 0 : air / (double) cellsU);
                num.put("caveFluid", cellsU == 0 ? 0 : fl / (double) cellsU);
                num.put("cave", caveN / (double) has);
                cat.put("notableId", Stats.dominant(notableIds));
                cat.put("biome", undergroundBiome(x0, z0));
            }
        }
        return new Cell(layer, patch.index(), patch.blockX() + x0 + CELL / 2, patch.blockZ() + z0 + CELL / 2, presence, num, cat,
                camera(layer, x0, z0));
    }

    private int layerHeight(Layer layer, int c) {
        return switch (layer) {
            case SKY -> skyTop[c];
            case ROOF -> roofUnderside[c];
            default -> surface[c];
        };
    }

    private String undergroundBiome(int x0, int z0) {
        Map<String, Integer> counts = new HashMap<>();
        for (int z = z0; z < z0 + CELL && z < side; z += 4) {
            for (int x = x0; x < x0 + CELL && x < side; x += 4) {
                int c = z * side + x;
                if (surface[c] == NONE) continue;
                for (int y = blockBottom[c]; y < surfLow[c]; y += 4) {
                    int k = kind(x, y, z);
                    if (k == Kind.AIR.ordinal() || k == Kind.PLANT.ordinal()) counts.merge(g.biomeAt(x, y, z), 1, Integer::sum);
                }
            }
        }
        return Stats.dominant(counts);
    }

    /**
     * A clear camera in a cell for a layer, [feetX, feetY, feetZ, yaw, pitch]:
     * on the surface or a sky island, the column nearest the cell centre whose eye
     * (1.6 over the ground, or over a liquid's top) is clear, looking along the
     * flattest of 8 directions; underground, the clear cave cell with the longest
     * sightline; under a roof, a clear cell 3–8 below it looking up.
     */
    private double[] camera(Layer layer, int x0, int z0) {
        List<int[]> order = new ArrayList<>();
        for (int z = z0 + 1; z < z0 + CELL - 1 && z < side - 1; z++) for (int x = x0 + 1; x < x0 + CELL - 1 && x < side - 1; x++) order.add(new int[] {x, z});
        int mid = CELL / 2;
        order.sort((a, b) -> Integer.compare(Math.abs(a[0] - x0 - mid) + Math.abs(a[1] - z0 - mid), Math.abs(b[0] - x0 - mid) + Math.abs(b[1] - z0 - mid)));
        if (layer == Layer.SURFACE || layer == Layer.SKY) {
            for (int[] p : order) {
                int c = p[1] * side + p[0];
                int h = layer == Layer.SURFACE ? surface[c] : skyTop[c];
                if (h == NONE) continue;
                int feet = h - minY + 1;
                while (feet < height - 2 && kind(p[0], feet, p[1]) == Kind.FLUID.ordinal()) feet++;
                if (!clearEye(p[0], feet + 1, p[1])) continue;
                return new double[] {patch.blockX() + p[0] + 0.5, minY + feet, patch.blockZ() + p[1] + 0.5, flattestYaw(layer, p[0], p[1]), 5};
            }
            return null;
        }
        if (layer == Layer.ROOF) {
            for (int[] p : order) {
                int c = p[1] * side + p[0];
                if (roofUnderside[c] == NONE) continue;
                for (int d = 4; d <= 8; d++) {
                    int y = roofUnderside[c] - minY - d;
                    if (y > 0 && clearEye(p[0], y, p[1])) return new double[] {patch.blockX() + p[0] + 0.5, minY + y - 1.12, patch.blockZ() + p[1] + 0.5, 0, -30};
                }
            }
            return null;
        }
        double[] best = null;
        int bestRun = 0;
        for (int i = 0; i < order.size(); i += 3) {
            int[] p = order.get(i);
            int c = p[1] * side + p[0];
            if (surface[c] == NONE) continue;
            for (int y = blockBottom[c] + 1; y < surfLow[c]; y++) {
                if (!clearEye(p[0], y, p[1])) continue;
                for (int a = 0; a < 16; a++) {
                    double ang = a * Math.PI / 8;
                    int run = 0;
                    for (int s = 1; s <= 48; s++) {
                        int x = p[0] + (int) Math.round(-Math.sin(ang) * s), z = p[1] + (int) Math.round(Math.cos(ang) * s);
                        if (!open(x, y, z)) break;
                        run = s;
                    }
                    if (run > bestRun) {
                        bestRun = run;
                        best = new double[] {patch.blockX() + p[0] + 0.5, minY + y - 1.12, patch.blockZ() + p[1] + 0.5, Math.toDegrees(ang), 10};
                    }
                }
                y += 3;
            }
        }
        return bestRun >= 6 ? best : null;
    }

    /** The yaw (Minecraft degrees, 0 = +z) of the direction with the least height change over 24 blocks from a column. */
    private double flattestYaw(Layer layer, int x, int z) {
        double bestYaw = 0, bestCost = Double.MAX_VALUE;
        int c0 = z * side + x;
        int h0 = layerHeight(layer, c0);
        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4;
            double cost = 0;
            int steps = 0;
            for (int s = 2; s <= 24; s += 2) {
                int nx = x + (int) Math.round(-Math.sin(ang) * s), nz = z + (int) Math.round(Math.cos(ang) * s);
                if (nx < 0 || nz < 0 || nx >= side || nz >= side) break;
                int h = layerHeight(layer, nz * side + nx);
                cost += h == NONE ? 32 : Math.abs(h - h0);
                steps++;
            }
            if (steps < 4) continue;
            cost /= steps;
            if (cost < bestCost) {
                bestCost = cost;
                bestYaw = Math.toDegrees(ang);
            }
        }
        return bestYaw;
    }

    public int column(int x, int z) {
        return z * side + x;
    }
}
