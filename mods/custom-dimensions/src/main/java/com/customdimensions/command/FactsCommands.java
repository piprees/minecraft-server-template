package com.customdimensions.command;

import com.customdimensions.facts.FactsEngine;
import com.customdimensions.facts.SeedFacts;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code /customdim facts <dim> <seed>} — measure one (dimension, seed),
 * report it, and write the full record under {@code .seed-rolling/}.
 *
 * <p>The record is the answer and the summary line is a receipt: RCON cannot
 * carry per-biome shares ([T17](../../../../../../../TROUBLESHOOTING.md#t17)).
 * A roller candidate is a snapshot of the config it was rolled against, so a
 * fresh measurement of a config that has since moved is a different fact
 * rather than a second copy of one — which is why this writes its own file
 * and keys it on (dimension, seed).
 *
 * <p>The body is {@link SeedFacts#toJson()} verbatim rather than an
 * {@code Artefacts.jsonHeader} wrapper: the record already carries
 * {@code stackVersion}, {@code measuredAt} and {@code configFingerprint}, and
 * keeping it byte-comparable with a banked candidate is the point.
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
        Path out = artefactPath(Artefacts.rollingDir(), dimensionId, seed);
        String where;
        try {
            Artefacts.write(out, facts.toJson());
            where = " -> " + out;
        } catch (IOException e) {
            // The measurement succeeded and only the record is missing, so say
            // which failed rather than reporting the whole run as broken.
            source.sendError(Text.literal("facts: write failed: " + e.getMessage()));
            where = " (NOT PERSISTED)";
        }
        final String msg = "facts " + dimensionId + " seed=" + seed + ": "
                + facts.structures().totalPositions().toJson(String::valueOf)
                + " structure positions, "
                + facts.biomes().distinctCount().toJson(String::valueOf)
                + " biomes, " + absences.size() + " absent fact(s) in "
                + millis + "ms, worlds " + worldsBefore + "->" + worldsAfter + where;
        source.sendFeedback(() -> Text.literal(msg), false);
        return absences.size();
    }

    /**
     * Where a (dimension, seed) measurement is written. Keyed so re-measuring
     * replaces its own file and can never overwrite a roller candidate, which
     * lives under {@code candidates/}.
     *
     * <p>Takes the directory rather than reading it, so the naming rule is
     * testable: {@code Artefacts.rollingDir()} needs a Fabric game directory
     * and this suite has none.
     */
    static Path artefactPath(Path rollingDir, Identifier dimensionId, long seed) {
        return rollingDir.resolve(
                "facts__" + dimensionId.toString().replace(':', '_') + "__" + seed + ".json");
    }
}
