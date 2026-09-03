package com.customdimensions.companion;

import com.customdimensions.MultiverseServer;
import com.customdimensions.immersive.ImmersivePreloader;
import com.customdimensions.portal.PortalHelper;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server half of the companion handshake.
 *
 * A player is only in the set once their client has announced a matching
 * protocol version, so a vanilla client is never sent a companion payload and
 * every gated behaviour falls back to what the server does today.
 */
public final class CompanionNetwork {
    /** Grepped in the server log to prove a vanilla client is sent nothing. */
    public static final String SEND_MARKER = "companion-send:preloaded-transfer";

    /** Grepped in the server log: the positive control that a client handshook. */
    public static final String ACCEPT_MARKER = "companion-accept:handshake";

    /** Grepped in the server log to prove the destination stream left this side. */
    public static final String PROJECTION_MARKER = "companion-send:projection";

    private static final Set<UUID> COMPANIONS = ConcurrentHashMap.newKeySet();

    private CompanionNetwork() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(
                CompanionPayloads.Hello.ID, CompanionPayloads.Hello.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.PreloadedTransfer.ID, CompanionPayloads.PreloadedTransfer.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.Projection.ID, CompanionPayloads.Projection.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.ProjectionClear.ID, CompanionPayloads.ProjectionClear.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(CompanionPayloads.Hello.ID,
                (payload, context) -> onHello(context.player().getUuid(),
                        context.player().getNameForScoreboard(), payload.protocolVersion()));
    }

    /** Version skew degrades to vanilla, never to a hybrid. */
    static void onHello(UUID playerId, String playerName, int protocolVersion) {
        if (protocolVersion != CompanionPayloads.PROTOCOL_VERSION) {
            return;
        }
        COMPANIONS.add(playerId);
        MultiverseServer.LOGGER.info("{} player={} protocol={}",
                ACCEPT_MARKER, playerName, protocolVersion);
    }

    public static void forget(UUID playerId) {
        COMPANIONS.remove(playerId);
    }

    public static boolean isCompanion(UUID playerId) {
        return COMPANIONS.contains(playerId);
    }

    /** Drops every record (server shutdown). */
    public static void clear() {
        COMPANIONS.clear();
    }

    /**
     * Tells a companion client the destination is already resident, so it can
     * skip the loading screen. Sent before the teleport on purpose: the
     * connection then carries it ahead of the dimension-change packet.
     */
    public static void notifyPreloadedTransfer(ServerPlayerEntity player, ServerWorld targetWorld,
            PortalHelper.PortalZone zone, int arrivalX, int arrivalZ) {
        if (!COMPANIONS.contains(player.getUuid())) {
            return;
        }
        RegistryKey<World> targetKey = targetWorld.getRegistryKey();
        if (!ImmersivePreloader.hasPreloaded(targetKey, zone)) {
            return;
        }
        // Requested is not resident: a ticket registered moments ago may still
        // be generating. Never force it — a null read means show the screen.
        if (targetWorld.getChunkManager().getWorldChunk(arrivalX >> 4, arrivalZ >> 4, false) == null) {
            return;
        }
        ServerPlayNetworking.send(player, new CompanionPayloads.PreloadedTransfer(targetKey.getValue()));
        MultiverseServer.LOGGER.info("{} player={} dimension={}",
                SEND_MARKER, player.getNameForScoreboard(), targetKey.getValue());
    }

    /**
     * Hands one portal's destination to a companion client to render itself.
     *
     * <p>Paired with the fake-block path being skipped for the same player:
     * both describe the same space, and a client running both draws the
     * destination behind a slab of blocks saying the same thing.
     */
    public static void sendProjection(ServerPlayerEntity player, CompanionPayloads.Projection projection) {
        if (projection == null || !COMPANIONS.contains(player.getUuid())) {
            return;
        }
        ServerPlayNetworking.send(player, projection);
        MultiverseServer.LOGGER.debug("{} player={} dimension={} cells={} aperture={}",
                PROJECTION_MARKER, player.getNameForScoreboard(), projection.destination(),
                projection.cellCount(), projection.apertureOrigin().toShortString());
    }

    /** Tells a companion to drop a projection whose zone has gone. */
    public static void clearProjection(ServerPlayerEntity player, net.minecraft.util.math.BlockPos apertureOrigin) {
        if (apertureOrigin == null || !COMPANIONS.contains(player.getUuid())) {
            return;
        }
        ServerPlayNetworking.send(player, new CompanionPayloads.ProjectionClear(apertureOrigin));
    }
}
