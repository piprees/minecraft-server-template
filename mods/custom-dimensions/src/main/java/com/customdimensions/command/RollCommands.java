package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.facts.Json;
import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.Roller;
import com.customdimensions.roll.SeedBank;
import com.customdimensions.score.Frontier;
import com.customdimensions.score.Scorecard;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code /customdim roll}, {@code roll-all}, {@code bank} and {@code winner}
 * — the seed search, its bank, and writing a winner back into config.
 *
 * <p>Every answer is one line plus a path: RCON concatenates feedback with no
 * separator and truncates at a few KB, so nothing here reports a candidate
 * list down the wire (mods/AGENTS.md § diagnostic artefacts).
 */
public final class RollCommands {

    private RollCommands() {
    }

    static int roll(CommandContext<ServerCommandSource> ctx, int count) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);
        DimensionConfig def = resolveDef(dimensionId);
        if (def == null) {
            source.sendError(Text.literal("No configured dimension " + dimensionId));
            return 0;
        }

        String dimension = dimensionId.toString();
        Roller.RollResult result = Roller.rollDimension(server, dimensionId, def, count);
        String inputHash = InputHash.of(def, server);
        List<SeedBank.CandidateSummary> board = SeedBank.leaderboard(inputHash, dimension);
        int rejectedTotal = SeedBank.rejectedSeeds(inputHash, dimension).size();
        final String msg = "roll " + dimensionId + ": " + result.measured() + " measured ("
                + result.scored() + " new scored, " + result.rejected() + " new rejected) -> "
                + leaderboardSummary(board, rejectedTotal) + " -> " + SeedBank.dimensionDir(inputHash, dimension);
        source.sendFeedback(() -> Text.literal(msg), false);
        return result.scored();
    }

    static int rollAll(CommandContext<ServerCommandSource> ctx, int count) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();
        List<DimensionConfig> defs = MultiverseConfig.getInstance().getDimensions();

        int rolled = 0;
        int skipped = 0;
        int scoredTotal = 0;
        int rejectedTotal = 0;
        for (DimensionConfig def : defs) {
            if (!Roller.rollable(def)) {
                skipped++;
                continue;
            }
            Identifier id = def.getDimensionIdentifier();
            try {
                Roller.RollResult result = Roller.rollDimension(server, id, def, count);
                scoredTotal += result.scored();
                rejectedTotal += result.rejected();
                rolled++;
            } catch (RuntimeException e) {
                MultiverseServer.LOGGER.error("Failed to roll {}", id, e);
            }
        }

        // Each dimension's candidates live under its OWN input hash, so there
        // is no single directory to name here — only the root they share.
        Path candidatesRoot = Artefacts.rollingDir().resolve("candidates");
        final String msg = "roll-all: rolled " + rolled + " dimension(s), skipped " + skipped
                + " (superflat/void/skip), " + scoredTotal + " new scored, " + rejectedTotal
                + " new rejected -> " + candidatesRoot + " (one input-hash subdirectory per dimension)";
        source.sendFeedback(() -> Text.literal(msg), false);
        return rolled;
    }

    /**
     * The bank's non-dominated frontier, not a single ranked leaderboard: a
     * percentage collapses onto whichever ceiling a dimension's config
     * produces, so two candidates at the same percentage can be good for
     * unrelated reasons that number cannot carry. The frontier and each
     * member's distinctive strengths go to a file — RCON truncates, and
     * several members' strengths would not fit a one-line answer anyway.
     */
    static int bank(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);
        DimensionConfig def = resolveDef(dimensionId);
        if (def == null) {
            source.sendError(Text.literal("No configured dimension " + dimensionId));
            return 0;
        }
        String dimension = dimensionId.toString();
        String inputHash = InputHash.of(def, source.getServer());
        int candidateTotal = SeedBank.leaderboard(inputHash, dimension).size();
        int rejectedTotal = SeedBank.rejectedSeeds(inputHash, dimension).size();
        List<Frontier.Member> frontier = Frontier.of(SeedBank.scorecards(inputHash, dimension));
        Path path = writeFrontier(dimensionId, dimension, inputHash, frontier);

        final String msg = "bank " + dimensionId + ": " + candidateTotal + " candidate(s), "
                + rejectedTotal + " rejected, frontier=" + frontier.size()
                + (frontier.size() == 1 ? " member" : " members") + " -> " + path;
        source.sendFeedback(() -> Text.literal(msg), false);
        return frontier.size();
    }

    /**
     * Writes a chosen seed into the consumer's committed overlay file at
     * {@link Artefacts#overlayDimensionsDir()}, creating it when absent.
     *
     * <p>Never writes under {@link Artefacts#dir()}: that is the server's staged
     * copy, so a seed written there reaches neither git nor a future roll and is
     * destroyed by the next {@code refresh-config}. Without the overlay mount
     * this refuses and prints the seed for a human to place.
     *
     * <p>A frontier of exactly one candidate has an unambiguous winner and is
     * written automatically. A frontier of several is several genuinely
     * different good seeds, not a ranking with a tie-break rule this command
     * can invent — it refuses and points at the frontier file instead of
     * picking rank 1 off a percentage that no longer means "the best one".
     * {@code explicitSeed} lets a person who has read that file say which one
     * they want; naming a seed not on the frontier is refused too, since a
     * dominated seed is never the right pick.
     */
    static int winner(CommandContext<ServerCommandSource> ctx, Long explicitSeed) {
        ServerCommandSource source = ctx.getSource();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);
        DimensionConfig def = resolveDef(dimensionId);
        if (def == null) {
            source.sendError(Text.literal("No configured dimension " + dimensionId));
            return 0;
        }
        String dimension = dimensionId.toString();
        String inputHash = InputHash.of(def, source.getServer());
        List<Frontier.Member> frontier = Frontier.of(SeedBank.scorecards(inputHash, dimension));
        if (frontier.isEmpty()) {
            source.sendError(Text.literal(
                    "No winner banked for " + dimensionId + " — run /customdim roll first"));
            return 0;
        }

        long winner;
        if (explicitSeed != null) {
            boolean onFrontier = frontier.stream()
                    .anyMatch(m -> m.scorecard().seed() == explicitSeed);
            if (!onFrontier) {
                Path path = writeFrontier(dimensionId, dimension, inputHash, frontier);
                source.sendError(Text.literal("winner " + dimensionId + ": seed=" + explicitSeed
                        + " is not on the frontier — a dominated seed is never the right pick. "
                        + "See " + path));
                return 0;
            }
            winner = explicitSeed;
        } else if (frontier.size() == 1) {
            winner = frontier.get(0).scorecard().seed();
        } else {
            Path path = writeFrontier(dimensionId, dimension, inputHash, frontier);
            source.sendError(Text.literal("winner " + dimensionId + ": refused — the frontier has "
                    + frontier.size() + " non-dominated candidates and no single one is best; "
                    + "name one with /customdim winner " + dimensionId + " <seed> -> " + path));
            return 0;
        }

        String slug = dimensionId.getPath();
        Path overlayDir = Artefacts.overlayDimensionsDir();
        if (!Files.isDirectory(overlayDir)) {
            source.sendError(Text.literal("winner " + dimensionId + ": seed=" + winner
                    + " NOT written — " + overlayDir + " is not mounted. Set "
                    + "\"overrides\": {\"seed\": " + winner + "} in "
                    + "overlay/config/custom-dimensions/dimensions/" + slug + ".json"));
            return 0;
        }
        Path target = overlayDir.resolve(slug + ".json");

        try {
            JsonObject root = Files.isRegularFile(target)
                    ? JsonParser.parseString(Files.readString(target)).getAsJsonObject()
                    : new JsonObject();
            // The overlay's deep-merge form: an "overrides" block patches the
            // platform default, where a bare object would replace it wholesale.
            JsonObject overrides = root.has("overrides") && root.get("overrides").isJsonObject()
                    ? root.getAsJsonObject("overrides") : new JsonObject();
            overrides.addProperty("seed", winner);
            root.add("overrides", overrides);
            String out = new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
            Artefacts.write(target, out);
            final String msg = "winner " + dimensionId + ": seed=" + winner + " -> " + target;
            source.sendFeedback(() -> Text.literal(msg), false);
            return 1;
        } catch (IOException | RuntimeException e) {
            MultiverseServer.LOGGER.error("Failed to write winner seed for {}", dimensionId, e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Draws one candidate to a PNG beside its JSON — {@code lowres} (the
     * default) or {@code highres}. Lowres reads the candidate's own
     * persisted grid and needs no timing of its own; highres samples a fresh
     * one and reports the measured per-column cost that decided its size.
     * Either way, nothing about how the grid was obtained is silent.
     */
    static int render(CommandContext<ServerCommandSource> ctx, long seed, String resolutionArg) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);
        DimensionConfig def = resolveDef(dimensionId);
        if (def == null) {
            source.sendError(Text.literal("No configured dimension " + dimensionId));
            return 0;
        }
        CandidateRender.Resolution resolution;
        try {
            resolution = CandidateRender.Resolution.valueOf(resolutionArg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source.sendError(Text.literal(
                    "Unknown resolution '" + resolutionArg + "' — use lowres or highres"));
            return 0;
        }
        // Highres can run past a minute on the calling thread; refuse rather
        // than freeze a server whose tick watchdog could act on that.
        if (resolution == CandidateRender.Resolution.HIGHRES) {
            long watchdogMs = CandidateRender.watchdogTimeoutMillis(server);
            if (watchdogMs > 0) {
                source.sendError(Text.literal("render " + dimensionId + " seed=" + seed
                        + " highres: refused — this server's tick watchdog is armed at "
                        + watchdogMs + "ms and a highres render can run past a minute on the "
                        + "main thread. Set MAX_TICK_TIME=-1 (this platform's own default) or "
                        + "render lowres instead."));
                return 0;
            }
        }
        String dimension = dimensionId.toString();
        String inputHash = InputHash.of(def, server);
        Path path = SeedBank.candidateImagePath(inputHash, dimension, seed, resolution);
        try {
            CandidateRender.RenderResult result =
                    CandidateRender.render(server, dimensionId, def, seed, resolution, path);
            // Lowres has no per-column cost to report — it read a candidate's
            // own persisted grid rather than sampling one, and saying
            // "0.000ms/column" would read as a measurement that never happened.
            String costPart = resolution == CandidateRender.Resolution.LOWRES
                    ? "from the candidate's persisted grid"
                    : String.format(Locale.ROOT, "%.3fms/column, %d sampled",
                            result.perColumnNanos() / 1_000_000.0, result.sampled());
            final String msg = "render " + dimensionId + " seed=" + seed + " "
                    + resolutionArg.toLowerCase(Locale.ROOT) + ": " + result.side() + "x" + result.side()
                    + " grid, step=" + result.step() + " blocks, " + costPart + ", "
                    + result.structureMarkers() + " structure marker(s), "
                    + String.format(Locale.ROOT, "%.3fs total", result.renderNanos() / 1_000_000_000.0)
                    + " -> " + result.path();
            source.sendFeedback(() -> Text.literal(msg), false);
            return 1;
        } catch (IOException | RuntimeException e) {
            MultiverseServer.LOGGER.error("Failed to render {} seed {}", dimensionId, seed, e);
            source.sendError(Text.literal("Render failed: " + e.getMessage()));
            return 0;
        }
    }

    private static DimensionConfig resolveDef(Identifier dimensionId) {
        DimensionConfig def = MultiverseConfig.getInstance().getDimension(dimensionId.getPath());
        return def != null ? def : MultiverseConfig.getInstance().getBaseWorld(dimensionId.toString());
    }

    private static String leaderboardSummary(List<SeedBank.CandidateSummary> board, int rejectedTotal) {
        String winnerPart = board.isEmpty() ? "no winner yet"
                : String.format(Locale.ROOT, "winner=%d (%.1f%%)",
                        board.get(0).seed(), board.get(0).percentage());
        return board.size() + " candidate(s), " + rejectedTotal + " rejected, " + winnerPart;
    }

    /** Writes the frontier artefact and returns its path, logging rather than failing the caller on an IO error. */
    private static Path writeFrontier(Identifier dimensionId, String dimension, String inputHash,
                                      List<Frontier.Member> frontier) {
        Path path = SeedBank.frontierPath(inputHash, dimension);
        try {
            Artefacts.write(path, frontierJson(dimension, frontier));
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write frontier for {}", dimensionId, e);
        }
        return path;
    }

    /**
     * The frontier artefact's body: every non-dominated candidate with its
     * percentage (for a human's sense of scale) and its distinctive
     * strengths (for the choice a percentage cannot make). Pure — no Fabric
     * API — so the shape is pinned against hand-built members with no server.
     */
    static String frontierJson(String dimension, List<Frontier.Member> frontier) {
        StringBuilder b = new StringBuilder(Artefacts.jsonHeader("seed-frontier"));
        b.append(" \"dimension\": ").append(Json.quote(dimension)).append(",\n");
        b.append(" \"members\": [");
        for (int i = 0; i < frontier.size(); i++) {
            Frontier.Member m = frontier.get(i);
            Scorecard card = m.scorecard();
            Double pct = card.percentage();
            b.append(i > 0 ? ",\n  " : "\n  ");
            b.append("{\"seed\": ").append(card.seed());
            b.append(", \"percentage\": ").append(pct == null ? "null" : Json.number(pct));
            b.append(", \"achieved\": ").append(Json.number(card.achieved()));
            b.append(", \"ceiling\": ").append(Json.number(card.ceiling()));
            b.append(", \"strengths\": [");
            for (int j = 0; j < m.strengths().size(); j++) {
                b.append(j > 0 ? ", " : "").append(Json.quote(m.strengths().get(j)));
            }
            b.append("]}");
        }
        b.append(frontier.isEmpty() ? "]\n}\n" : "\n ]\n}\n");
        return b.toString();
    }
}
