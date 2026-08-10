package com.customdimensions.command;

import com.customdimensions.facts.FactsEngine;
import com.customdimensions.facts.SeedFacts;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * {@code /customdim facts <dim> <seed>} — measure one (dimension, seed) and
 * report it. The persisted record lives in the candidate the roller writes
 * for this (dimension, seed) — this command is the manual, ad-hoc measure,
 * not a second copy of that storage.
 *
 * <p>The summary line reports how many facts came back ABSENT, not just how
 * many came back. A run that measured nothing and a run that measured
 * everything otherwise look identical from a success message, and the absent
 * count is the number that tells a human which they got.
 */
public final class FactsCommands {

    private FactsCommands() {
    }

    static int facts(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);
        long seed = LongArgumentType.getLong(ctx, "seed");

        int worldsBefore = SpikeSampler.worldCount(source.getServer());
        long started = System.nanoTime();
        SeedFacts facts = FactsEngine.measure(source.getServer(), dimensionId, seed);
        long millis = (System.nanoTime() - started) / 1_000_000L;
        int worldsAfter = SpikeSampler.worldCount(source.getServer());

        var absences = facts.absences();
        final String msg = "facts " + dimensionId + " seed=" + seed + ": "
                + facts.structures().totalPositions().toJson(String::valueOf)
                + " structure positions, "
                + facts.biomes().distinctCount().toJson(String::valueOf)
                + " biomes, " + absences.size() + " absent fact(s) in "
                + millis + "ms, worlds " + worldsBefore + "->" + worldsAfter;
        source.sendFeedback(() -> Text.literal(msg), false);
        return absences.size();
    }
}
