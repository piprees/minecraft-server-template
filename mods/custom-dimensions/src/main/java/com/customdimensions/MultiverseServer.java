package com.customdimensions;

import com.customdimensions.command.DimensionCommand;
import com.customdimensions.command.PortalCommand;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.dimension.StorageHelper;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            DimensionCommand.register(dispatcher);
            PortalCommand.register(dispatcher);
            LOGGER.info("CustomDimensions commands registered");
        });
        // Idle unload MUST run outside MinecraftServer.tickWorlds: it removes
        // entries from the server's worlds map, and doing that from a
        // ServerWorld.tick mixin (i.e. mid-iteration of that map) crashes the
        // server with a ConcurrentModificationException.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DimensionManager.getInstance().processPendingWorldLoads();
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
        MultiverseConfig.getInstance().save();
        StorageHelper.shutdown();
        LOGGER.info("CustomDimensions shutdown complete");
    }
}
