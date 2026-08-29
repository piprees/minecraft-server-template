package com.customdimensions.command;

import com.customdimensions.roll.StructureSizes;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code /customdim structure-sizes <dimension> [samples] [budgetSeconds]} —
 * every structure in the registry assembled at scattered chunks, and the
 * bounding box each one built, written to an artefact.
 *
 * <p>The placement model needs a footprint per structure so a castle claims
 * more ground than a well. The jigsaw fields in the jars do not carry one —
 * {@code size} is pool depth and {@code max_distance_from_center} is a search
 * bound most authors leave at its default. This measures the thing itself.
 *
 * <p>A structure's biomes are ignored here on purpose: the question is how
 * big it is, not where it belongs, and holding a structure to its own biomes
 * would leave every one whose biomes this dimension lacks unmeasured.
 *
 * <p><b>An overworld is the one to run.</b> Measured: an overworld reaches
 * 781 of 783 structures and the nether 727, a strict subset, because a low
 * ceiling truncates a jigsaw expansion — {@code minecraft:ancient_city} spans
 * 242 blocks in an overworld and 65 in a nether. Where both measure a
 * structure the medians agree (73% identical, p90 within 11%), so a footprint
 * is a property of the structure and one sweep is enough. Run a second
 * dimension only to fill a hole, and merge by the larger value.
 *
 * <p><b>Runs off the server thread, and must.</b> An assembly costs roughly
 * half a second per structure and the pack has 783 of them, so a synchronous
 * sweep would hold the main thread for minutes and wedge RCON while
 * {@code docker ps} still reads healthy. Only {@code createStructureStart} is
 * called — never {@code StructureAccessor} — so the {@code HashMap} that
 * makes a structure locate server-thread-only is not in this path. The
 * command returns immediately and the artefact lands at the end.
 *
 * <p>Progress is a SEPARATE command, {@code /customdim structure-sizes-progress}.
 * Asking a long job how it is doing must not be able to start another one —
 * a poll loop written against a single command restarts the sweep the moment
 * it finishes, forever.
 */
public final class StructureSizesCommand {

    private StructureSizesCommand() {
    }

    /** Positions per structure, and the ceiling on attempts to find them. */
    private static final int DEFAULT_SAMPLES = 3;
    private static final int ATTEMPTS_PER_SAMPLE = 2;

    /** Wide enough to cross landforms, small enough to stay inside any border. */
    private static final int SCATTER_RADIUS_CHUNKS = 256;

    /** One sweep at a time: two would double the assembly load for no answer. */
    private static final AtomicReference<Run> RUNNING = new AtomicReference<>();

    /** A sweep in flight or finished, and what it has decided so far. */
    private record Run(Thread thread, AtomicInteger progress, int total,
                       String dimension, Path target, AtomicReference<String> finished) {

        String line() {
            String done = this.finished.get();
            if (done != null) {
                return this.dimension + ": " + done + " -> " + this.target;
            }
            return this.dimension + ": " + this.progress.get() + " of " + this.total
                    + " structures decided, still running -> " + this.target;
        }
    }

    /** What the current or last sweep is doing. Never starts one. */
    static int progress(CommandContext<ServerCommandSource> ctx) {
        Run current = RUNNING.get();
        final String out = current == null
                ? "structure-sizes: no sweep has run since this boot"
                : "structure-sizes " + current.line();
        ctx.getSource().sendFeedback(() -> Text.literal(out), false);
        return 1;
    }

    static int structureSizes(CommandContext<ServerCommandSource> ctx, Integer samples,
                              Integer budgetSeconds) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);

        Run current = RUNNING.get();
        if (current != null && current.thread().isAlive()) {
            final String busy = "structure-sizes " + current.line();
            source.sendFeedback(() -> Text.literal(busy), false);
            return 1;
        }

        ServerWorld world = SpikeSampler.loadedWorld(server, dimensionId);
        if (world == null) {
            source.sendError(Text.literal("structure-sizes: dimension not loaded "
                    + dimensionId + " -- an assembly needs the world's own generator"));
            return 0;
        }

        int want = samples != null ? samples : DEFAULT_SAMPLES;
        long budget = (budgetSeconds != null ? budgetSeconds : 600) * 1000L;
        int total = server.getRegistryManager()
                .get(net.minecraft.registry.RegistryKeys.STRUCTURE).size();
        Path target = artefactPath(dimensionId);
        AtomicInteger progress = new AtomicInteger();
        AtomicReference<String> finished = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            StructureSizes.Census census = StructureSizes.of(server, world, want,
                    want * ATTEMPTS_PER_SAMPLE, SCATTER_RADIUS_CHUNKS, budget, progress);
            finished.set(census.summary());
            try {
                Artefacts.write(target, StructureSizes.json(census));
            } catch (IOException e) {
                finished.set(census.summary() + " -- artefact unwritable: " + e);
                com.customdimensions.MultiverseServer.LOGGER.error(
                        "structure-sizes {}: {} -- artefact unwritable",
                        census.dimension(), census.summary(), e);
                return;
            }
            com.customdimensions.MultiverseServer.LOGGER.info("structure-sizes {}: {} -> {}",
                    census.dimension(), census.summary(), target);
        }, "CustomDimensions-StructureSizes");
        worker.setDaemon(true);
        RUNNING.set(new Run(worker, progress, total, dimensionId.toString(), target, finished));
        worker.start();

        final String out = "structure-sizes " + dimensionId + ": sweeping " + total
                + " structures, " + want + " sample(s) each, budget "
                + (budget / 1000L) + "s. Ask structure-sizes-progress how it is doing;"
                + " the artefact and a log line land at the end -> " + target;
        source.sendFeedback(() -> Text.literal(out), false);
        return 1;
    }

    /** The bind-mounted rolling directory where one exists, the config dir otherwise. */
    private static Path artefactPath(Identifier dimensionId) {
        String name = "structure-sizes__"
                + dimensionId.toString().replace(':', '_') + ".json";
        return Artefacts.canWriteDurably()
                ? Artefacts.rollingDir().resolve(name)
                : Artefacts.dir("structure-sizes").resolve(name);
    }
}
