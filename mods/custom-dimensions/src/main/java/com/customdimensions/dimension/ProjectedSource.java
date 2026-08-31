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
     * <p>{@code depth} is deliberately absent. Where the router keeps it in
     * schema it is a surface band, so its authored range already means in this
     * world what it meant where it was written, and remapping it would move
     * that band underground. It is also measured at a single height ([T58]),
     * so the window recorded for it is not the range the world crosses.
     *
     * <p>That leaves it authored, not unexamined — see
     * {@link #depthCarriesNoInformation}.
     */
    private static final String[] AXIS_KEY = {"temp", "humid", "cont", "eros", null, "weird"};

    /** The hypercube axis {@link #AXIS_KEY} leaves alone. */
    static final int DEPTH = 4;

    /** The schema every declared parameter is bound to, either side of zero. */
    private static final double SCHEMA_LIMIT = 2.0;

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

    private static Map<String, WindowProjection.Window> depthWindows = new LinkedHashMap<>();

    private ProjectedSource() {
    }

    /** Reads climate-axes.json. Absent or unreadable leaves every dimension unprojected. */
    public static void load(Path configDir) {
        windows = new LinkedHashMap<>();
        depthWindows = new LinkedHashMap<>();
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
            apply(doc);
        } catch (Exception e) {
            MultiverseServer.LOGGER.warn("climate-axes.json unreadable — no dimension is projected", e);
        }
    }

    /** Takes a parsed climate-axes document as the live measurement set. */
    static void apply(JsonObject doc) {
        windows = parse(doc);
        depthWindows = parseDepth(doc);
        MultiverseServer.LOGGER.info("Loaded measured climate windows for {} dimension(s)",
                windows.size());
        for (Map.Entry<String, WindowProjection.Window> e : depthWindows.entrySet()) {
            if (depthCarriesNoInformation(e.getValue())) {
                MultiverseServer.LOGGER.info(
                        "Dimension {}: sampled depth {}..{} lies outside the declared schema "
                        + "±{} — depth is opened on every cell and does not place biomes",
                        e.getKey(), e.getValue().lo(), e.getValue().hi(), SCHEMA_LIMIT);
            }
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

    /**
     * The measured depth window per dimension slug. Kept apart from
     * {@link #parse} because depth is not projected onto: this is read to
     * decide whether the axis can place anything at all, not to remap it.
     */
    static Map<String, WindowProjection.Window> parseDepth(JsonObject doc) {
        Map<String, WindowProjection.Window> out = new LinkedHashMap<>();
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
            WindowProjection.Window depth = windowOf(axes.getAsJsonObject().get("depth"));
            if (depth != null) {
                out.put(e.getKey(), depth);
            }
        }
        return out;
    }

    /**
     * Whether a sampled depth window can rank one declared cell above another.
     *
     * <p>Vanilla binds every declared parameter to ±{@value #SCHEMA_LIMIT}, so a
     * window disjoint from that puts every cell strictly on the same side of
     * every sample. What survives is a ranking by which cell declared the
     * highest bound — the same answer everywhere, carrying nothing about where
     * in the world the sample came from, and dwarfing the five axes that do
     * carry something ([T76]).
     *
     * <p>Sampled-but-in-schema is left alone even when it overruns slightly:
     * there the axis still separates cells by position, and opening it would
     * discard real signal.
     */
    static boolean depthCarriesNoInformation(WindowProjection.Window depth) {
        return depth != null
                && (depth.hi() < -SCHEMA_LIMIT || depth.lo() > SCHEMA_LIMIT);
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
     * every one of which is a refusal to guess rather than a failure. Depth is
     * opened on every one of those paths where the measurement says it places
     * nothing, because that judgement is about the axis alone and does not
     * depend on whether the other five projected.
     */
    public static <T> Projected<T> project(
            List<Pair<MultiNoiseUtil.NoiseHypercube, T>> declared, String dimName) {
        boolean openDepth = depthCarriesNoInformation(depthWindows.get(dimName));
        List<WindowProjection.Window> window = windows.get(dimName);
        if (window == null || declared == null || declared.isEmpty()) {
            return new Projected<>(openDepth ? withOpenDepth(declared) : declared, 1.0);
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
            return new Projected<>(openDepth ? withOpenDepth(declared) : declared, 1.0);
        }
        List<Pair<MultiNoiseUtil.NoiseHypercube, T>> out = new ArrayList<>();
        for (WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, T>> cell : result.cells()) {
            out.add(Pair.of(toHypercube(cell, openDepth), cell.value().getSecond()));
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

    /**
     * The projected cell as a hypercube, taking depth back from the original.
     *
     * <p>The offset is clamped to what vanilla will encode. A declared offset
     * multiplied by a projection factor above 1 is the third route to the
     * value {@code MultiNoiseUtil$NoiseHypercube}'s codec refuses, and the
     * refusal drops {@code WorldGenSettings} from {@code level.dat} with
     * nothing thrown — the same ceiling the authored and default band paths
     * already apply.
     */
    static <T> MultiNoiseUtil.NoiseHypercube toHypercube(
            WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, T>> cell) {
        return toHypercube(cell, false);
    }

    /** As above; {@code openDepth} replaces the authored depth with {@link #OPEN}. */
    static <T> MultiNoiseUtil.NoiseHypercube toHypercube(
            WindowProjection.Cell<Pair<MultiNoiseUtil.NoiseHypercube, T>> cell, boolean openDepth) {
        MultiNoiseUtil.NoiseHypercube original = cell.value().getFirst();
        return new MultiNoiseUtil.NoiseHypercube(
                rangeOf(cell.axes().get(0)),
                rangeOf(cell.axes().get(1)),
                rangeOf(cell.axes().get(2)),
                rangeOf(cell.axes().get(3)),
                openDepth ? OPEN : original.depth(),
                rangeOf(cell.axes().get(5)),
                Math.round(Math.min(1.0, cell.offset()) * SCALE));
    }

    /** Every cell with depth opened and all five other axes exactly as given. */
    private static <T> List<Pair<MultiNoiseUtil.NoiseHypercube, T>> withOpenDepth(
            List<Pair<MultiNoiseUtil.NoiseHypercube, T>> declared) {
        if (declared == null || declared.isEmpty()) {
            return declared;
        }
        List<Pair<MultiNoiseUtil.NoiseHypercube, T>> out = new ArrayList<>(declared.size());
        for (Pair<MultiNoiseUtil.NoiseHypercube, T> entry : declared) {
            MultiNoiseUtil.NoiseHypercube h = entry.getFirst();
            out.add(Pair.of(new MultiNoiseUtil.NoiseHypercube(
                    h.temperature(), h.humidity(), h.continentalness(),
                    h.erosion(), OPEN, h.weirdness(), h.offset()),
                    entry.getSecond()));
        }
        return out;
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
