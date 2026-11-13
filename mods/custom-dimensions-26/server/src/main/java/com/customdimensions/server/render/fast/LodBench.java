package com.customdimensions.server.render.fast;

import com.customdimensions.server.render.Sampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link FastBench}'s LOD mode ({@value #PROPERTY}{@code =<detail>[,...]}):
 * for one case's dimension, the {@link LodSource} a Distant Horizons
 * generator answers from, measured headless: the pre-scan's shape, each
 * detail level's coarse shortcut against whole columns
 * ({@link LodSource#compare}), and one request per detail level answered end
 * to end, its segments checked as DH checks a column (topmost first, each at
 * least one block, no gap from the level's top to its floor).
 */
final class LodBench {
    static final String PROPERTY = "customdimensions.fastbench.lod";
    /** Tiles a side the comparison spreads over the reach. */
    static final int TILES = 8;

    private LodBench() {}

    static List<String> run(String slug, Sampler sampler, int reach, boolean skylit, String details) {
        List<String> out = new ArrayList<>();
        try (LodSource source = LodSource.forSampler(sampler, reach, skylit)) {
            long t = System.nanoTime();
            LodSource.Shape shape = source.shape();
            out.add(String.format(Locale.ROOT, "%s lod shape: %s, window %d..%d, pre-scan %d columns every %d blocks (%d roofed, %d floating) "
                            + "in %d ms; %s", slug, shape.mode(), shape.from(), shape.to(), shape.columns(), shape.spacing(), shape.roofed(),
                    shape.floating(), (System.nanoTime() - t) / 1_000_000, shape.rule()));
            for (String d : details.split(",")) {
                int detail = Integer.parseInt(d.trim());
                if (!LodSource.fine(detail)) {
                    LodSource.Comparison c = source.compare(detail, TILES);
                    out.add(String.format(Locale.ROOT, "%s lod shortcut detail %d (%s): %d columns, heights within 1 %.4f, exact %.4f, "
                                    + "runs kept %.4f, runs per column %.2f whole / %.2f shortcut, %d ms whole / %d ms shortcut", slug, detail,
                            c.mode(), c.columns(), c.surfaceWithinOne(), c.surfaceExact(), c.runsKept(), c.meanRunsFull(), c.meanRunsCoarse(),
                            c.fullNanos() / 1_000_000, c.coarseNanos() / 1_000_000));
                }
                out.add(request(slug, source, detail));
            }
        }
        return out;
    }

    /** One request at {@code detail} from the origin, end to end, with its segments checked. */
    static String request(String slug, LodSource source, int detail) {
        long t = System.nanoTime();
        LodSource.Column[] columns = source.columns(0, 0, detail);
        long segments = 0;
        long solid = 0;
        int bad = 0;
        String firstBad = null;
        int levelTop = Integer.MIN_VALUE;
        for (LodSource.Column c : columns) {
            List<LodColumn.Segment<BlockState, Holder<Biome>>> s = source.segments(c);
            String problem = check(s, source.minY());
            if (problem != null && bad++ == 0) firstBad = problem;
            if (!s.isEmpty()) levelTop = Math.max(levelTop, s.getFirst().top());
            segments += s.size();
            for (var seg : s) if (!seg.block().isAir() && seg.block().getFluidState().isEmpty()) solid++;
        }
        return String.format(Locale.ROOT, "%s lod request detail %d: %d columns, %.2f segments and %.2f solid segments per column, "
                        + "%d columns malformed%s, %d ms", slug, detail, columns.length, (double) segments / columns.length,
                (double) solid / columns.length, bad, firstBad == null ? "" : " (first: " + firstBad + ")",
                (System.nanoTime() - t) / 1_000_000);
    }

    /** Null when the segments are as DH takes a column, else what is wrong. */
    static <T, B> String check(List<LodColumn.Segment<T, B>> segments, int minY) {
        if (segments.isEmpty()) return "no segments";
        for (int i = 0; i < segments.size(); i++) {
            var s = segments.get(i);
            if (s.top() <= s.bottom()) return "segment " + i + " is " + s.bottom() + ".." + s.top();
            if (s.biome() == null || s.block() == null) return "segment " + i + " has no block or biome";
            if (i > 0 && segments.get(i - 1).bottom() != s.top()) return "gap above segment " + i;
        }
        return segments.getLast().bottom() == minY ? null : "ends at " + segments.getLast().bottom() + ", not the floor " + minY;
    }
}
