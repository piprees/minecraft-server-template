package com.customdimensions.command;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.roll.SiteValidity;
import com.customdimensions.web.BankView;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code /customdim site-validity <dimension> <seed> [radius]} — every
 * noise-managed site in a (dimension, seed) with the verdict its own biome
 * gives it, written to an artefact.
 *
 * <p>Headless and chunkless: it reads the placement index, the weighted pick
 * and the biome source, so it answers for a world that has never been created.
 * That is what makes it a CI gate rather than a post-mortem — the same shape
 * as {@code render-check-headless}, which it sits beside.
 */
public final class SiteValidityCommand {

    private SiteValidityCommand() {
    }

    static int siteValidity(CommandContext<ServerCommandSource> ctx, long seed, Integer radius) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);

        DimensionConfig def = BankView.resolve(dimensionId.getPath());
        if (def == null) {
            source.sendError(Text.literal("site-validity: no configured world " + dimensionId));
            return 0;
        }
        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);
        if (!base.ok()) {
            source.sendError(Text.literal(
                    "site-validity " + dimensionId + ": " + base.error()));
            return 0;
        }

        int span = radius != null ? radius : Math.max(1, def.getPlayerBorderRadius());
        SiteValidity.Report report = SiteValidity.of(server, def, base, seed, span);

        Path target = artefactPath(dimensionId, seed);
        try {
            Artefacts.write(target, SiteValidity.json(report));
        } catch (IOException e) {
            source.sendError(Text.literal(
                    "site-validity " + dimensionId + ": " + report.summary()
                    + " -- artefact unwritable: " + e));
            return 0;
        }
        final String out = "site-validity " + dimensionId + " seed=" + seed + ": "
                + report.summary() + " -> " + target;
        source.sendFeedback(() -> Text.literal(out), false);
        return 1;
    }

    /** The bind-mounted rolling directory where one exists, the config dir otherwise. */
    private static Path artefactPath(Identifier dimensionId, long seed) {
        String name = "site-validity__" + dimensionId.toString().replace(':', '_')
                + "__" + seed + ".json";
        return Artefacts.canWriteDurably()
                ? Artefacts.rollingDir().resolve(name)
                : Artefacts.dir("site-validity").resolve(name);
    }
}
