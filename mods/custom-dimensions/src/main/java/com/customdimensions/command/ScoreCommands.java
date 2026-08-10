package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.facts.FactsEngine;
import com.customdimensions.facts.SeedFacts;
import com.customdimensions.facts.SeedFactsCodec;
import com.customdimensions.score.Criteria;
import com.customdimensions.score.Scorecard;
import com.customdimensions.score.Scorer;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code /customdim score <dim> <seed>} — measure, then judge, then say which.
 *
 * <p>Writes both artefacts: the facts it measured and the scorecard it derived.
 * Keeping them separate on disk is the point — a scoring change is re-runnable
 * against banked facts without re-measuring, and a disagreement about a score
 * can be settled by reading the facts it cites.
 */
public final class ScoreCommands {

    private ScoreCommands() {
    }

    static int score(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);
        long seed = LongArgumentType.getLong(ctx, "seed");

        DimensionConfig def = MultiverseConfig.getInstance()
                .getDimension(dimensionId.getPath());
        if (def == null) {
            def = MultiverseConfig.getInstance().getBaseWorld(dimensionId.toString());
        }
        if (def == null) {
            source.sendError(Text.literal("No configured dimension " + dimensionId));
            return 0;
        }

        String part = dimensionId.getNamespace() + "__" + dimensionId.getPath()
                + "__" + seed + ".json";
        Path factsPath = Artefacts.dir("facts").resolve(part);

        SeedFacts banked = reusableFacts(factsPath, def, dimensionId, seed);
        SeedFacts facts = banked != null ? banked
                : FactsEngine.measure(source.getServer(), dimensionId, seed);
        Scorecard card = Scorer.score(facts, def, Criteria.all());

        try {
            Artefacts.write(factsPath, facts.toJson());
            Path out = Artefacts.dir("scores").resolve(part);
            Artefacts.write(out, card.toJson());
            Double pct = card.percentage();
            final String msg = String.format(Locale.ROOT,
                    "score %s seed=%d: %s%s (%.1f/%.1f)%s -> %s",
                    dimensionId, seed, card.verdict(),
                    pct == null ? "" : String.format(Locale.ROOT, " %.1f%%", pct),
                    card.achieved(), card.ceiling(),
                    banked != null ? " [banked facts]" : "", out);
            source.sendFeedback(() -> Text.literal(msg), false);
            return pct == null ? 0 : (int) Math.round(pct);
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write scorecard", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * A banked facts record for this exact (dimension, seed, config, release),
     * or null after deleting whatever was there.
     *
     * <p>Re-reading facts is what makes a criteria change cheap: facts do not
     * change when a criterion does, and re-measuring a dimension costs up to 26
     * seconds. Four things must all hold: the file parses, and its dimension,
     * seed and config fingerprint match. The fingerprint is the load-bearing
     * one — a config edit changes what generates, so an older record describes
     * a different world.
     *
     * <p><b>Release match, not compatibility.</b> A record measured by another
     * release is deleted, never adapted: what the engine measures is defined by
     * the build that measured it, and there is deliberately no mechanism for
     * reading an older record's facts under this build's meanings. A dev build
     * reports no release, so it never reuses — two dev builds share a version
     * string while measuring different things.
     */
    private static SeedFacts reusableFacts(Path path, DimensionConfig def,
                                           Identifier dimensionId, long seed) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        String running = Artefacts.stackVersion();
        try {
            SeedFacts banked = SeedFactsCodec.read(Files.readString(path));
            String fingerprint = String.valueOf(def.getBiomePatchesFingerprint());
            if (Artefacts.isRelease(running)
                    && banked.stackVersion().equals(running)
                    && banked.seed() == seed
                    && banked.dimension().equals(dimensionId.toString())
                    && banked.configFingerprint().equals(fingerprint)) {
                return banked;
            }
        } catch (IOException | RuntimeException e) {
            MultiverseServer.LOGGER.debug("Banked facts at {} unusable: {}",
                    path, e.toString());
        }
        discard(path);
        return null;
    }

    /** Stale facts are removed, so nothing downstream can read them again. */
    private static void discard(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            MultiverseServer.LOGGER.warn("Could not delete stale facts at {}", path, e);
        }
    }
}
