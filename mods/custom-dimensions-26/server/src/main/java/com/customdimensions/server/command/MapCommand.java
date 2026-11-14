package com.customdimensions.server.command;

import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.core.render.WavePlan;
import com.customdimensions.core.render.map.MapTiles;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.map.ExploredExport;
import com.customdimensions.server.map.MapColours;
import com.customdimensions.server.map.MapSite;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.render.Sampler;
import com.customdimensions.server.render.fast.MapExport;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * {@code /customdim map export <dimension>|all [generated|explored|force]}:
 * the web map into {@link MapSite#root}. For each dimension, one at a time:
 * its generated layers ({@link MapExport}, skipped while its fingerprint
 * holds; {@code force} redraws them) and its explored layer
 * ({@link ExploredExport}, only regions changed since the last export); then
 * the viewer shell and the manifest of every dimension. Shares the render's
 * one-job slot.
 */
final class MapCommand {
    private MapCommand() {}

    enum What { BOTH, GENERATED, EXPLORED, FORCE }

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        var export = Commands.literal("export");
        var all = Commands.literal("all").executes(c -> start(c.getSource(), null, What.BOTH) ? 1 : 0);
        var one = CustomDimCommands.dimensionArgument()
                .executes(c -> start(c.getSource(), c.getArgument("dimension", String.class), What.BOTH) ? 1 : 0);
        for (What w : What.values()) {
            if (w == What.BOTH) continue;
            String name = w.name().toLowerCase(Locale.ROOT);
            all.then(Commands.literal(name).executes(c -> start(c.getSource(), null, w) ? 1 : 0));
            one.then(Commands.literal(name).executes(c -> start(c.getSource(), c.getArgument("dimension", String.class), w) ? 1 : 0));
        }
        root.then(Commands.literal("map").then(export.then(all).then(one)));
    }

    /** The radius the map covers: the wave's, the larger of the player and generation borders. */
    static int radius(CustomDimCommands.Dimension d) {
        return FastRenderCommand.waveRadius(d);
    }

    static boolean start(CommandSourceStack source, String slug, What what) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return false;
        List<CustomDimCommands.Dimension> all = CustomDimCommands.all(manager.get());
        List<CustomDimCommands.Dimension> dims;
        if (slug == null) {
            dims = all;
        } else {
            Optional<CustomDimCommands.Dimension> d = CustomDimCommands.dimension(source, manager.get(), slug);
            if (d.isEmpty()) return false;
            dims = List.of(d.get());
        }
        Optional<RenderJob> busy = RenderJob.running();
        if (busy.isPresent()) {
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", busy.get().request().dimension(),
                    "percent", String.valueOf(busy.get().percent()));
            return false;
        }
        MinecraftServer server = source.getServer();
        LevelManager levels = manager.get();
        Path root = MapSite.root(server.getServerDirectory());
        int threads = RenderJob.defaultThreads();
        long columns = 0;
        for (CustomDimCommands.Dimension d : dims) {
            int side = MapTiles.of(radius(d)).side(MapTiles.of(radius(d)).maxZoom());
            columns += (long) side * side;
        }
        String label = slug == null ? "all" : slug;
        int firstRadius = radius(dims.getFirst());
        RenderJob.Request request = new RenderJob.Request(label, null,
                RenderGrid.wide(0, 0, firstRadius, WavePlan.of(firstRadius, WavePlan.FINAL_SIDE).passes().getLast().stride()), root);
        Optional<RenderJob> job = RenderJob.startWork(server, request, columns, threads, (self, workers) -> {
            long started = System.nanoTime();
            int failed = 0;
            for (CustomDimCommands.Dimension d : dims) {
                long t = System.nanoTime();
                try {
                    Path out = MapSite.dimension(root, d.slug());
                    int radius = radius(d);
                    String generated = "-";
                    String explored = "-";
                    if (what != What.EXPLORED) {
                        Sampler sampler = Sampler.forDimension(server, levels, d.slug(), null);
                        MapExport.Result g = MapExport.run(sampler, radius, workers, threads, out, self, what == What.FORCE);
                        generated = g.skipped() ? "unchanged" : g.tiles() + " tiles";
                        CustomDimensions.LOG.info("web map {} generated: {}", d.slug(), g);
                    }
                    if (what != What.GENERATED) {
                        ServerLevel level = server.getLevel(d.key());
                        Path regions = DimensionType.getStorageFolder(d.key(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
                        MapColours colours = new MapColours(server.registryAccess());
                        ExploredExport.Result e = ExploredExport.run(d.slug(), level, regions, colours, radius, out, workers,
                                what == What.FORCE);
                        explored = e.skipped() ? "unchanged" : e.chunks() + " chunks";
                        CustomDimensions.LOG.info("web map {} explored: {}", d.slug(), e);
                    }
                    String g = generated;
                    String e = explored;
                    String seconds = seconds((System.nanoTime() - t) / 1_000_000);
                    server.execute(() -> CustomDimCommands.ok(source, "customdim.map.export.dimension", "dimension", d.slug(),
                            "generated", g, "explored", e, "seconds", seconds));
                } catch (Exception ex) {
                    failed++;
                    CustomDimensions.LOG.error("web map {} failed", d.slug(), ex);
                    server.execute(() -> CustomDimCommands.fail(source, "customdim.map.export.failed", "dimension", d.slug(),
                            "reason", CustomDimCommands.reason(ex)));
                }
                MapSite.manifest(root, entries(all));
            }
            MapSite.shell(root);
            MapSite.manifest(root, entries(all));
            return new long[] {dims.size(), failed, (System.nanoTime() - started) / 1_000_000};
        }, r -> CustomDimCommands.ok(source, "customdim.map.export.done", "count", String.valueOf(r[0]), "failed", String.valueOf(r[1]),
                "total", seconds(r[2]), "path", root.toString()),
                e -> CustomDimCommands.fail(source, "customdim.map.export.failed", "dimension", label, "reason", CustomDimCommands.reason(e)));
        if (job.isEmpty()) {
            RenderJob other = RenderJob.running().orElse(null);
            CustomDimCommands.fail(source, "customdim.render.busy", "dimension", other == null ? "?" : other.request().dimension(),
                    "percent", other == null ? "0" : String.valueOf(other.percent()));
            return false;
        }
        CustomDimCommands.ok(source, "customdim.map.export.started", "count", String.valueOf(dims.size()),
                "what", what.name().toLowerCase(Locale.ROOT), "threads", String.valueOf(threads), "path", root.toString());
        return true;
    }

    static List<MapSite.Entry> entries(List<CustomDimCommands.Dimension> all) {
        List<MapSite.Entry> out = new ArrayList<>();
        for (CustomDimCommands.Dimension d : all) {
            var def = d.definition();
            int[] spawn = def != null && def.spawn() != null ? new int[] {def.spawn().x(), def.spawn().y(), def.spawn().z()} : null;
            out.add(new MapSite.Entry(d.slug(), def == null ? null : def.description(), spawn));
        }
        return out;
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f", millis / 1000.0);
    }
}
