package com.customdimensions.core.survey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One sampled patch as the scanner read it from generated chunks: per cell a
 * {@link Kind} and an index into the block palette, per 4×4×4 quart an index
 * into the biome palette, per column the precipitation at its top, and the
 * structure starts in its chunks. Local cell (x, y, z) is at block
 * {@code originX + x, minY + y, originZ + z}; its index is {@code (y * side + z) * side + x}.
 */
public final class PatchGrid {
    /** What a cell is to the survey: open air, terrain with a collision shape, liquid, a passable non-air block, or tree wood and leaves. */
    public enum Kind { AIR, SOLID, FLUID, PLANT, FOLIAGE }

    /** What the scanner knows about a block id from the game's tags. */
    public record BlockInfo(String id, Kind kind, boolean log, String plant, boolean snowIce, boolean bareStone, String fluid) {}

    public final SurveyPlan.Patch patch;
    public final int originX;
    public final int originZ;
    public final int side;
    public final int minY;
    public final int height;
    public final boolean ceiling;
    final byte[] kinds;
    final short[] blocks;
    final List<BlockInfo> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    final short[] biomes;
    final List<String> biomePalette = new ArrayList<>();
    private final Map<String, Integer> biomeIndex = new HashMap<>();
    /** Per column: 0 none, 1 rain, 2 snow at its highest solid block. */
    final byte[] precipitation;
    final Map<String, Integer> structures = new LinkedHashMap<>();

    public PatchGrid(SurveyPlan.Patch patch, int minY, int height, boolean ceiling) {
        this.patch = patch;
        this.originX = patch.blockX();
        this.originZ = patch.blockZ();
        this.side = patch.side();
        this.minY = minY;
        this.height = height;
        this.ceiling = ceiling;
        this.kinds = new byte[side * side * height];
        this.blocks = new short[side * side * height];
        int q = side / 4;
        this.biomes = new short[q * q * Math.max(1, height / 4)];
        this.precipitation = new byte[side * side];
        block(new BlockInfo("minecraft:air", Kind.AIR, false, null, false, false, null));
    }

    /** The palette index of a block, adding it the first time. */
    public int block(BlockInfo info) {
        Integer i = paletteIndex.get(info.id());
        if (i != null) return i;
        palette.add(info);
        paletteIndex.put(info.id(), palette.size() - 1);
        return palette.size() - 1;
    }

    public int biome(String id) {
        Integer i = biomeIndex.get(id);
        if (i != null) return i;
        biomePalette.add(id);
        biomeIndex.put(id, biomePalette.size() - 1);
        return biomePalette.size() - 1;
    }

    public void set(int x, int y, int z, int paletteIndex) {
        int i = (y * side + z) * side + x;
        blocks[i] = (short) paletteIndex;
        kinds[i] = (byte) palette.get(paletteIndex).kind().ordinal();
    }

    /** Sets the biome of the quart holding local cell (x, y, z). */
    public void setBiome(int qx, int qy, int qz, int biomeIndex) {
        int q = side / 4;
        if (qy < 0 || qy >= biomes.length / (q * q)) return;
        biomes[(qy * q + qz) * q + qx] = (short) biomeIndex;
    }

    public void setPrecipitation(int x, int z, int value) {
        precipitation[z * side + x] = (byte) value;
    }

    public void addStructure(String id) {
        structures.merge(id, 1, Integer::sum);
    }

    public Kind kind(int x, int y, int z) {
        return Kind.values()[kinds[(y * side + z) * side + x]];
    }

    public BlockInfo info(int x, int y, int z) {
        return palette.get(blocks[(y * side + z) * side + x]);
    }

    public String biomeAt(int x, int y, int z) {
        int q = side / 4;
        int qy = Math.max(0, Math.min(biomes.length / (q * q) - 1, y >> 2));
        return biomePalette.isEmpty() ? "unknown" : biomePalette.get(biomes[(qy * q + (z >> 2)) * q + (x >> 2)]);
    }

    /** The highest solid or foliage cell in a column, or -1: where the scanner asks for precipitation. */
    public int topSolid(int x, int z) {
        for (int y = height - 1; y >= 0; y--) {
            int k = kinds[(y * side + z) * side + x];
            if (k == Kind.SOLID.ordinal() || k == Kind.FOLIAGE.ordinal()) return y;
        }
        return -1;
    }
}
