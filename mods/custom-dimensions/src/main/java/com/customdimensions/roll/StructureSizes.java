package com.customdimensions.roll;

import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * How much ground a structure actually covers, measured from the assembly
 * vanilla builds rather than inferred from the jigsaw fields it declares.
 *
 * <p>Neither declared field is a footprint. {@code size} is the jigsaw pool's
 * maximum expansion depth and {@code max_distance_from_center} is the
 * assembler's search bound — of the pack's 783 structures, 280 declare no
 * bound at all and 246 of the rest leave it at the default 80, so it
 * discriminates almost nothing. The assembled {@link StructureStart} knows the
 * answer exactly, and knows it before a single chunk exists.
 *
 * <p><b>Headless.</b> {@code Structure.createStructureStart} takes the world
 * only as a {@code HeightLimitView} and reads no block data, so an assembly
 * here generates nothing and can run against a world at any state. The
 * biome predicate is always-true on purpose: this asks how big a thing is,
 * not where it belongs, and holding it to its own biomes would leave every
 * structure whose biomes are absent from this dimension unmeasured.
 *
 * <p><b>One assembly is not a size.</b> A jigsaw structure reaches a
 * different depth at every position, so each structure is assembled at
 * several scattered chunks and reported as a spread. A caller wanting one
 * number wants {@link Measurement#medianSpan()}; a caller wanting to know
 * whether that number means anything wants the gap between min and max.
 */
public final class StructureSizes {

    private StructureSizes() {
    }

    /** Assembly succeeds anywhere: the question is extent, not suitability. */
    private static final Predicate<RegistryEntry<Biome>> ANY_BIOME = biomeEntry -> true;

    /**
     * The golden angle, which is what keeps a sunflower spiral's points from
     * ever falling into rings — the scatter must not correlate with the
     * terrain's own periodicity or a structure gets measured on one landform.
     */
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    /** One structure's assembled extents across every position that took. */
    public record Measurement(String structureId, String step, int attempts, int samples,
                              int minSpan, int medianSpan, int maxSpan,
                              int medianX, int medianZ, int medianY, int medianPieces) {

        /** Half the median horizontal extent — the radius a placement model wants. */
        public int radius() {
            return this.medianSpan / 2;
        }

        /** How much the extent moves with position, as a fraction of the median. */
        public double spread() {
            return this.medianSpan == 0 ? 0.0
                    : (this.maxSpan - this.minSpan) / (double) this.medianSpan;
        }
    }

    /** Every structure the registry holds, measured or explicitly not. */
    public record Census(String dimension, long seed, int radiusChunks,
                         int requestedSamples, int maxAttempts,
                         List<Measurement> measured, List<String> unmeasured,
                         List<String> skipped, long millis) {

        public int total() {
            return this.measured.size() + this.unmeasured.size() + this.skipped.size();
        }

        /** Median of the per-structure medians — the table's own centre. */
        public int medianSpan() {
            List<Integer> spans = new ArrayList<>(this.measured.size());
            for (Measurement m : this.measured) {
                spans.add(m.medianSpan());
            }
            return median(spans);
        }

        /**
         * The summary line, in counts. "Measured 604 of 783" is the only
         * reading that says whether the table is usable; a structure that
         * refused every position is a hole in it, not a rounding error.
         */
        public String summary() {
            return this.measured.size() + " measured of " + this.total()
                    + " (" + this.unmeasured.size() + " refused every position, "
                    + this.skipped.size() + " unreached before the budget ran out), "
                    + "median span " + this.medianSpan() + " blocks, "
                    + this.millis + "ms";
        }
    }

    /**
     * Assemble every structure in the registry at scattered chunks in this
     * world and record what each one built.
     *
     * <p>Runs to {@code budgetMillis} and then stops, reporting the structures
     * it never reached rather than running past a caller's patience. The order
     * is the registry's own id order, so a second run with a larger budget
     * covers a superset of the first.
     *
     * <p><b>Safe off the server thread.</b> Nothing here touches
     * {@code StructureAccessor} or the world's {@code StructureStartsStorage}
     * — the plain {@code HashMap} that makes a structure LOCATE server-thread
     * only ({@code LocateManager}). A start is built and measured, never
     * stored, and vanilla itself builds starts on chunk workers.
     *
     * <p>{@code progress} counts structures decided, for a caller reporting
     * on a run it does not block on.
     */
    public static Census of(MinecraftServer server, ServerWorld world, int samples,
                            int maxAttempts, int radiusChunks, long budgetMillis,
                            java.util.concurrent.atomic.AtomicInteger progress) {
        long start = System.nanoTime();
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        long seed = world.getSeed();

        Map<String, Structure> structures = new TreeMap<>();
        for (Map.Entry<net.minecraft.registry.RegistryKey<Structure>, Structure> e
                : server.getRegistryManager().get(RegistryKeys.STRUCTURE).getEntrySet()) {
            structures.put(e.getKey().getValue().toString(), e.getValue());
        }

        List<Measurement> measured = new ArrayList<>();
        List<String> unmeasured = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        long deadline = start + budgetMillis * 1_000_000L;

        for (Map.Entry<String, Structure> entry : structures.entrySet()) {
            if (System.nanoTime() > deadline) {
                skipped.add(entry.getKey());
                continue;
            }
            Measurement m = measure(server, world, generator, noiseConfig, seed,
                    entry.getKey(), entry.getValue(), samples, maxAttempts, radiusChunks);
            if (m.samples() == 0) {
                unmeasured.add(entry.getKey());
            } else {
                measured.add(m);
            }
            if (progress != null) {
                progress.incrementAndGet();
            }
        }
        return new Census(world.getRegistryKey().getValue().toString(), seed, radiusChunks,
                samples, maxAttempts, measured, unmeasured, skipped,
                (System.nanoTime() - start) / 1_000_000L);
    }

    /** One structure, assembled at up to {@code maxAttempts} scattered chunks. */
    private static Measurement measure(MinecraftServer server, ServerWorld world,
                                       ChunkGenerator generator, NoiseConfig noiseConfig,
                                       long seed, String structureId, Structure structure,
                                       int samples, int maxAttempts, int radiusChunks) {
        List<Integer> spans = new ArrayList<>();
        List<Integer> xs = new ArrayList<>();
        List<Integer> zs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        List<Integer> pieces = new ArrayList<>();
        int attempts = 0;

        for (int a = 0; a < maxAttempts && spans.size() < samples; a++) {
            attempts++;
            ChunkPos pos = scatter(a, maxAttempts, radiusChunks);
            StructureStart built;
            try {
                built = structure.createStructureStart(server.getRegistryManager(), generator,
                        generator.getBiomeSource(), noiseConfig, server.getStructureTemplateManager(),
                        seed, pos, 0, world, ANY_BIOME);
            } catch (RuntimeException | StackOverflowError e) {
                // A structure that throws on an arbitrary position is not ours
                // to fail on; it reports as unmeasured like any other refusal.
                continue;
            }
            if (built == null || !built.hasChildren()) {
                continue;
            }
            BlockBox box = built.getBoundingBox();
            spans.add(Math.max(box.getBlockCountX(), box.getBlockCountZ()));
            xs.add(box.getBlockCountX());
            zs.add(box.getBlockCountZ());
            ys.add(box.getBlockCountY());
            pieces.add(built.getChildren().size());
        }

        String step = stepOf(structure);
        if (spans.isEmpty()) {
            return new Measurement(structureId, step, attempts, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        List<Integer> sorted = new ArrayList<>(spans);
        Collections.sort(sorted);
        return new Measurement(structureId, step, attempts, spans.size(),
                sorted.get(0), median(spans), sorted.get(sorted.size() - 1),
                median(xs), median(zs), median(ys), median(pieces));
    }

    /**
     * The {@code a}-th of {@code n} scattered chunk positions, as a sunflower
     * spiral out to {@code radiusChunks}.
     *
     * <p>Deterministic, so two runs measure the same structure at the same
     * places, and area-uniform (radius grows as the square root of the index)
     * so the samples are not all crowded into the middle. The first position
     * is deliberately away from the origin: spawn terrain is flattened and
     * special-cased, and a structure measured only there is measured on a
     * landform the rest of the world does not have.
     */
    static ChunkPos scatter(int a, int n, int radiusChunks) {
        double angle = a * GOLDEN_ANGLE;
        double unit = Math.sqrt((a + 0.5) / Math.max(1, n));
        double r = radiusChunks * (0.15 + 0.85 * unit);
        return new ChunkPos((int) Math.round(r * Math.cos(angle)),
                (int) Math.round(r * Math.sin(angle)));
    }

    /** The generation step a structure declares, lowercased, or "unknown". */
    private static String stepOf(Structure structure) {
        try {
            return structure.getFeatureGenerationStep().name().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static int median(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** The artefact body: every measurement, and every id that has none. */
    public static String json(Census census) {
        StringBuilder b = new StringBuilder(
                com.customdimensions.command.Artefacts.jsonHeader("structure-sizes"));
        b.append(" \"dimension\": \"").append(census.dimension()).append("\",\n");
        b.append(" \"seed\": ").append(census.seed()).append(",\n");
        b.append(" \"radiusChunks\": ").append(census.radiusChunks()).append(",\n");
        b.append(" \"requestedSamples\": ").append(census.requestedSamples()).append(",\n");
        b.append(" \"maxAttempts\": ").append(census.maxAttempts()).append(",\n");
        b.append(" \"note\": \"Spans are the assembled StructureStart bounding box, biome"
                + " predicate always-true, no chunks generated. medianSpan is the horizontal"
                + " extent in blocks; a footprint radius is half of it.\",\n");
        b.append(" \"measuredCount\": ").append(census.measured().size()).append(",\n");
        b.append(" \"unmeasuredCount\": ").append(census.unmeasured().size()).append(",\n");
        b.append(" \"skippedCount\": ").append(census.skipped().size()).append(",\n");
        b.append(" \"medianSpan\": ").append(census.medianSpan()).append(",\n");
        b.append(" \"millis\": ").append(census.millis()).append(",\n");
        b.append(" \"structures\": {");
        boolean first = true;
        for (Measurement m : census.measured()) {
            b.append(first ? "\n  " : ",\n  ");
            first = false;
            b.append('"').append(m.structureId()).append("\": {\"step\": \"").append(m.step())
                    .append("\", \"samples\": ").append(m.samples())
                    .append(", \"attempts\": ").append(m.attempts())
                    .append(", \"minSpan\": ").append(m.minSpan())
                    .append(", \"medianSpan\": ").append(m.medianSpan())
                    .append(", \"maxSpan\": ").append(m.maxSpan())
                    .append(", \"medianX\": ").append(m.medianX())
                    .append(", \"medianZ\": ").append(m.medianZ())
                    .append(", \"medianY\": ").append(m.medianY())
                    .append(", \"medianPieces\": ").append(m.medianPieces())
                    .append('}');
        }
        b.append(first ? "},\n" : "\n },\n");
        b.append(" \"unmeasured\": [");
        first = true;
        for (String id : census.unmeasured()) {
            b.append(first ? "\n  " : ",\n  ");
            first = false;
            b.append('"').append(id).append('"');
        }
        b.append(first ? "],\n" : "\n ],\n");
        b.append(" \"skipped\": [");
        first = true;
        for (String id : census.skipped()) {
            b.append(first ? "\n  " : ",\n  ");
            first = false;
            b.append('"').append(id).append('"');
        }
        b.append(first ? "]\n}\n" : "\n ]\n}\n");
        return b.toString();
    }

    /** Identifier form of a measurement's id, or null when it will not parse. */
    public static Identifier idOf(Measurement m) {
        return Identifier.tryParse(m.structureId());
    }
}
