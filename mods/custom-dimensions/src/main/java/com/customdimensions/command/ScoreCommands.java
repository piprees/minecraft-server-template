package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.facts.FactsEngine;
import com.customdimensions.facts.SeedFacts;
import com.customdimensions.score.Criteria;
import com.customdimensions.score.Scorecard;
import com.customdimensions.score.Scorer;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
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

        SeedFacts facts = FactsEngine.measure(source.getServer(), dimensionId, seed);
        Scorecard card = Scorer.score(facts, def, Criteria.all());

        try {
            String part = dimensionId.getNamespace() + "__" + dimensionId.getPath()
                    + "__" + seed + ".json";
            Artefacts.write(Artefacts.dir("facts").resolve(part), facts.toJson());
            Path out = Artefacts.dir("scores").resolve(part);
            Artefacts.write(out, card.toJson());
            Double pct = card.percentage();
            final String msg = String.format(Locale.ROOT,
                    "score %s seed=%d: %s%s (%.1f/%.1f) -> %s",
                    dimensionId, seed, card.verdict(),
                    pct == null ? "" : String.format(Locale.ROOT, " %.1f%%", pct),
                    card.achieved(), card.ceiling(), out);
            source.sendFeedback(() -> Text.literal(msg), false);
            return pct == null ? 0 : (int) Math.round(pct);
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to write scorecard", e);
            source.sendError(Text.literal("Write failed: " + e.getMessage()));
            return 0;
        }
    }
}
