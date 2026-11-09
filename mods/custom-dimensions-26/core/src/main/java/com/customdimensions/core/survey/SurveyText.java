package com.customdimensions.core.survey;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code dossier.md}: the dossier in plain English. Every sentence states a
 * number from the dossier, with its unit; a word like "sparse" or "dense" is
 * never used, the per-hectare figure is given instead.
 */
public final class SurveyText {
    private SurveyText() {}

    public static String markdown(JsonObject d) {
        StringBuilder b = new StringBuilder();
        JsonObject plan = d.getAsJsonObject("plan");
        b.append("# Survey of ").append(d.get("dimension").getAsString()).append("\n\n");
        b.append(plan.get("patches").getAsInt()).append(" patches, ").append(plan.get("columns").getAsLong()).append(" columns (")
                .append(plan.get("hectares").getAsDouble()).append(" ha) of generated chunks, seed ").append(d.get("seed").getAsLong()).append(".\n\n");
        b.append("## Summary\n\n");
        for (String s : summary(d)) b.append("- ").append(s).append("\n");
        b.append("\n## Layers\n\n");
        JsonObject v = d.getAsJsonObject("vertical");
        b.append("- Columns by class: ").append(list(v.getAsJsonObject("columnClassPercent"), 6, "%", false)).append(".\n");
        b.append("- Columns with each layer: ").append(list(v.getAsJsonObject("layerColumnsPercent"), 4, "%", false)).append(".\n");
        b.append("- Overhangs in ").append(num(v, "overhangPercent")).append("% of columns, stacked layers in ").append(num(v, "stackedPercent"))
                .append("%, cave pockets in ").append(num(v, "cavePercent")).append("%.\n");
        b.append("\n## Places\n\n");
        JsonObject places = d.getAsJsonObject("places");
        for (String layer : List.of("surface", "sky", "underground", "roof")) {
            JsonObject l = places.getAsJsonObject(layer);
            if (l == null || l.get("cells").getAsInt() == 0) {
                b.append("- **").append(layer).append("**: no cells.\n");
                continue;
            }
            b.append("- **").append(layer).append("** (").append(l.get("cells").getAsInt()).append(" cells, ").append(l.get("k").getAsInt())
                    .append(" types, silhouette ").append(l.get("silhouette").getAsDouble()).append("):\n");
            for (JsonElement e : l.getAsJsonArray("types")) {
                JsonObject t = e.getAsJsonObject();
                b.append("  - type ").append(t.get("type").getAsInt()).append(": ").append(t.get("description").getAsString());
                JsonArray c = t.getAsJsonArray("coordinates");
                if (!c.isEmpty()) b.append(" — camera ").append(c.get(0).getAsJsonObject().get("camera"));
                b.append("\n");
            }
            for (JsonElement e : l.getAsJsonArray("landmarks")) {
                JsonObject t = e.getAsJsonObject();
                b.append("  - landmark at ").append(t.get("cell")).append(": ").append(t.get("description").getAsString()).append("\n");
            }
        }
        b.append("\n## Biome expression\n\n");
        JsonObject be = d.getAsJsonObject("biomeExpression");
        b.append(be.get("expressed").getAsInt()).append(" of ").append(be.get("placed").getAsInt()).append(" placed biomes are expressed.\n\n");
        for (Map.Entry<String, JsonElement> e : be.getAsJsonObject("biomes").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            if (!o.has("columns")) {
                b.append("- ").append(e.getKey()).append(": ").append(o.get("state").getAsString()).append(" (").append(o.get("caveQuarts").getAsLong())
                        .append(" cave samples)\n");
                continue;
            }
            JsonObject carries = o.getAsJsonObject("carries");
            b.append("- ").append(e.getKey()).append(": ").append(o.get("state").getAsString()).append(", ").append(o.get("columns").getAsLong())
                    .append(" columns; placement ").append(list(o.getAsJsonObject("placementPercent"), 6, "%", true)).append("; snow ")
                    .append(num(carries, "snowPercent")).append("%, canopy ").append(num(carries, "canopyPercent")).append("%, trees ")
                    .append(num(carries, "treesPerHectare")).append("/ha; top ").append(list(carries.getAsJsonObject("topBlocksPercent"), 3, "%", false));
            JsonArray flags = o.getAsJsonArray("flags");
            if (!flags.isEmpty()) b.append("; **").append(join(flags)).append("**");
            b.append("\n");
        }
        JsonArray missing = be.getAsJsonArray("listedNotFound");
        if (!missing.isEmpty()) b.append("\nListed in the definition, found in no sampled column: ").append(join(missing)).append(".\n");
        b.append("\n## Notable clusters\n\n");
        JsonObject types = d.getAsJsonObject("clusters").getAsJsonObject("types");
        if (types.isEmpty()) b.append("None in two or more patches.\n");
        for (Map.Entry<String, JsonElement> e : types.entrySet()) {
            JsonObject t = e.getValue().getAsJsonObject();
            b.append("- ").append(e.getKey()).append(": ").append(t.get("clusters").getAsInt()).append(" clusters in ").append(t.get("patches").getAsInt())
                    .append(" patches, median ").append(num(t, "sizeP50")).append(" blocks (max ").append(num(t, "sizeMax")).append("), spacing ")
                    .append(num(t, "meanSpacing")).append(" blocks; blocks by layer ").append(list(t.getAsJsonObject("layerBlocks"), 4, "", false)).append("\n");
        }
        b.append("\n## Vertical profile (16-block bands)\n\n| y | solid % | open % | sky-exposed % | liquid % |\n|---|---|---|---|---|\n");
        for (JsonElement e : v.getAsJsonArray("yBands")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("solidPercent").getAsDouble() == 0 && o.get("fluidPercent").getAsDouble() == 0 && o.get("skyExposedPercent").getAsDouble() == 100) continue;
            b.append("| ").append(o.get("y").getAsInt()).append(" | ").append(num(o, "solidPercent")).append(" | ").append(num(o, "openPercent"))
                    .append(" | ").append(num(o, "skyExposedPercent")).append(" | ").append(num(o, "fluidPercent")).append(" |\n");
        }
        b.append("\n(Bands entirely open sky are left out.)\n\n## Layer maps\n\n");
        JsonObject maps = d.getAsJsonObject("layerMaps");
        if (maps != null) for (Map.Entry<String, JsonElement> e : maps.entrySet()) b.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        return b.toString();
    }

    /** The headline sentences. */
    static List<String> summary(JsonObject d) {
        List<String> out = new ArrayList<>();
        JsonObject s = d.getAsJsonObject("surface");
        JsonObject tops = s.getAsJsonObject("topBlocksPercent");
        JsonObject biomes = s.getAsJsonObject("biomesPercent");
        out.add("Top blocks seen from the sky: " + list(tops, 4, "%", false) + ".");
        out.add("Surface biomes: " + list(biomes, 5, "%", false) + ".");
        out.add("Ground blocks: " + list(s.getAsJsonObject("groundBlocksPercent"), 4, "%", false) + ".");
        JsonObject f = s.getAsJsonObject("fluidCoverPercent");
        out.add("Liquid cover " + num(f, "water") + "% water, " + num(f, "lava") + "% lava, " + num(f, "other") + "% other; snow or ice "
                + num(s, "snowIcePercent") + "% (a snowing climate over " + num(s.getAsJsonObject("precipitationPercent"), "snow")
                + "%); tree canopy " + num(s, "canopyPercent") + "%; bare rock " + num(s, "bareRockPercent") + "%; no ground (void) "
                + num(s, "voidPercent") + "%.");
        JsonObject veg = d.getAsJsonObject("vegetation");
        StringBuilder trees = new StringBuilder("Trees: " + num(veg, "treesPerHectare") + " per ha");
        int i = 0;
        for (Map.Entry<String, JsonElement> e : veg.getAsJsonObject("species").entrySet()) {
            if (i++ >= 4) break;
            JsonObject sp = e.getValue().getAsJsonObject();
            trees.append(i == 1 ? " (" : ", ").append(Survey.short_(e.getKey())).append(" ").append(num(sp, "perHectare")).append("/ha, mean spacing ")
                    .append(num(sp, "meanSpacing")).append(" blocks");
        }
        if (i > 0) trees.append(")");
        out.add(trees + "; ground plants " + list(s.getAsJsonObject("groundPlantsPercent"), 5, "%", false) + ".");
        JsonObject t = d.getAsJsonObject("terrain");
        JsonObject h = t.getAsJsonObject("surfaceHeight");
        JsonObject sl = t.getAsJsonObject("slopes");
        out.add("Surface height p5 " + num(h, "p5") + ", median " + num(h, "p50") + ", p95 " + num(h, "p95") + ", max " + num(h, "max")
                + "; median relief per patch " + num(t, "reliefMedian") + " blocks; slopes under 15° " + num(sl, "flatUnder15Percent") + "%, over 45° "
                + num(sl, "over45Percent") + "%, cliffs " + num(sl, "cliffPercent") + "%; above the clouds " + num(t, "aboveCloudsPercent")
                + "%, below sea level " + num(t, "belowSeaLevelPercent") + "%.");
        JsonObject u = d.getAsJsonObject("underground");
        out.add("Underground: " + num(u, "airPercent") + "% open, " + num(u, "fluidPercent") + "% liquid; cave biomes "
                + list(u.getAsJsonObject("caveBiomesPercent"), 4, "%", false) + ".");
        JsonObject types = d.getAsJsonObject("clusters").getAsJsonObject("types");
        List<String> c = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : types.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            c.add(Survey.short_(e.getKey()) + " " + o.get("clusters").getAsInt() + " clusters, spacing " + num(o, "meanSpacing"));
            if (c.size() >= 6) break;
        }
        if (!c.isEmpty()) out.add("Notable clusters, fewest first: " + String.join("; ", c) + ".");
        JsonObject be = d.getAsJsonObject("biomeExpression");
        out.add(be.get("expressed").getAsInt() + " of " + be.get("placed").getAsInt() + " placed biomes are expressed; "
                + be.getAsJsonArray("listedNotFound").size() + " listed biomes were not found.");
        JsonObject x = d.getAsJsonObject("crossCheck");
        if (x.get("columns").getAsInt() > 0) {
            out.add("Render against world at " + x.get("columns").getAsInt() + " columns: height within 2 blocks " + num(x, "heightWithin2Percent")
                    + "%, biome match " + num(x, "biomeMatchPercent") + "%.");
        }
        if (d.has("landforms")) out.add("Landforms over the border (noise render): " + list(d.getAsJsonObject("landforms").getAsJsonObject("sharesPercent"), 8, "%", false) + ".");
        if (d.has("placeness")) {
            JsonObject pl = d.getAsJsonObject("placeness"), b = pl.getAsJsonObject("biomeRegions"), l = pl.getAsJsonObject("landformRegions");
            out.add("Placeness: biome regions " + b.get("perKm2") + "/km² (" + b.get("major") + " major, largest " + b.get("largestPercent")
                    + "%), landform regions " + l.get("perKm2") + "/km² (largest " + l.get("largestPercent") + "%), dominant place "
                    + pl.getAsJsonObject("dominant").get("percent") + "% of the border.");
        }
        return out;
    }

    static String num(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) return "n/a";
        double v = e.getAsDouble();
        if (Double.isNaN(v)) return "n/a";
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    static String list(JsonObject o, int limit, String unit, boolean skipZero) {
        if (o == null || o.isEmpty()) return "none";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (parts.size() >= limit) break;
            if (skipZero && e.getValue().getAsDouble() == 0) continue;
            parts.add(Survey.short_(e.getKey()) + " " + num(o, e.getKey()) + unit);
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private static String join(JsonArray a) {
        List<String> parts = new ArrayList<>();
        for (JsonElement e : a) parts.add(e.getAsString());
        return String.join(", ", parts);
    }
}
