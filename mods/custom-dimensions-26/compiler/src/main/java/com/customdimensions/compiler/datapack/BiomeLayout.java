package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.BiomeEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A biome list partitioned into multi-noise entries that never overlap.
 *
 * <ol>
 *   <li>A biome with {@code climate} takes that box first, in list order.</li>
 *   <li>The other biomes share what is left. When every one of them has
 *       entries in the preset's vanilla parameter list, they keep those
 *       entries in vanilla's order. Otherwise they split temperature by
 *       humidity into an even grid of cells, in list order, each cell spanning
 *       every other axis.</li>
 *   <li>Overlaps are carved away with earlier entries keeping their space
 *       ({@link MultiNoise#carve}), so a climate box always wins.</li>
 * </ol>
 *
 * A biome the game does not have is reported and left out; an empty result is
 * {@code minecraft:plains} over all of climate space.
 */
public final class BiomeLayout {
    /** Climate axis bounds that cover everything vanilla's samplers produce. */
    static final double LOW = -2.0;
    static final double HIGH = 2.0;

    private BiomeLayout() {
    }

    /** The disjoint entries for {@code biomes} over the preset's {@code list} ({@code parameters}). */
    static JsonArray partition(List<BiomeEntry> biomes, String list, JsonArray parameters, Set<String> known,
                               boolean alwaysDisjoint, String versionId, CompileReport.Entry entry) {
        Set<String> inList = new LinkedHashSet<>();
        for (JsonElement e : parameters) {
            inList.add(e.getAsJsonObject().get("biome").getAsString());
        }
        JsonArray explicit = new JsonArray();
        List<String> rest = new ArrayList<>();
        for (BiomeEntry b : biomes) {
            if (!known.contains(b.id())) {
                entry.skippedBiome(b.id(), "no vanilla biome");
                continue;
            }
            if (b.climate() != null) {
                explicit.add(entry(b.id(), climate(b.id(), b.climate())));
            } else if (!rest.contains(b.id())) {
                rest.add(b.id());
            }
        }
        JsonArray placed = new JsonArray();
        boolean grid = !rest.stream().allMatch(inList::contains);
        if (!grid) {
            for (JsonElement e : parameters) {
                if (rest.contains(e.getAsJsonObject().get("biome").getAsString())) placed.add(e);
            }
        } else {
            placed = grid(rest);
            entry.note("Biomes " + rest + " share temperature by humidity as a grid of " + rest.size()
                    + " cell(s): not every one has a vanilla " + list + " entry");
        }
        JsonArray kept = new JsonArray();
        explicit.forEach(kept::add);
        placed.forEach(kept::add);
        if (kept.isEmpty()) {
            kept.add(MultiNoise.fullRange("minecraft:plains"));
            entry.note(DatapackCompiler.FALLBACK_NOTE + ": no listed biome has a vanilla " + list
                    + " entry, so `minecraft:plains` covers all of climate space");
        }
        if (alwaysDisjoint || !explicit.isEmpty() || grid) {
            JsonArray before = kept;
            kept = MultiNoise.carve(before);
            if (!kept.equals(before)) {
                entry.note("Overlapping " + list + " entries carved apart" + (alwaysDisjoint ? " for Minecraft " + versionId : "")
                        + " (" + before.size() + " entries became " + kept.size() + "); earlier entries keep their space");
            }
            int[] pair = MultiNoise.firstOverlap(kept);
            if (pair != null) {
                throw new IllegalStateException("multi-noise entries " + pair[0] + " and " + pair[1] + " still overlap");
            }
        }
        return kept;
    }

    /** {@code ids} over an even temperature-by-humidity grid; the outer cells reach the axis bounds. */
    static JsonArray grid(List<String> ids) {
        JsonArray out = new JsonArray();
        int n = ids.size();
        if (n == 0) return out;
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) cols);
        for (int i = 0; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            int colsInRow = row == rows - 1 ? n - row * cols : cols;
            JsonObject p = new JsonObject();
            p.add("temperature", band(col, colsInRow));
            p.add("humidity", band(row, rows));
            for (String axis : List.of("continentalness", "erosion", "weirdness", "depth")) {
                p.add(axis, range(LOW, HIGH));
            }
            p.addProperty("offset", 0.0);
            out.add(entry(ids.get(i), p));
        }
        return out;
    }

    /** Band {@code i} of {@code n} across [-1, 1], the first and last reaching the axis bounds. */
    static JsonArray band(int i, int n) {
        double lo = i == 0 ? LOW : -1 + 2.0 * i / n;
        double hi = i == n - 1 ? HIGH : -1 + 2.0 * (i + 1) / n;
        return range(round(lo), round(hi));
    }

    /** A {@code climate} object as vanilla parameters: each axis a number or {@code [min, max]}, absent axes everything. */
    static JsonObject climate(String id, JsonObject climate) {
        JsonObject p = new JsonObject();
        for (String key : climate.keySet()) {
            if (!MultiNoise.AXES.contains(key) && !key.equals("offset")) {
                throw new IllegalArgumentException("biomes[" + id + "].climate." + key + " is not a climate axis " + MultiNoise.AXES);
            }
        }
        for (String axis : MultiNoise.AXES) {
            JsonElement v = climate.get(axis);
            if (v == null || v.isJsonNull()) {
                p.add(axis, range(LOW, HIGH));
            } else if (v.isJsonArray()) {
                JsonArray a = v.getAsJsonArray();
                if (a.size() != 2 || a.get(0).getAsDouble() > a.get(1).getAsDouble()) {
                    throw new IllegalArgumentException("biomes[" + id + "].climate." + axis + " must be a number or [min, max]");
                }
                p.add(axis, range(a.get(0).getAsDouble(), a.get(1).getAsDouble()));
            } else {
                p.addProperty(axis, v.getAsDouble());
            }
        }
        p.addProperty("offset", climate.has("offset") ? climate.get("offset").getAsDouble() : 0.0);
        return p;
    }

    private static JsonObject entry(String biome, JsonObject parameters) {
        JsonObject e = new JsonObject();
        e.addProperty("biome", biome);
        e.add("parameters", parameters);
        return e;
    }

    private static JsonArray range(double lo, double hi) {
        JsonArray a = new JsonArray();
        a.add(lo);
        a.add(hi);
        return a;
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
