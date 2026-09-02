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

    private static final Set<UUID> COMPANIONS = ConcurrentHashMap.newKeySet();

    private CompanionNetwork() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(
                CompanionPayloads.Hello.ID, CompanionPayloads.Hello.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.PreloadedTransfer.ID, CompanionPayloads.PreloadedTransfer.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(CompanionPayloads.Hello.ID,
                (payload, context) -> onHello(context.player().getUuid(), payload.protocolVersion()));
    }

    /** Version skew degrades to vanilla, never to a hybrid. */
    static void onHello(UUID playerId, int protocolVersion) {
        if (protocolVersion != CompanionPayloads.PROTOCOL_VERSION) {
            return;
        }
        COMPANIONS.add(playerId);
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
}
