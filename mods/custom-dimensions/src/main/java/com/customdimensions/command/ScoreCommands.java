package com.customdimensions.command;

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

import java.util.Locale;

/**
 * {@code /customdim score <dim> <seed>} — measure, then judge, then say which.
 *
 * <p>Reports inline rather than banking facts and a scorecard to disk: the
 * roller's own candidate file is where a (dimension, seed) result is kept
 * for reuse, and this command always measures fresh.
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

        Double pct = card.percentage();
        final String msg = String.format(Locale.ROOT,
                "score %s seed=%d: %s%s (%.1f/%.1f)",
                dimensionId, seed, card.verdict(),
                pct == null ? "" : String.format(Locale.ROOT, " %.1f%%", pct),
                card.achieved(), card.ceiling());
        source.sendFeedback(() -> Text.literal(msg), false);
        return pct == null ? 0 : (int) Math.round(pct);
    }
}
