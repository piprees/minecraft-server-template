package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import net.minecraft.registry.RegistryKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dimension ids a player has entered, in the order they were first entered, as
 * a JSON array in the world directory. The map renderer reads it to decide
 * what to list and in what order: chunks on disk prove nothing, because Chunky
 * pre-generates the base worlds.
 */
public final class VisitLog {
    private static final String FILE_NAME = "visited-dimensions.json";
    private static final Set<String> VISITED =
            Collections.synchronizedSet(new LinkedHashSet<>());

    private VisitLog() {
    }

    private static Path path(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve(FILE_NAME);
    }

    public static void load(MinecraftServer server) {
        Path file = path(server);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonArray()) {
                return;
            }
            for (JsonElement entry : parsed.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) {
                    VISITED.add(entry.getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            MultiverseServer.LOGGER.warn("Could not read {}: {}", FILE_NAME, e.toString());
        }
    }

    /** Records a dimension as visited, writing the file only on a new entry. */
    public static void record(MinecraftServer server, RegistryKey<World> dimension) {
        if (server == null || dimension == null || !VISITED.add(dimension.getValue().toString())) {
            return;
        }
        MultiverseServer.LOGGER.info("First visit to {}", dimension.getValue());
        StorageHelper.runAsync(() -> write(server));
    }

    private static void write(MinecraftServer server) {
        JsonArray array = new JsonArray();
        synchronized (VISITED) {
            for (String id : VISITED) {
                array.add(id);
            }
        }
        Path file = path(server);
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, array.toString());
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Could not write {}", FILE_NAME, e);
        }
    }
}
