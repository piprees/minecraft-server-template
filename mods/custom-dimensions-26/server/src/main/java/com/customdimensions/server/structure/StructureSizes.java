package com.customdimensions.server.structure;

import com.customdimensions.core.firewall.Bridge;
import com.customdimensions.core.firewall.BridgeReader;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measures every registered structure on the running game (T54): its start is
 * generated without a level, as the generator would at a chunk, with the biome
 * check off, and the start's bounding box is its size. Each structure is tried
 * at up to {@link #ATTEMPTS} chunks of the overworld, then the Nether, then the
 * End, until a start is valid. Writes {@code structure-sizes.json} and fills
 * the size slots of the bridges in {@code customdimensions.bridges}, then stops
 * the server. Runs off the server thread.
 */
public final class StructureSizes {

    public static final String PROPERTY = "customdimensions.structuresizes";
    static final int ATTEMPTS = 24;

    /** A measured size in blocks: the start's bounding box spans. */
    public record Size(int x, int y, int z) {}

    private StructureSizes() {
    }

    public static void register() {
        String out = System.getProperty(PROPERTY, "");
        if (out.isEmpty()) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Thread t = new Thread(() -> run(server, Path.of(out)), "customdimensions-structure-sizes");
            t.setDaemon(true);
            t.start();
        });
    }

    private static void run(MinecraftServer server, Path out) {
        try {
            Map<String, Size> sizes = measureAll(server);
            write(sizes, out);
            String bridges = System.getProperty("customdimensions.bridges", "");
            if (!bridges.isEmpty()) fillBridges(Path.of(bridges), sizes);
        } catch (Exception e) {
            CustomDimensions.LOG.error("structure sizes failed", e);
        } finally {
            server.execute(() -> server.halt(false));
        }
    }

    static Map<String, Size> measureAll(MinecraftServer server) {
        Registry<Structure> registry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<ServerLevel> levels = new ArrayList<>();
        for (var key : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            ServerLevel level = server.getLevel(key);
            if (level != null) levels.add(level);
        }
        Map<String, Size> out = new TreeMap<>();
        List<String> failed = new ArrayList<>();
        long started = System.nanoTime();
        for (Holder.Reference<Structure> holder : registry.listElements().toList()) {
            String id = holder.key().identifier().toString();
            Size size = null;
            for (ServerLevel level : levels) {
                size = measure(server, level, holder);
                if (size != null) break;
            }
            if (size == null) failed.add(id);
            else out.put(id, size);
        }
        CustomDimensions.LOG.info("structure sizes: {} measured, {} unmeasured {} in {} s", out.size(), failed.size(), failed,
                (System.nanoTime() - started) / 1_000_000_000);
        return out;
    }

    static Size measure(MinecraftServer server, ServerLevel level, Holder<Structure> holder) {
        var source = level.getChunkSource();
        var climate = source.randomState().createClimateSampler(
                net.minecraft.world.level.levelgen.densityfunction.SamplerContext.builder().enableCaches().build());
        for (int i = 0; i < ATTEMPTS; i++) {
            ChunkPos chunk = new ChunkPos(40 + i * 37, -60 + i * 23);
            try {
                StructureStart start = holder.value().generate(holder, level.dimension(), server.registryAccess(),
                        source.getGenerator(), source.getGenerator().getBiomeSource(), climate,
                        source.randomState(), server.getStructureTemplateManager(), level.getSeed(), chunk, 0, level, b -> true);
                if (start.isValid()) {
                    BoundingBox box = start.getBoundingBox();
                    return new Size(box.getXSpan(), box.getYSpan(), box.getZSpan());
                }
            } catch (RuntimeException e) {
                CustomDimensions.LOG.debug("structure {} at {} in {}: {}", holder, chunk, level.dimension(), e.toString());
            }
        }
        return null;
    }

    static void write(Map<String, Size> sizes, Path out) throws java.io.IOException {
        JsonObject o = new JsonObject();
        sizes.forEach((id, s) -> {
            JsonObject j = new JsonObject();
            j.addProperty("x", s.x());
            j.addProperty("y", s.y());
            j.addProperty("z", s.z());
            o.add(id, j);
        });
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n");
    }

    static void fillBridges(Path dir, Map<String, Size> sizes) throws java.io.IOException {
        for (Map.Entry<String, Bridge> e : BridgeReader.readAll(dir).entrySet()) {
            Bridge b = e.getValue();
            List<Bridge.Structure> structures = new ArrayList<>();
            for (Bridge.Structure s : b.structures()) {
                Size m = sizes.get(s.id());
                Bridge.Size size = m == null ? s.size() : new Bridge.Size(m.x(), m.y(), m.z());
                structures.add(new Bridge.Structure(s.id(), s.set(), size, s.mood(), s.climate(), s.depth()));
            }
            Bridge filled = new Bridge(b.mod(), b.reviewed(), b.structureSets(), structures, b.features(), b.spawns(),
                    b.mixins(), b.registries());
            Files.writeString(dir.resolve(b.mod() + ".json"), BridgeReader.write(filled));
        }
    }
}
