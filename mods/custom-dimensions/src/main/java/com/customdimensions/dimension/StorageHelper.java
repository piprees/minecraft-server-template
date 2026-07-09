package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StorageHelper {
    private static final ExecutorService IO_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "CustomDimensions-IO");
        t.setDaemon(true);
        return t;
    });

    public static Path getDimensionDirectory(MinecraftServer server, String dimName) {
        return server.getRunDirectory().resolve("dimensions").resolve(dimName);
    }

    public static CompletableFuture<Void> ensureDirectoryAsync(Path dir) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                MultiverseServer.LOGGER.error("Failed to create dir: {}", dir, e);
            }
        }, IO_POOL);
    }

    public static CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(task, IO_POOL);
    }

    public static void shutdown() {
        IO_POOL.shutdown();
    }
}
