package com.customdimensions;

import com.customdimensions.command.DimensionCommands;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.dimension.StorageHelper;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiverseServer implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("customdimensions");

    @Override
    public void onInitializeServer() {
        LOGGER.info("Initializing CustomDimensions (The Multiverse Engine)");
        FabricLoader.getInstance().getObjectShare().put("customdimensions:init", true);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            DimensionCommands.register(dispatcher));
        // Config-driven overworld spawn: the worlds[] overworld entry's
        // "spawn": [x, y, z] replaces the SPAWN_X/Y/Z env enforcement.
        // Other worlds share the global spawn in vanilla, so only the
        // overworld entry is applied.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            com.customdimensions.config.WorldSeedDefinition ow =
                    MultiverseConfig.getInstance().getWorld("overworld");
            int[] spawn = ow != null ? ow.getSpawn() : null;
            if (spawn != null) {
                server.getOverworld().setSpawnPos(
                        new net.minecraft.util.math.BlockPos(spawn[0], spawn[1], spawn[2]), 0.0f);
                LOGGER.info("World spawn set from config: {} {} {}", spawn[0], spawn[1], spawn[2]);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DimensionManager.getInstance().processPendingWorldLoads();
            DimensionManager.getInstance().reconcileOrphansOnce();
            DimensionManager.getInstance().processPendingWorldUnloads();
            if (server.getTicks() % 1200 == 0) {
                DimensionManager.getInstance().unloadIdleDimensions(server, MultiverseConfig.getInstance().getIdleUnloadMinutes());
            }
        });
    }

    public static void onServerStarting(MinecraftServer server) {
        StorageHelper.ensureDirectoryAsync(StorageHelper.getDimensionDirectory(server, ""));
    }

    public static void onServerStopping(MinecraftServer server) {
        StorageHelper.shutdown();
        LOGGER.info("CustomDimensions shutdown complete");
    }
}
