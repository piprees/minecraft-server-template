package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.Roller;
import com.customdimensions.roll.SeedBank;
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
        List<SeedBank.CandidateSummary> board = SeedBank.leaderboard(inputHash, dimension);
        int rejectedTotal = SeedBank.rejectedSeeds(inputHash, dimension).size();
        final String msg = "bank " + dimensionId + ": " + leaderboardSummary(board, rejectedTotal)
                + " -> " + SeedBank.dimensionDir(inputHash, dimension);
        source.sendFeedback(() -> Text.literal(msg), false);
        return board.size();
    }

    /**
     * Writes the bank's winner into the consumer's committed overlay file at
     * {@link Artefacts#overlayDimensionsDir()}, creating it when absent.
     *
     * <p>Never writes under {@link Artefacts#dir()}: that is the server's staged
     * copy, so a seed written there reaches neither git nor a future roll and is
     * destroyed by the next {@code refresh-config}. Without the overlay mount
     * this refuses and prints the seed for a human to place.
     */
    static int winner(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);
        DimensionConfig def = resolveDef(dimensionId);
        if (def == null) {
            source.sendError(Text.literal("No configured dimension " + dimensionId));
            return 0;
        }
        String inputHash = InputHash.of(def, source.getServer());
        List<SeedBank.CandidateSummary> board = SeedBank.leaderboard(inputHash, dimensionId.toString());
        if (board.isEmpty()) {
            source.sendError(Text.literal(
                    "No winner banked for " + dimensionId + " — run /customdim roll first"));
            return 0;
        }
        long winner = board.get(0).seed();

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
     * default) or {@code highres}. The grid, step and measured per-column
     * cost go in the answer line: nothing about the resolution chosen is
     * silent.
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
            final String msg = "render " + dimensionId + " seed=" + seed + " "
                    + resolutionArg.toLowerCase(Locale.ROOT) + ": " + result.side() + "x" + result.side()
                    + " grid, step=" + result.step() + " blocks, "
                    + String.format(Locale.ROOT, "%.3fms/column", result.perColumnNanos() / 1_000_000.0)
                    + ", " + result.sampled() + " sampled, " + result.structureMarkers() + " structure marker(s), "
                    + String.format(Locale.ROOT, "%.1fs total", result.renderNanos() / 1_000_000_000.0)
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
}
