package com.customdimensions;

import com.customdimensions.command.DimensionCommands;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DifficultyManager;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.dimension.StorageHelper;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
        // biomePatches: the wrapper source must have a registered codec or
        // vanilla cannot encode the dimension's generator into level.dat at
        // save time (crash on first world save, not at creation).
        net.minecraft.registry.Registry.register(
                net.minecraft.registry.Registries.BIOME_SOURCE,
                net.minecraft.util.Identifier.of("customdimensions", "patched"),
                com.customdimensions.dimension.PatchedBiomeSource.CODEC);
        // structures.force: fixed placements live only in per-world rebuilt
        // calculators (never level.dat), but the type registration keeps
        // getType() honest and future serialisation safe.
        net.minecraft.registry.Registry.register(
                net.minecraft.registry.Registries.STRUCTURE_PLACEMENT,
                net.minecraft.util.Identifier.of("customdimensions", "fixed"),
                com.customdimensions.dimension.FixedStructurePlacement.TYPE);
        // Exit conditions ("exits" block): death redirection and the
        // ender-pearl trigger. Void/fallFrom run from the world tick
        // (ServerWorldMixin); the respawn override is consumed by
        // PlayerRespawnRedirectMixin.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register(
                (entity, source, amount) -> {
                    if (entity instanceof net.minecraft.server.network.ServerPlayerEntity player
                            && entity.getWorld() instanceof net.minecraft.server.world.ServerWorld world) {
                        return com.customdimensions.dimension.ExitConditions.onDeath(player, world, source);
                    }
                    return true;
                });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
                (player, world, hand) -> {
                    net.minecraft.item.ItemStack stack = player.getStackInHand(hand);
                    if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp
                            && world instanceof net.minecraft.server.world.ServerWorld sw
                            && stack.isOf(net.minecraft.item.Items.ENDER_PEARL)
                            && com.customdimensions.dimension.ExitConditions.handleEnderPearl(sp, sw)) {
                        return net.minecraft.util.TypedActionResult.fail(stack);
                    }
                    return net.minecraft.util.TypedActionResult.pass(stack);
                });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) ->
                        com.customdimensions.dimension.ExitConditions.forgetPlayer(handler.player.getUuid()));
        // Exit shrines: beacon detection on chunk load (cheap block-entity
        // map scan); block mutation happens in the world tick drain, never
        // inside the load event.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register(
                com.customdimensions.portal.ExitShrineManager::onChunkLoad);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            DimensionCommands.register(dispatcher));
        // Config-driven overworld spawn: the worlds[] overworld entry's
        // "spawn": [x, y, z] replaces the SPAWN_X/Y/Z env enforcement.
        // Other worlds share the global spawn in vanilla, so only the
        // overworld entry is applied.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            com.customdimensions.config.DimensionConfig ow =
                    MultiverseConfig.getInstance().getWorld("overworld");
            int[] spawn = ow != null ? ow.getSpawn() : null;
            if (spawn != null) {
                server.getOverworld().setSpawnPos(
                        new net.minecraft.util.math.BlockPos(spawn[0], spawn[1], spawn[2]), 0.0f);
                LOGGER.info("World spawn set from config: {} {} {}", spawn[0], spawn[1], spawn[2]);
            }
            // Per-dimension world borders (borders.player) — after
            // createWorlds so vanilla's overworld border-load can't clobber
            // them (see WorldBorderManager for the syncer trap).
            com.customdimensions.dimension.WorldBorderManager.applyAll(server);
        });
        // Runtime-created dimensions get their border the moment they load.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.LOAD.register(
            (server, world) -> com.customdimensions.dimension.WorldBorderManager.onWorldLoad(world));
        // Per-dimension player luck (DimensionConfig.difficulty.playerLuck):
        // re-applied whenever a player joins or changes world.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            DifficultyManager.applyPlayerLuck(handler.player));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
            (player, origin, destination) -> DifficultyManager.applyPlayerLuck(player));
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
        com.customdimensions.command.LocateManager.getInstance().shutdown();
        StorageHelper.shutdown();
        LOGGER.info("CustomDimensions shutdown complete");
    }
}
