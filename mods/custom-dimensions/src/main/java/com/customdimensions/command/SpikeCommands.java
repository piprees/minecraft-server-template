package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.MultiverseConfig;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Phase 1 spike commands. Three questions, three artefacts, no answers down
 * the wire beyond a summary and a path.
 *
 * <pre>
 *   /customdim spike-sample  &lt;dim&gt; &lt;seed&gt; &lt;x&gt; &lt;z&gt;        one headless column
 *   /customdim spike-compare &lt;dim&gt; &lt;seed&gt; &lt;count&gt; &lt;span&gt;  headless vs the live world
 *   /customdim spike-bench   &lt;dim&gt; &lt;seeds&gt;                throughput, worlds, heap
 * </pre>
 *
 * {@code spike-compare} writes both sides' raw values per column alongside its
 * own mismatch list, so a checker can recompute the verdict instead of taking
 * the command's word for it.
 */
public final class SpikeCommands {

    private SpikeCommands() {
    }

    /**
     * Brigadier's identifier argument defaults a bare name to
     * {@code minecraft}, and the managed namespace is what a dimension slug
     * actually lives under — the same fallback {@code resolveWorld} applies,
     * kept here because these commands must resolve a dimension that has no
     * world at all.
     */
    static Identifier resolveId(CommandContext<ServerCommandSource> ctx) {
        Identifier raw = IdentifierArgumentType.getIdentifier(ctx, "dimension");
        if (!"minecraft".equals(raw.getNamespace())) {
            return raw;
        }
        var def = MultiverseConfig.getInstance().getDimension(raw.getPath());
        if (def != null) {
            return def.getDimensionIdentifier();
        }
        var base = MultiverseConfig.getInstance().getWorld(raw.getPath());
        if (base != null) {
            return base.getDimensionIdentifier();
        }
        return raw;
    }

    private static String filePart(Identifier id) {
        return id.getNamespace() + "__" + id.getPath();
    }

    // -------------------------------------------------------------------- lint

    /**
     * Validate every dimension config against the live registries and write
     * {@code lint/report.json}.
     *
     * <p>Seconds, not minutes: no seeds, no worlds, no generation. The command
     * returns the ERROR count, so a CI caller can branch on it, and the
     * summary line names the top checks — a bare total tells a human nothing
     * about what to go and fix.
     */
    static int lint(CommandContext<ServerCommandSource> ctx, String only) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();

        long started = System.nanoTime();
        java.util.List<DimensionLint.Finding> findings = DimensionLint.lint(server, only);
        long elapsed = (System.nanoTime() - started) / 1_000_000L;

        int checked = only != null ? 1
                : MultiverseConfig.getInstance().getDimensions().size();
        int errors = 0;
        java.util.TreeMap<String, Integer> byCheck = new java.util.TreeMap<>();
        for (DimensionLint.Finding f : findings) {
            if (DimensionLint.ERROR.equals(f.severity())) {
                errors++;
            }
            byCheck.merge(f.check(), 1, Integer::sum);
        }

        try {
            Path out = Artefacts.dir("lint").resolve("report.json");
            Artefacts.write(out, DimensionLint.toJson(findings, checked, elapsed));
            StringBuilder top = new StringBuilder();
            byCheck.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(4)
                    .forEach(e -> top.append(' ')
                            .append(e.getKey()).append('=').append(e.getValue()));
            final String msg = "lint: " + checked + " dimension(s), "
                    + findings.size() + " finding(s), " + errors + " error(s) in "
                    + elapsed + "ms |" + (top.length() == 0 ? " clean" : top)
                    + " -> " + out;
            source.sendFeedback(() -> Text.literal(msg), false);
            return errors;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write lint report", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    // ------------------------------------------------------------ spike-sample

    static int sample(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Identifier dimensionId = resolveId(ctx);
        long seed = LongArgumentType.getLong(ctx, "seed");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");

        int worldsBefore = SpikeSampler.worldCount(server);
        SpikeSampler.Rig rig = SpikeSampler.headless(server, dimensionId, seed);
        SpikeSampler.logBuildFailure(dimensionId, rig);
        SpikeSampler.Sample s = SpikeSampler.sample(rig, x, z);
        int worldsAfter = SpikeSampler.worldCount(server);

        StringBuilder json = new StringBuilder(Artefacts.jsonHeader("spike-sample"));
        json.append(" \"schemaVersion\": 1,\n");
        json.append(" \"dimension\": \"").append(dimensionId).append("\",\n");
        json.append(" \"seed\": ").append(seed).append(",\n");
        json.append(" \"build\": ").append(buildJson(rig)).append(",\n");
        json.append(" \"worldsBefore\": ").append(worldsBefore).append(",\n");
        json.append(" \"worldsAfter\": ").append(worldsAfter).append(",\n");
        json.append(" \"sample\": ");
        SpikeSampler.appendSample(json, s);
        json.append("\n}\n");

        try {
            Path out = Artefacts.dir("spike")
                    .resolve("sample__" + filePart(dimensionId) + "__" + seed + ".json");
            Artefacts.write(out, json.toString());
            final String msg = "spike-sample " + dimensionId + " seed=" + seed
                    + " (" + x + ", " + z + "): "
                    + (rig.ok() ? "biome=" + s.biome() + " height=" + s.surfaceHeight()
                                : "BUILD FAILED: " + rig.error())
                    + " -> " + out;
            source.sendFeedback(() -> Text.literal(msg), false);
            return rig.ok() ? 1 : 0;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write spike sample", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    // ----------------------------------------------------------- spike-compare

    static int compare(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Identifier dimensionId = resolveId(ctx);
        long seed = LongArgumentType.getLong(ctx, "seed");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int span = IntegerArgumentType.getInteger(ctx, "span");

        ServerWorld world = SpikeSampler.loadedWorld(server, dimensionId);
        if (world == null) {
            source.sendError(Text.literal(
                    "spike-compare needs a LOADED world as the oracle: "
                    + dimensionId + " is not loaded (use /customdim load first)"));
            return 0;
        }

        int worldsBefore = SpikeSampler.worldCount(server);
        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);
        SpikeSampler.Rig headless = SpikeSampler.forSeed(server, base, seed);
        SpikeSampler.logBuildFailure(dimensionId, headless);
        SpikeSampler.Rig climate = SpikeSampler.forSeedClimate(server, base, seed);
        SpikeSampler.Rig live = SpikeSampler.live(world, seed);
        boolean liveNoiseConfig = SpikeSampler.usesLiveNoiseConfig(world, seed);

        StringBuilder rows = new StringBuilder();
        StringBuilder mismatches = new StringBuilder();
        int mismatchCount = 0;
        int climateMismatchCount = 0;
        for (int i = 0; i < count; i++) {
            int[] p = SpikeSampler.probe(i, span);
            SpikeSampler.Sample h = SpikeSampler.sample(headless, p[0], p[1]);
            SpikeSampler.Sample l = SpikeSampler.sample(live, p[0], p[1]);
            SpikeSampler.Sample c = SpikeSampler.sample(climate, p[0], p[1]);
            if (i > 0) {
                rows.append(",\n  ");
            }
            rows.append("{\"headless\": ");
            SpikeSampler.appendSample(rows, h);
            rows.append(", \"live\": ");
            SpikeSampler.appendSample(rows, l);
            rows.append(", \"climateOnly\": ");
            SpikeSampler.appendSample(rows, c);
            rows.append('}');
            if (!h.matches(l)) {
                if (mismatchCount > 0) {
                    mismatches.append(", ");
                }
                mismatches.append('[').append(p[0]).append(", ").append(p[1]).append(']');
                mismatchCount++;
            }
            // The climate-only rig answers biome and climate, never height —
            // so it is compared on exactly those, and its absent height is a
            // stated fact rather than a silent omission.
            if (!java.util.Objects.equals(h.biome(), c.biome())
                    || !java.util.Arrays.equals(h.climate(), c.climate())) {
                climateMismatchCount++;
            }
        }
        int worldsAfter = SpikeSampler.worldCount(server);

        StringBuilder json = new StringBuilder(Artefacts.jsonHeader("spike-compare"));
        json.append(" \"schemaVersion\": 2,\n");
        json.append(" \"dimension\": \"").append(dimensionId).append("\",\n");
        json.append(" \"seed\": ").append(seed).append(",\n");
        json.append(" \"worldSeed\": ").append(world.getSeed()).append(",\n");
        json.append(" \"liveNoiseConfig\": ").append(liveNoiseConfig).append(",\n");
        json.append(" \"span\": ").append(span).append(",\n");
        json.append(" \"headlessBuild\": ").append(buildJson(headless)).append(",\n");
        json.append(" \"liveGenerator\": \"")
                .append(live.generator().getClass().getName()).append("\",\n");
        json.append(" \"liveBiomeSource\": \"")
                .append(live.generator().getBiomeSource().getClass().getName()).append("\",\n");
        json.append(" \"worldsBefore\": ").append(worldsBefore).append(",\n");
        json.append(" \"worldsAfter\": ").append(worldsAfter).append(",\n");
        json.append(" \"count\": ").append(count).append(",\n");
        json.append(" \"mismatchCount\": ").append(mismatchCount).append(",\n");
        json.append(" \"mismatchAt\": [").append(mismatches).append("],\n");
        json.append(" \"climateOnlyMismatchCount\": ")
                .append(climateMismatchCount).append(",\n");
        json.append(" \"samples\": [\n  ").append(rows).append("\n ]\n}\n");

        try {
            Path out = Artefacts.dir("spike")
                    .resolve("compare__" + filePart(dimensionId) + "__" + seed + ".json");
            Artefacts.write(out, json.toString());
            final String msg = "spike-compare " + dimensionId + " seed=" + seed + ": "
                    + count + " columns, " + mismatchCount + " mismatches, "
                    + climateMismatchCount + " climate-only mismatches, worlds "
                    + worldsBefore + "->" + worldsAfter + " -> " + out;
            source.sendFeedback(() -> Text.literal(msg), false);
            return mismatchCount == 0 ? 1 : 0;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write spike comparison", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    // ------------------------------------------------------------- spike-bench

    /**
     * Throughput, world count and heap across N distinct seeds, single
     * threaded. The measured unit is the whole per-seed cost the search stage
     * pays: build the rig for that seed, then one spawn-column biome check.
     * Timing only the biome check would report a number no caller can have.
     */
    static int bench(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Identifier dimensionId = resolveId(ctx);
        int seeds = IntegerArgumentType.getInteger(ctx, "seeds");

        long baseStarted = System.nanoTime();
        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);
        long baseBuildNanos = System.nanoTime() - baseStarted;
        if (!base.ok()) {
            source.sendError(Text.literal("spike-bench: " + base.error()));
            return 0;
        }

        int worldsBefore = SpikeSampler.worldCount(server);
        int dimensionsBefore = registrySize(server, net.minecraft.registry.RegistryKeys.DIMENSION);
        int dimensionTypesBefore =
                registrySize(server, net.minecraft.registry.RegistryKeys.DIMENSION_TYPE);
        Runtime runtime = Runtime.getRuntime();

        // Warmup: the first few hundred builds are JIT, not steady state.
        int warmup = Math.min(200, seeds);
        for (int i = 0; i < warmup; i++) {
            SpikeSampler.spawnBiome(
                    SpikeSampler.forSeedClimate(server, base, 900000L + i), 0, 0);
        }

        // The climate-only path is what a search actually runs, so it is timed
        // first and separately. The full-router number below is the price of
        // the same question asked through the whole terrain pipeline — the two
        // together are the argument for the split, and neither alone is.
        long climateBuildNanos = 0;
        long climateCheckNanos = 0;
        // A weak reference to one loop-created rig. If any per-seed object is
        // retained by something global, this referent survives a collection —
        // which is what "no per-seed leak" actually means. A heap delta cannot
        // say it: on a live server the number moves for a dozen other reasons.
        java.lang.ref.WeakReference<Object> canary = null;
        long climateStarted = System.nanoTime();
        for (int i = 0; i < seeds; i++) {
            long t0 = System.nanoTime();
            SpikeSampler.Rig rig = SpikeSampler.forSeedClimate(server, base, 2000000L + i);
            long t1 = System.nanoTime();
            SpikeSampler.spawnBiome(rig, 0, 0);
            climateBuildNanos += t1 - t0;
            climateCheckNanos += System.nanoTime() - t1;
            if (i == 0) {
                canary = new java.lang.ref.WeakReference<>(rig.noiseConfig());
            }
        }
        long climateNanos = System.nanoTime() - climateStarted;
        double climatePerSecond = climateNanos > 0
                ? seeds / (climateNanos / 1_000_000_000.0) : 0.0;

        // Control: an all-zero router. The floor under any further stripping.
        long emptyNanos = 0;
        int climateNoises = 0;
        if (base.generator() instanceof net.minecraft.world.gen.chunk.NoiseChunkGenerator ng) {
            var full = ng.getSettings().value();
            climateNoises = SpikeSampler.climateNoiseCount(base.climateSettings());
            var empty = SpikeSampler.emptyRouter(full);
            var lookup = server.getRegistryManager()
                    .get(net.minecraft.registry.RegistryKeys.NOISE_PARAMETERS)
                    .getReadOnlyWrapper();
            int controlRuns = Math.min(200, seeds);
            long emptyStarted = System.nanoTime();
            for (int i = 0; i < controlRuns; i++) {
                net.minecraft.world.gen.noise.NoiseConfig.create(
                        empty, lookup, 3000000L + i);
            }
            emptyNanos = (System.nanoTime() - emptyStarted) / Math.max(1, controlRuns);
        }

        long heapBefore = settledHeap(runtime);
        long heapMid = 0;
        long noiseConfigNanos = 0;
        long biomeCheckNanos = 0;
        long started = System.nanoTime();
        int nonNull = 0;
        java.util.TreeMap<String, Integer> biomeHistogram = new java.util.TreeMap<>();
        for (int i = 0; i < seeds; i++) {
            long t0 = System.nanoTime();
            SpikeSampler.Rig rig = SpikeSampler.forSeed(server, base, 1000000L + i);
            long t1 = System.nanoTime();
            String biome = SpikeSampler.spawnBiome(rig, 0, 0);
            long t2 = System.nanoTime();
            noiseConfigNanos += t1 - t0;
            biomeCheckNanos += t2 - t1;
            if (biome != null) {
                nonNull++;
                biomeHistogram.merge(biome, 1, Integer::sum);
            }
            // Halfway heap, so a leak reads as a slope rather than as one
            // number that could be anything a live JVM happened to be doing.
            if (i == seeds / 2) {
                heapMid = settledHeap(runtime);
            }
        }
        long elapsedNanos = System.nanoTime() - started;

        long heapAfter = settledHeap(runtime);
        int worldsAfter = SpikeSampler.worldCount(server);
        int canaryRounds = roundsToCollect(canary, 64);
        boolean canaryCollected = canaryRounds >= 0;
        int dimensionsAfter = registrySize(server, net.minecraft.registry.RegistryKeys.DIMENSION);
        int dimensionTypesAfter =
                registrySize(server, net.minecraft.registry.RegistryKeys.DIMENSION_TYPE);

        double seconds = elapsedNanos / 1_000_000_000.0;
        double perSecond = seconds > 0 ? seeds / seconds : 0.0;

        StringBuilder json = new StringBuilder(Artefacts.jsonHeader("spike-bench"));
        json.append(" \"schemaVersion\": 6,\n");
        json.append(" \"dimension\": \"").append(dimensionId).append("\",\n");
        json.append(" \"seeds\": ").append(seeds).append(",\n");
        json.append(" \"warmupSeeds\": ").append(warmup).append(",\n");
        json.append(" \"generator\": \"")
                .append(base.generator().getClass().getName()).append("\",\n");
        json.append(" \"baseBuildNanos\": ").append(baseBuildNanos).append(",\n");
        json.append(" \"climateOnlyNanos\": ").append(climateNanos).append(",\n");
        json.append(" \"climateOnlySeedsPerSecond\": ")
                .append(climatePerSecond).append(",\n");
        json.append(" \"climateOnlyBuildNanos\": ").append(climateBuildNanos).append(",\n");
        json.append(" \"climateOnlyCheckNanos\": ").append(climateCheckNanos).append(",\n");
        json.append(" \"emptyRouterNanosPerSeed\": ").append(emptyNanos).append(",\n");
        json.append(" \"climateNoiseCount\": ").append(climateNoises).append(",\n");
        json.append(" \"canaryCollected\": ").append(canaryCollected).append(",\n");
        json.append(" \"canaryCollectRounds\": ").append(canaryRounds).append(",\n");
        json.append(" \"dimensionRegistryBefore\": ").append(dimensionsBefore).append(",\n");
        json.append(" \"dimensionRegistryAfter\": ").append(dimensionsAfter).append(",\n");
        json.append(" \"dimensionTypeRegistryBefore\": ")
                .append(dimensionTypesBefore).append(",\n");
        json.append(" \"dimensionTypeRegistryAfter\": ")
                .append(dimensionTypesAfter).append(",\n");
        json.append(" \"elapsedNanos\": ").append(elapsedNanos).append(",\n");
        json.append(" \"noiseConfigNanos\": ").append(noiseConfigNanos).append(",\n");
        json.append(" \"biomeCheckNanos\": ").append(biomeCheckNanos).append(",\n");
        json.append(" \"seedsPerSecond\": ").append(perSecond).append(",\n");
        json.append(" \"answeredBiome\": ").append(nonNull).append(",\n");
        json.append(" \"worldsBefore\": ").append(worldsBefore).append(",\n");
        json.append(" \"worldsAfter\": ").append(worldsAfter).append(",\n");
        json.append(" \"heapBeforeBytes\": ").append(heapBefore).append(",\n");
        json.append(" \"heapMidBytes\": ").append(heapMid).append(",\n");
        json.append(" \"heapAfterBytes\": ").append(heapAfter).append(",\n");
        json.append(" \"availableProcessors\": ")
                .append(runtime.availableProcessors()).append(",\n");
        // A histogram that collapses to one biome across a thousand seeds means
        // the seed never reached the sampler — the exact failure this gate is
        // meant to catch, and invisible from a throughput number alone.
        json.append(" \"distinctBiomes\": ").append(biomeHistogram.size()).append(",\n");
        json.append(" \"biomeHistogram\": {");
        int n = 0;
        for (var e : biomeHistogram.entrySet()) {
            if (n++ > 0) {
                json.append(", ");
            }
            json.append('"').append(e.getKey()).append("\": ").append(e.getValue());
        }
        json.append("}\n}\n");

        try {
            Path out = Artefacts.dir("spike")
                    .resolve("bench__" + filePart(dimensionId) + ".json");
            Artefacts.write(out, json.toString());
            final String msg = String.format(
                    "spike-bench %s: climate-only %.1f seeds/s, full router %.1f seeds/s "
                    + "(noiseConfig %.1f%%), base build %.1fms, %d distinct biomes, "
                    + "worlds %d->%d, heap %+d bytes -> %s",
                    dimensionId, climatePerSecond, perSecond,
                    100.0 * noiseConfigNanos / Math.max(1, elapsedNanos),
                    baseBuildNanos / 1_000_000.0, biomeHistogram.size(),
                    worldsBefore, worldsAfter, heapAfter - heapBefore, out);
            source.sendFeedback(() -> Text.literal(msg), false);
            return (int) perSecond;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write spike bench", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    private static <T> int registrySize(
            MinecraftServer server, net.minecraft.registry.RegistryKey<
                    net.minecraft.registry.Registry<T>> key) {
        return server.getCombinedDynamicRegistries()
                .getCombinedRegistryManager().get(key).size();
    }

    /**
     * Allocation rounds until a weakly-held object is collected, or -1 if it
     * survives all of them.
     *
     * <p>{@code System.gc()} cannot answer this here: the server runs with
     * {@code -XX:+DisableExplicitGC}, so it is a no-op. A collect-then-look
     * measurement therefore reports every short run as a leak and every long
     * one as clean, purely from how much collecting the JVM happened to do on
     * its own — which is what the first version of this check did. Allocating
     * until the referent clears asks the collector directly, and the round
     * count is itself the evidence: a young-gen object clears in single
     * figures, and only something genuinely reachable survives all of them.
     */
    private static int roundsToCollect(java.lang.ref.WeakReference<?> ref, int maxRounds) {
        if (ref == null) {
            return -1;
        }
        for (int i = 0; i < maxRounds; i++) {
            if (ref.get() == null) {
                return i;
            }
            // Dropped immediately — the allocation is the point, and it is
            // sized past the TLAB so it reaches a G1 region.
            byte[] churn = new byte[4 * 1024 * 1024];
            churn[0] = (byte) i;
        }
        return ref.get() == null ? maxRounds : -1;
    }

    /** Raw heap in use. Reported as context; never gated on (see above). */
    private static long settledHeap(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String buildJson(SpikeSampler.Rig rig) {
        StringBuilder b = new StringBuilder("{\"ok\": ").append(rig.ok());
        b.append(", \"error\": ")
                .append(rig.error() == null ? "null" : '"' + rig.error().replace("\"", "'") + '"');
        b.append(", \"generator\": ").append(rig.generator() == null ? "null"
                : '"' + rig.generator().getClass().getName() + '"');
        b.append(", \"biomeSource\": ").append(rig.generator() == null ? "null"
                : '"' + rig.generator().getBiomeSource().getClass().getName() + '"');
        b.append(", \"noiseConfig\": ").append(rig.noiseConfig() != null);
        b.append(", \"biomeSourceAcceptsWithSeed\": ")
                .append(rig.biomeSourceAcceptsWithSeed());
        b.append('}');
        return b.toString();
    }
}
