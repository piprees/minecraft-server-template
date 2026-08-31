package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
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
 * {@code spike-compare}: headless generator maths vs the live world for one
 * seed. Its mismatch list is the assertion — reported in the command's own
 * output, not a file. {@code lint}: config validated against the live
 * registries, one hash-scoped file per dimension checked.
 *
 * <pre>
 *   /customdim spike-compare &lt;dim&gt; &lt;seed&gt; &lt;count&gt; &lt;span&gt;  headless vs the live world
 *   /customdim lint [dimension]                          config validation
 * </pre>
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
        var def = MultiverseConfig.getInstance().getDimensionBySlug(raw.getPath());
        return def != null ? def.getDimensionIdentifier() : raw;
    }

    // -------------------------------------------------------------------- lint

    /**
     * Validate every dimension config against the live registries, one
     * hash-scoped file per dimension checked (lint is about a dimension's
     * config, not a seed, so no candidate/event tree sits above it).
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

        java.util.List<DimensionConfig> targets = new java.util.ArrayList<>();
        for (DimensionConfig def : DimensionLint.targets()) {
            if (only == null || only.equals(def.getName())) {
                targets.add(def);
            }
        }
        java.util.Map<String, java.util.List<DimensionLint.Finding>> byDimension =
                new java.util.HashMap<>();
        for (DimensionLint.Finding f : findings) {
            byDimension.computeIfAbsent(f.dimension(), k -> new java.util.ArrayList<>()).add(f);
        }

        int errors = 0;
        java.util.TreeMap<String, Integer> byCheck = new java.util.TreeMap<>();
        for (DimensionLint.Finding f : findings) {
            if (DimensionLint.ERROR.equals(f.severity())) {
                errors++;
            }
            byCheck.merge(f.check(), 1, Integer::sum);
        }

        int written = 0;
        try {
            for (DimensionConfig def : targets) {
                String hash = InputHash.of(def, server);
                java.util.List<DimensionLint.Finding> own =
                        byDimension.getOrDefault(def.getName(), java.util.List.of());
                Path out = Artefacts.rollingDir().resolve("lint").resolve(hash + ".json");
                // elapsed is the whole pass's cost, not this one dimension's
                // share — findings are gathered together, never per-dimension.
                Artefacts.write(out, DimensionLint.toJson(own, 1, elapsed));
                written++;
            }
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write lint report", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }

        StringBuilder top = new StringBuilder();
        byCheck.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(4)
                .forEach(e -> top.append(' ')
                        .append(e.getKey()).append('=').append(e.getValue()));
        final String msg = "lint: " + targets.size() + " dimension(s), "
                + findings.size() + " finding(s), " + errors + " error(s) in "
                + elapsed + "ms |" + (top.length() == 0 ? " clean" : top)
                + " -> " + written + " file(s) under " + Artefacts.rollingDir().resolve("lint");
        source.sendFeedback(() -> Text.literal(msg), false);
        return errors;
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

        StringBuilder mismatches = new StringBuilder();
        int mismatchCount = 0;
        int climateMismatchCount = 0;
        for (int i = 0; i < count; i++) {
            int[] p = SpikeSampler.probe(i, span, seed);
            SpikeSampler.Sample h = SpikeSampler.sample(headless, p[0], p[1]);
            SpikeSampler.Sample l = SpikeSampler.sample(live, p[0], p[1]);
            SpikeSampler.Sample c = SpikeSampler.sample(climate, p[0], p[1]);
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

        // The zero-tolerance gate lives here, not in a file a checker reads
        // afterwards — sendError so a mismatch reads as a command failure a
        // human or CI caller cannot mistake for a clean run. mismatchAt is
        // capped: printing every one of up to `count` pairs inline is the
        // RCON-overflow failure mode Artefacts exists to avoid elsewhere,
        // and a handful of coordinates is already enough to go reproduce one.
        final String msg = "spike-compare " + dimensionId + " seed=" + seed + ": "
                + count + " columns, " + mismatchCount + " mismatches, "
                + climateMismatchCount + " climate-only mismatches, worlds "
                + worldsBefore + "->" + worldsAfter
                + (mismatchCount > 0 ? " | mismatchAt " + capped(mismatches, mismatchCount) : "");
        if (mismatchCount > 0) {
            source.sendError(Text.literal(
                    msg + " -- HEADLESS DIVERGED FROM THE LIVE WORLD AT ZERO TOLERANCE"));
        } else {
            source.sendFeedback(() -> Text.literal(msg), false);
        }
        return mismatchCount == 0 ? 1 : 0;
    }

    /** The mismatch-coordinate list, capped so RCON never sees more than a screenful. */
    private static String capped(StringBuilder mismatches, int mismatchCount) {
        if (mismatchCount <= 20) {
            return "[" + mismatches + "]";
        }
        String all = mismatches.toString();
        int cut = all.length();
        for (int seen = 0, i = 0; i < all.length(); i++) {
            if (all.charAt(i) == ']' && ++seen == 20) {
                cut = i + 1;
                break;
            }
        }
        return "[" + all.substring(0, cut) + ", ... +" + (mismatchCount - 20) + " more]";
    }
}
