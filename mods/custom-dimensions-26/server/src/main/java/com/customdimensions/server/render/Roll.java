package com.customdimensions.server.render;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.dimension.StructureAliases;
import com.customdimensions.core.render.ColumnSamples;
import com.customdimensions.core.render.RenderFiles;
import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.core.render.RenderLayer;
import com.customdimensions.core.render.RenderLayers;
import com.customdimensions.core.render.StructureLayer;
import com.customdimensions.core.score.ContactSheet;
import com.customdimensions.core.score.Criteria;
import com.customdimensions.core.score.Criterion;
import com.customdimensions.core.score.DefinitionZones;
import com.customdimensions.core.score.FactsBuilder;
import com.customdimensions.core.score.RollFacts;
import com.customdimensions.core.score.RollTarget;
import com.customdimensions.core.score.Scorecard;
import com.customdimensions.core.score.Scorer;
import com.customdimensions.core.score.ZoneMap;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;
import java.util.function.LongFunction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * A roll (roller.md): each seed is sampled with the {@link Sampler} — a disc
 * of {@link #DISC_SIDE} columns a side round spawn and the
 * {@link FactsBuilder} spawn patch — its wanted, shunned and gate structures'
 * starts read from the placement state, and scored. The best {@link #KEEP}
 * are rendered (biome, height, structure starts) beside their scorecards, a
 * {@code roll.json} ranking and the contact sheet {@code index.html}. Runs as
 * a {@link RenderJob}, so off the server thread and one job at a time.
 */
public final class Roll {
    public static final int DEFAULT_COUNT = 100;
    public static final int MAX_COUNT = 500;
    public static final int KEEP = 10;
    /** Pixels a side of the disc grid. */
    public static final int DISC_SIDE = 128;
    /** The disc covers the playable radius up to this, in blocks. */
    public static final int MAX_DISC_RADIUS = 2048;
    /** Structure starts are looked for out to the playable radius, up to this, in blocks. */
    public static final int MAX_STRUCTURE_RADIUS = 8192;
    public static final String RANKING_FILE = "roll.json";

    /** What a roll asks for: the dimension, the seeds and where spawn is. */
    public record Plan(String dimension, Definition definition, int spawnX, int spawnZ, List<Long> seeds, Path outputDir) {
        public RollTarget target() {
            return RollTarget.of(dimension, definition);
        }

        public RenderGrid disc() {
            int radius = Math.max(RenderGrid.MIN_RADIUS, Math.min(target().playableRadius(), MAX_DISC_RADIUS));
            return new RenderGrid(spawnX, spawnZ, radius, Math.max(RenderGrid.minimumStep(radius), Math.ceilDiv(2 * radius, DISC_SIDE)));
        }

        public int structureRadius() {
            return Math.min(target().playableRadius(), MAX_STRUCTURE_RADIUS);
        }

        public long units() {
            return seeds.size() * (disc().columns() + FactsBuilder.patchGrid(spawnX, spawnZ).columns());
        }
    }

    /** One scored seed; {@code disc} is kept for the best only. */
    public record Rolled(long seed, Scorecard card, ColumnSamples disc) {}

    public record Outcome(String dimension, int rolled, List<Rolled> best, Path sheet, long millis, long columnsPerSecond,
                          int threads) {}

    private Roll() {}

    /** Scores every seed of {@code plan}, renders the best and writes the sheet; runs on a job's pool. */
    public static Outcome run(RenderJob job, Plan plan, LongFunction<Sampler> samplers, ExecutorService pool,
                              IntConsumer progress) throws IOException {
        long start = System.nanoTime();
        RollTarget target = plan.target();
        DefinitionZones zones = DefinitionZones.of(plan.definition());
        List<String> zoneIds = zones.zones().stream().map(ZoneMap.Zone::id).toList();
        List<Criterion> criteria = Criteria.forTarget(target, zoneIds);
        List<Rolled> best = new ArrayList<>();
        long columns = 0;
        long sampleNanos = 0;
        for (long seed : plan.seeds()) {
            Sampler sampler = samplers.apply(seed);
            long t0 = System.nanoTime();
            ColumnSamples disc = job.sample(sampler, plan.disc(), pool, progress, inside(plan.disc()), null);
            ColumnSamples patch = job.sample(sampler, FactsBuilder.patchGrid(plan.spawnX(), plan.spawnZ()), pool, progress);
            sampleNanos += System.nanoTime() - t0;
            columns += discColumns(plan.disc()) + patch.grid().columns();
            RollFacts facts = FactsBuilder.build(target, seed, disc, patch, plan.structureRadius(),
                    structures(sampler, target, plan, pool), zones);
            Scorecard card = Scorer.score(facts, target, criteria);
            best.add(new Rolled(seed, card, disc));
            best.sort((a, b) -> ContactSheet.RANKING.compare(a.card(), b.card()));
            if (best.size() > KEEP) best.removeLast();
        }
        Path sheet = write(job, plan, best, samplers, zones, pool);
        long millis = (System.nanoTime() - start) / 1_000_000;
        long rate = sampleNanos <= 0 ? 0 : columns * 1_000_000_000L / sampleNanos;
        CustomDimensions.LOG.info("rolled {} seeds of {} in {} ms ({} columns/s on {} threads); best {} -> {}", plan.seeds().size(),
                plan.dimension(), millis, rate, job.threads(), best.isEmpty() ? "none" : best.getFirst().seed(), sheet);
        return new Outcome(plan.dimension(), plan.seeds().size(), List.copyOf(best), sheet, millis, rate, job.threads());
    }

    static long discColumns(RenderGrid grid) {
        RenderJob.Mask inside = inside(grid);
        long n = 0;
        for (int py = 0; py < grid.side(); py++) for (int px = 0; px < grid.side(); px++) if (inside.wanted(px, py)) n++;
        return n;
    }

    /** The structures a seed is scored on: the pool, and the starts of the wanted, shunned and gate structures. */
    static RollFacts.Structures structures(Sampler sampler, RollTarget target, Plan plan, ExecutorService workers) {
        ChunkGeneratorStructureState state = sampler.structureState();
        Set<String> pool = StructureStarts.pool(state);
        Registry<Structure> registry = sampler.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Map<String, List<String>> targets = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(target.spec().wants().keySet());
        names.addAll(target.spec().shuns());
        for (String name : names) {
            List<String> ids = resolve(registry, name);
            if (ids != null) targets.put(name, ids);
        }
        Set<String> wanted = new HashSet<>();
        targets.values().forEach(wanted::addAll);
        if (Criteria.NETHER.equals(target.dimension())) wanted.add("minecraft:fortress");
        if (Criteria.END.equals(target.dimension())) wanted.add("minecraft:end_city");
        List<RollFacts.Start> starts = wanted.isEmpty() ? List.of()
                : StructureStarts.find(sampler, state, wanted::contains, plan.spawnX(), plan.spawnZ(), plan.structureRadius(), workers, StructureStarts.CAP);
        return new RollFacts.Structures(pool, targets, starts);
    }

    /** The structure ids a want or shun name means here; null when the name is not known at all. */
    static List<String> resolve(Registry<Structure> registry, String name) {
        String resolved = StructureAliases.resolve(name);
        if (resolved == null) return null;
        if (resolved.startsWith("#")) {
            Identifier tag = Identifier.tryParse(resolved.substring(1));
            if (tag == null) return null;
            Optional<HolderSet.Named<Structure>> members = registry.get(TagKey.create(Registries.STRUCTURE, tag));
            return members.map(set -> set.stream().map(StructureStarts::id).toList()).orElse(List.of());
        }
        Identifier id = Identifier.tryParse(resolved);
        return id != null && registry.containsKey(id) ? List.of(resolved) : List.of();
    }

    /** The disc's pixels: the columns scoring reads. The corners are sampled only for the best seeds' images. */
    static RenderJob.Mask inside(RenderGrid grid) {
        long r2 = (long) grid.radius() * grid.radius();
        return (px, py) -> {
            long dx = grid.x(px) - grid.centreX();
            long dz = grid.z(py) - grid.centreZ();
            return dx * dx + dz * dz <= r2;
        };
    }

    private static Path write(RenderJob job, Plan plan, List<Rolled> best, LongFunction<Sampler> samplers, ZoneMap zones,
                              ExecutorService pool) throws IOException {
        Files.createDirectories(plan.outputDir());
        List<ContactSheet.Entry> entries = new ArrayList<>();
        JsonArray ranking = new JsonArray();
        int rank = 0;
        for (Rolled r : best) {
            rank++;
            Sampler sampler = samplers.apply(r.seed());
            RenderGrid grid = r.disc().grid();
            RenderJob.Mask inside = inside(grid);
            job.sample(sampler, grid, pool, p -> {}, (px, py) -> !inside.wanted(px, py), r.disc());
            List<StructureLayer.Marker> markers = new ArrayList<>();
            for (RollFacts.Start s : StructureStarts.find(sampler, sampler.structureState(), id -> true,
                    grid.centreX(), grid.centreZ(), grid.radius(), pool, StructureStarts.CAP)) {
                markers.add(new StructureLayer.Marker(s.structure(), s.x(), s.z()));
            }
            StructureLayer layer = new StructureLayer(markers);
            List<RenderLayer> layers = List.of(RenderLayers.BIOME, RenderLayers.HEIGHT, layer);
            RenderFiles.Written files = RenderFiles.write(plan.outputDir(), r.disc(), layers,
                    new RenderFiles.Meta(plan.dimension(), r.seed(), "roll", RenderJob.SURFACE, RenderJob.BIOME_Y, Map.of(), 0));
            String scorecard = RenderFiles.baseName(r.seed(), grid.radius()) + "-scorecard.json";
            Files.writeString(plan.outputDir().resolve(scorecard), pretty(r.card().toJson()));
            Map<String, String> images = new LinkedHashMap<>();
            files.images().forEach((name, path) -> images.put(name, path.getFileName().toString()));
            entries.add(new ContactSheet.Entry(rank, r.card(), images, scorecard, layer.legend()));
            JsonObject row = new JsonObject();
            row.addProperty("rank", rank);
            row.addProperty("seed", r.seed());
            row.addProperty("verdict", r.card().verdict().name());
            if (r.card().percentage() != null) row.addProperty("percentage", r.card().percentage());
            row.addProperty("scorecard", scorecard);
            ranking.add(row);
        }
        JsonObject root = new JsonObject();
        root.addProperty("kind", "roll");
        root.addProperty("dimension", plan.dimension());
        root.addProperty("rolled", plan.seeds().size());
        root.addProperty("discRadius", plan.disc().radius());
        root.addProperty("discStep", plan.disc().step());
        root.addProperty("structureRadius", plan.structureRadius());
        root.add("best", ranking);
        Files.writeString(plan.outputDir().resolve(RANKING_FILE),
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n");
        String summary = String.format(Locale.ROOT, "%d seeds scored over a %d-block disc round spawn (%d, %d) at a %d-block step, "
                        + "structure starts out to %d blocks.", plan.seeds().size(), plan.disc().radius(), plan.spawnX(),
                plan.spawnZ(), plan.disc().step(), plan.structureRadius());
        if (zones instanceof DefinitionZones dz && !dz.skipped().isEmpty()) summary += " Zones not scored: " + dz.skipped() + ".";
        return ContactSheet.write(plan.outputDir(), plan.dimension(), plan.seeds().size(), summary, entries);
    }

    /** The seed at {@code rank} (1-based) in the last roll's {@code roll.json} in {@code dir}. */
    public static Optional<Long> ranked(Path dir, int rank) throws IOException {
        Path file = dir.resolve(RANKING_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        JsonArray best = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("best");
        for (var row : best) {
            if (row.getAsJsonObject().get("rank").getAsInt() == rank) return Optional.of(row.getAsJsonObject().get("seed").getAsLong());
        }
        return Optional.empty();
    }

    private static String pretty(String json) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(JsonParser.parseString(json)) + "\n";
    }
}
