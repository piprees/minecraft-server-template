package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts the game's declared cells and this repo's measured windows to
 * {@link WindowProjection}.
 *
 * <p>Declared cells are authored across the whole climate space; a dimension's
 * router samples a sliver of it, so most of a table handed over unchanged is
 * never nearest to anything the world visits. This is where the two meet.
 *
 * <p>Refuses rather than guesses: a dimension with no measured window is
 * returned untouched, which is the behaviour it has today.
 */
public final class ProjectedSource {

    /**
     * climate-axes.json's key per axis, in the order a hypercube declares them.
     *
     * <p>{@code depth} is deliberately absent. It is not a noise field the
     * router narrows — it is linear in y — so its authored range already means
     * in this world what it meant where it was written, and remapping it would
     * move a surface band underground. It is also measured at a single height
     * ([T58]), so the window recorded for it is not the range the world crosses.
     */
    private static final String[] AXIS_KEY = {"temp", "humid", "cont", "eros", null, "weird"};

    /** The hypercube axis {@link #AXIS_KEY} leaves alone. */
    static final int DEPTH = 4;

    /**
     * Fewest distinct sampled values an axis needs before it is projected onto.
     *
     * <p>Not a measured threshold and not treated as one: {@code distinct = 1}
     * is unarguably inert and this only excludes that, leaving every axis that
     * varies at all in play. Where between 2 and a full grid a band stops
     * working is unmeasured, and picking a number there is what [K7] warns
     * against.
     */
    static final int MIN_DISTINCT = 2;

    private static Map<String, List<WindowProjection.Window>> windows = new LinkedHashMap<>();

    private ProjectedSource() {
    }

    /** Reads climate-axes.json. Absent or unreadable leaves every dimension unprojected. */
    public static void load(Path configDir) {
        windows = new LinkedHashMap<>();
        Path file = configDir.resolve("climate-axes.json");
        if (!Files.isRegularFile(file)) {
            MultiverseServer.LOGGER.info(
                    "No climate-axes.json at {} — declared cells are used as authored, "
                    + "across the whole climate space rather than this world's own", file);
            return;
        }
        try {
            JsonObject doc = com.google.gson.JsonParser.parseString(Files.readString(file))
                    .getAsJsonObject();
            windows = parse(doc);
            MultiverseServer.LOGGER.info("Loaded measured climate windows for {} dimension(s)",
                    windows.size());
        } catch (Exception e) {
            MultiverseServer.LOGGER.warn("climate-axes.json unreadable — no dimension is projected", e);
        }
    }

    /**
     * Windows per dimension slug from a climate-axes document. An axis missing
     * its measurement becomes a null window, which {@link WindowProjection}
     * reads as not live.
     */
    static Map<String, List<WindowProjection.Window>> parse(JsonObject doc) {
        Map<String, List<WindowProjection.Window>> out = new LinkedHashMap<>();
        JsonElement per = doc.get("perDimension");
        if (per == null || !per.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : per.getAsJsonObject().entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonElement axes = e.getValue().getAsJsonObject().get("axes");
            if (axes == null || !axes.isJsonObject()) {
                continue;
            }
            List<WindowProjection.Window> row = new ArrayList<>();
            for (String key : AXIS_KEY) {
                row.add(key == null ? null : windowOf(axes.getAsJsonObject().get(key)));
            }

            out.put(e.getKey(), row);
        }
        return out;
    }

    private static WindowProjection.Window windowOf(JsonElement axis) {
        if (axis == null || !axis.isJsonObject()) {
            return null;
        }
        JsonObject o = axis.getAsJsonObject();
        if (!o.has("min") || !o.has("max") || !o.has("distinct")) {
            return null;
        }
        return new WindowProjection.Window(o.get("min").getAsDouble(),
                o.get("max").getAsDouble(), o.get("distinct").getAsInt());
    }

    /**
     * The projected cells, and the factor their offsets were actually
     * multiplied by. {@code appliedFactor} is 1.0 on every refusal, so a
     * caller sizing something against a declared offset reads what the cells
     * carry rather than what could have been computed.
     */
    public record Projected<T>(List<Pair<MultiNoiseUtil.NoiseHypercube, T>> cells,
                               double appliedFactor) {
    }

    /**
     * The declared entries mapped into {@code dimName}'s own window.
     *
     * <p>Returns the input unchanged when the dimension has no measured window,
     * when nothing is declared, or when no axis survives the collapse filter —
     * every one of which is a refusal to guess rather than a failure.
     */
    public static <T> Projected<T> project(
            List<Pair<MultiNoiseUtil.NoiseHypercube, T>> declared, String dimName) {
        List<WindowProjection.Window> window = windows.get(dimName);
        if (window == null || declared == null || declared.isEmpty()) {
            return new Projected<>(declared, 1.0);
        }
        List<WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, T>>> cells = new ArrayList<>();
        for (Pair<MultiNoiseUtil.NoiseHypercube, T> entry : declared) {
            cells.add(toCell(entry));
        }
        WindowProjection.Result<Pair<MultiNoiseUtil.NoiseHypercube, T>> result =
                WindowProjection.project(cells, window, MIN_DISTINCT);
        if (result.rejectedAxes().size() >= WindowProjection.AXES - 1) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: no climate axis carries enough variation to place on — "
                    + "{} declared cell(s) left as authored", dimName, declared.size());
            return new Projected<>(declared, 1.0);
        }
        List<Pair<MultiNoiseUtil.NoiseHypercube, T>> out = new ArrayList<>();
        for (WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, T>> cell : result.cells()) {
            out.add(Pair.of(toHypercube(cell), cell.value().getSecond()));
        }
        MultiverseServer.LOGGER.info(
                "Dimension {}: projected {} declared cell(s) into its own window "
                + "(offset x{}, {} axis/axes rejected, {} boundary move(s), {} left tied)",
                dimName, out.size(), String.format(java.util.Locale.ROOT, "%.3f", result.offsetFactor()),
                result.rejectedAxes().size(), result.separations(), result.unseparated());
        return new Projected<>(out, result.offsetFactor());
    }

    /** A hypercube as a projectable cell, keeping the original as the payload. */
    static <T> WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, T>> toCell(
            Pair<MultiNoiseUtil.NoiseHypercube, T> entry) {
        MultiNoiseUtil.NoiseHypercube h = entry.getFirst();
        List<WindowProjection.Span> axes = new ArrayList<>(List.of());
        axes.add(spanOf(h.temperature()));
        axes.add(spanOf(h.humidity()));
        axes.add(spanOf(h.continentalness()));
        axes.add(spanOf(h.erosion()));
        axes.add(null);
        axes.add(spanOf(h.weirdness()));
        return new WindowProjection.Cell<>(entry, axes, h.offset() / SCALE);
    }

    /** The projected cell as a hypercube, taking depth back from the original. */
    static <T> MultiNoiseUtil.NoiseHypercube toHypercube(
            WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, T>> cell) {
        MultiNoiseUtil.NoiseHypercube original = cell.value().getFirst();
        return new MultiNoiseUtil.NoiseHypercube(
                rangeOf(cell.axes().get(0)),
                rangeOf(cell.axes().get(1)),
                rangeOf(cell.axes().get(2)),
                rangeOf(cell.axes().get(3)),
                original.depth(),
                rangeOf(cell.axes().get(5)),
                Math.round(cell.offset() * SCALE));
    }

    /** The game's fixed point: a climate value is stored as {@code v * 10000}. */
    private static final double SCALE = 10000.0;

    /** An unconstrained axis is a full span, which is what a null span means. */
    private static WindowProjection.Span spanOf(MultiNoiseUtil.ParameterRange range) {
        double lo = range.min() / SCALE;
        double hi = range.max() / SCALE;
        return lo <= -2.0 && hi >= 2.0 ? null : new WindowProjection.Span(lo, hi);
    }

    /** An axis nobody constrains, which is how a hypercube writes "anywhere". */
    private static final MultiNoiseUtil.ParameterRange OPEN =
            MultiNoiseUtil.ParameterRange.of(-2.0f, 2.0f);

    /**
     * A null span means UNCONSTRAINED, never "keep what was there".
     *
     * <p>The projection nulls an axis it rejects, so restoring the original
     * range here would put the constraint straight back and quietly undo the
     * collapse filter — an axis carrying no information would go on deciding
     * placement. Depth is the one axis that keeps its original, and it does so
     * by never being offered for projection at all.
     */
    private static MultiNoiseUtil.ParameterRange rangeOf(WindowProjection.Span span) {
        return span == null ? OPEN
                : new MultiNoiseUtil.ParameterRange(
                        Math.round(span.lo() * SCALE), Math.round(span.hi() * SCALE));
    }
}
