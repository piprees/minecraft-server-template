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

    /** Grepped in the server log: which side is drawing this player's portals. */
    public static final String VIEW_MARKER = "companion-accept:portal-view";

    /** Grepped in the server log to prove the geometry-only send left this side. */
    public static final String FRAME_MARKER = "companion-send:portal-frame";

    private static final Set<UUID> COMPANIONS = ConcurrentHashMap.newKeySet();

    /**
     * What each companion says it draws for itself. Absent means the server
     * draws, which is what every vanilla client and every companion built
     * before this existed gets.
     */
    private static final java.util.Map<UUID, PortalViewPreference> VIEWS =
            new ConcurrentHashMap<>();

    private CompanionNetwork() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(
                CompanionPayloads.Hello.ID, CompanionPayloads.Hello.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.PreloadedTransfer.ID, CompanionPayloads.PreloadedTransfer.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.Projection.ID, CompanionPayloads.Projection.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.PortalFrame.ID, CompanionPayloads.PortalFrame.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.DestinationChunk.ID, CompanionPayloads.DestinationChunk.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.DestinationEntities.ID,
                CompanionPayloads.DestinationEntities.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.ProjectionClear.ID, CompanionPayloads.ProjectionClear.CODEC);

        PayloadTypeRegistry.playC2S().register(
                CompanionPayloads.PortalView.ID, CompanionPayloads.PortalView.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(CompanionPayloads.Hello.ID,
                (payload, context) -> onHello(context.player().getUuid(),
                        context.player().getNameForScoreboard(), payload.protocolVersion()));
        ServerPlayNetworking.registerGlobalReceiver(CompanionPayloads.PortalView.ID,
                (payload, context) -> onPortalView(context.player().getUuid(),
                        context.player().getNameForScoreboard(), payload));
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

    /**
     * A client saying what it will draw. Honoured only for a player whose
     * protocol version matched: without one, suppressing the description would
     * stop the far side reaching a client that has no other way to see it.
     */
    static void onPortalView(UUID playerId, String playerName, CompanionPayloads.PortalView payload) {
        if (!COMPANIONS.contains(playerId)) {
            return;
        }
        PortalViewPreference declared = new PortalViewPreference(
                payload.renderLocally(), payload.keepSlab(), payload.maxRenderDistance());
        PortalViewPreference held = VIEWS.put(playerId, declared);
        if (declared.equals(held)) {
            return;
        }
        MultiverseServer.LOGGER.info("{} player={} rendersLocally={} slab={} maxRenderDistance={}",
                VIEW_MARKER, playerName, declared.rendersLocally(),
                declared.streamsSlab() ? "streamed" : "suppressed", declared.maxRenderDistance());
    }

    public static void forget(UUID playerId) {
        COMPANIONS.remove(playerId);
        VIEWS.remove(playerId);
        DestinationFeed.forget(playerId);
        DestinationEntityFeed.forget(playerId);
    }

    public static boolean isCompanion(UUID playerId) {
        return COMPANIONS.contains(playerId);
    }

    /** What this player draws for itself; never null. */
    public static PortalViewPreference portalView(UUID playerId) {
        return VIEWS.getOrDefault(playerId, PortalViewPreference.SERVER_DRAWN);
    }

    /** Whether the server still has to describe a far side to this player. */
    public static boolean streamsSlab(UUID playerId) {
        return portalView(playerId).streamsSlab();
    }

    /** Drops every record (server shutdown). */
    public static void clear() {
        COMPANIONS.clear();
        VIEWS.clear();
        DestinationFeed.clear();
        DestinationEntityFeed.clear();
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
        if (!PortalHelper.isColumnResident(targetWorld, arrivalX, arrivalZ)) {
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

    /**
     * Hands one portal's geometry to a client that draws the destination
     * itself. Paired with the block description being skipped for the same
     * player: both address the same opening, and a client sent both would
     * draw the far side twice.
     */
    public static void sendPortalFrame(ServerPlayerEntity player, CompanionPayloads.PortalFrame frame) {
        if (frame == null || !COMPANIONS.contains(player.getUuid())) {
            return;
        }
        ServerPlayNetworking.send(player, frame);
        MultiverseServer.LOGGER.debug("{} player={} dimension={} aperture={} offset=({}, {}, {})",
                FRAME_MARKER, player.getNameForScoreboard(), frame.destination(),
                frame.apertureOrigin().toShortString(), frame.dx(), frame.dy(), frame.dz());
    }

    /** Tells a companion to drop a projection whose zone has gone. */
    public static void clearProjection(ServerPlayerEntity player, net.minecraft.util.math.BlockPos apertureOrigin) {
        if (apertureOrigin == null || !COMPANIONS.contains(player.getUuid())) {
            return;
        }
        ServerPlayNetworking.send(player, new CompanionPayloads.ProjectionClear(apertureOrigin));
    }
}
