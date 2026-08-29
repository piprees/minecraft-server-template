package com.customdimensions;

import com.customdimensions.command.DimensionCommands;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DifficultyManager;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.dimension.StorageHelper;
import com.customdimensions.dimension.VisitLog;
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
        // Mining one pane of an arrival portal takes the whole portal, the way
        // vanilla's does. NetherPortalProtectionMixin defends registered
        // portal blocks from NEIGHBOUR updates (netherportalspread and friends
        // would otherwise delete them), and spawnTargetPortalParticles heals a
        // hole so a missing pane cannot strand anyone — both correct, and
        // between them they made a deliberate pick-swing do nothing but
        // regenerate. A PLAYER break is a different intention entirely, and
        // this is the only place that can tell the two apart.
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register(
                (world, player, pos, state, entity) -> {
                    if (world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                        com.customdimensions.portal.PortalHelper.onPlayerBrokePortalBlock(serverWorld, pos);
                    }
                });
        // Config-driven overworld spawn: the worlds[] overworld entry's
        // "spawn": [x, y, z] replaces the SPAWN_X/Y/Z env enforcement.
        // Other worlds share the global spawn in vanilla, so only the
        // overworld entry is applied.
        // Try-out worlds never reach level.dat, so anything left on disk from
        // a previous run is unreferenced bytes — and region files are not
        // small. SERVER_STARTED, because nothing can have created one yet.
        ServerLifecycleEvents.SERVER_STARTED.register(
                com.customdimensions.tryout.TryOut::purgeOnStart);
        ServerLifecycleEvents.SERVER_STARTED.register(VisitLog::load);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            com.customdimensions.config.DimensionConfig ow =
                    MultiverseConfig.getInstance().getReservedDimensionBySlug("overworld");
            int[] spawn = ow != null ? ow.getSpawn() : null;
            // [0, 64, 0] means "no spawn chosen" — the same contract
            // deploy.sh's spawn guard reads. Stamping it would overwrite
            // vanilla's own spawn choice with the origin.
            if (spawn != null && (spawn[0] != 0 || spawn[1] != 64 || spawn[2] != 0)) {
                int groundY = com.customdimensions.portal.PortalHelper.findSurfaceY(
                        server.getOverworld(), spawn[0], spawn[2]);
                server.getOverworld().setSpawnPos(
                        new net.minecraft.util.math.BlockPos(spawn[0], groundY, spawn[2]), 0.0f);
                LOGGER.info("World spawn set from config: {} {} {} (Y grounded from {})",
                        spawn[0], groundY, spawn[2], spawn[1]);
            }
            // Per-dimension world borders (borders.player) — after
            // createWorlds so vanilla's overworld border-load can't clobber
            // them (see WorldBorderManager for the syncer trap).
            com.customdimensions.dimension.WorldBorderManager.applyAll(server);
            // The seed tool's browser talks to this process directly — the
            // registries, the bank and the live server are all right here.
            com.customdimensions.web.SeedServer.start(server);
        });
        // Runtime-created dimensions get their border the moment they load.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.LOAD.register(
            (server, world) -> com.customdimensions.dimension.WorldBorderManager.onWorldLoad(world));
        // Immersive portals: a pre-loaded-but-unvisited target world is
        // closed by the idle unloader after its timeout. Drop that world's
        // pre-load record so the next approach re-triggers pre-loading
        // instead of silently no-opping forever.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.UNLOAD.register(
            (server, world) -> {
                com.customdimensions.immersive.ImmersivePreloader.invalidate(world.getRegistryKey());
                // Release (or drop) any preview chunk tickets tied to this
                // world before its chunk manager closes.
                com.customdimensions.immersive.ImmersiveProjector.onWorldUnload(world);
                // Structure pick: clear the selection registry for this world
                // so stale entries from a previous calculator never match.
                com.customdimensions.dimension.StructurePick.clear(
                        world.getRegistryKey().getValue().toString());
                // Empty-site records are batched; this world's pending ones
                // are written before its chunk manager closes.
                com.customdimensions.dimension.RejectionCensus.flush(
                        world.getRegistryKey().getValue().toString());
            });
        // Immersive portals: a disconnecting player's fake-block
        // projections are dropped without restore packets — there is no
        // connection left to send them on, and vanilla's chunk resend on
        // the next login corrects anything left over.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            com.customdimensions.immersive.ImmersiveProjector.forgetPlayer(
                    handler.player.getUuid(), handler.player.getName().getString(),
                    "player disconnected"));
        // ...and again on JOIN. A reconnecting client has just been sent
        // REAL chunk data, so any surviving delta baseline is a lie: a
        // player who relogs while still inside activationRange would keep a
        // non-null state, take the delta branch, compare every position
        // equal, and see no projection at all until they walked out of
        // range and back. Also the backstop for any path where DISCONNECT
        // never fires.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            com.customdimensions.immersive.ImmersiveProjector.forgetPlayer(
                    handler.player.getUuid(), handler.player.getName().getString(),
                    "player joined"));
        // Per-dimension player luck (DimensionConfig.difficulty.playerLuck):
        // re-applied whenever a player joins or changes world.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            DifficultyManager.applyPlayerLuck(handler.player));
        // The map lists a dimension once somebody has been in it. JOIN covers
        // the world a player logs back into; the world change below covers
        // every other arrival.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            VisitLog.record(server, handler.player.getWorld().getRegistryKey()));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
            (player, origin, destination) -> {
                VisitLog.record(player.getServer(), destination.getRegistryKey());
                DifficultyManager.applyPlayerLuck(player);
                // Immersive portals: the projections this player had in the
                // world they LEFT must go — stepping THROUGH an immersive
                // portal is the common case. Dropped without restore
                // packets: those coordinates now address the destination
                // dimension on their client.
                com.customdimensions.immersive.ImmersiveProjector.forgetInWorld(
                        player.getUuid(), player.getName().getString(), origin.getRegistryKey());
            });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            com.customdimensions.tryout.TryOut.tick(server);
            com.customdimensions.command.RenderCheck.tick(server);
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
        com.customdimensions.web.SeedServer.stop();
        com.customdimensions.command.LocateManager.getInstance().shutdown();
        com.customdimensions.command.RenderCheck.clear();
        StorageHelper.shutdown();
        LOGGER.info("CustomDimensions shutdown complete");
    }
}
