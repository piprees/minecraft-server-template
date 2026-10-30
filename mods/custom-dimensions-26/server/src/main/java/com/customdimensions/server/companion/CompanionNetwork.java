package com.customdimensions.server.companion;

import com.customdimensions.core.companion.ViewPreference;
import com.customdimensions.server.CustomDimensions;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The server half of the companion handshake, and the one gate every feed
 * payload leaves through. A player is a companion only once its client has
 * said hello with this exact release; anyone else, a vanilla client included,
 * is sent nothing from this package.
 */
public final class CompanionNetwork {
    public static final String ACCEPT_MARKER = "companion-accept:handshake";
    public static final String REFUSE_MARKER = "companion-refuse:handshake";
    public static final String VIEW_MARKER = "companion-accept:portal-view";
    public static final String PRELOADED_MARKER = "companion-send:preloaded-transfer";

    /** A companion: the player object it said hello on and what it last declared. */
    record Companion(ServerPlayer player, ViewPreference view) {
    }

    private static final Map<UUID, Companion> COMPANIONS = new ConcurrentHashMap<>();

    private CompanionNetwork() {
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(CompanionPayloads.Hello.TYPE, CompanionPayloads.Hello.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CompanionPayloads.PortalView.TYPE, CompanionPayloads.PortalView.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompanionPayloads.PreloadedTransfer.TYPE,
                CompanionPayloads.PreloadedTransfer.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompanionPayloads.PortalFrame.TYPE, CompanionPayloads.PortalFrame.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompanionPayloads.DestinationChunk.TYPE,
                CompanionPayloads.DestinationChunk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompanionPayloads.DestinationEntities.TYPE,
                CompanionPayloads.DestinationEntities.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompanionPayloads.EntityHandover.TYPE,
                CompanionPayloads.EntityHandover.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CompanionPayloads.ProjectionClear.TYPE,
                CompanionPayloads.ProjectionClear.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CompanionPayloads.Hello.TYPE,
                (payload, context) -> hello(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(CompanionPayloads.PortalView.TYPE,
                (payload, context) -> portalView(context.player(), payload));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            COMPANIONS.clear();
            CompanionFeed.clear();
        });
        CompanionFeed.register();
    }

    /** This jar's own release; a client is admitted only when its hello names the same. */
    public static String version() {
        return FabricLoader.getInstance().getModContainer(CustomDimensions.ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** A client's hello. Version skew degrades to vanilla, never to a hybrid. */
    public static void hello(ServerPlayer player, CompanionPayloads.Hello payload) {
        String own = version();
        String name = player.getName().getString();
        if (!ViewPreference.admits(own, payload.modVersion())) {
            forget(player.getUUID());
            CustomDimensions.LOG.info("{} player={} client={} server={}", REFUSE_MARKER, name, payload.modVersion(), own);
            return;
        }
        COMPANIONS.put(player.getUUID(), new Companion(player, ViewPreference.UNDECLARED));
        CustomDimensions.LOG.info("{} player={} version={}", ACCEPT_MARKER, name, own);
    }

    /** What a client says it draws; honoured only for an admitted companion. */
    public static void portalView(ServerPlayer player, CompanionPayloads.PortalView payload) {
        Companion held = COMPANIONS.get(player.getUUID());
        if (held == null) {
            return;
        }
        ViewPreference declared = new ViewPreference(payload.renderLocally(), payload.keepSlab(),
                payload.maxRenderDistance(), payload.viewDepth());
        COMPANIONS.put(player.getUUID(), new Companion(player, declared));
        if (!declared.equals(held.view())) {
            CustomDimensions.LOG.info("{} player={} rendersLocally={} maxRenderDistance={} viewDepth={}", VIEW_MARKER,
                    player.getName().getString(), declared.rendersLocally(), declared.maxRenderDistance(),
                    declared.viewDepth());
        }
    }

    public static boolean isCompanion(UUID id) {
        return COMPANIONS.containsKey(id);
    }

    /** What this player declared; {@link ViewPreference#UNDECLARED} for anyone else. */
    public static ViewPreference view(UUID id) {
        Companion c = COMPANIONS.get(id);
        return c == null ? ViewPreference.UNDECLARED : c.view();
    }

    /** Every admitted companion, by the player object it is live on now. */
    static Map<UUID, Companion> companions() {
        return COMPANIONS;
    }

    /** Drops a player's handshake and everything fed to it. */
    public static void forget(UUID id) {
        COMPANIONS.remove(id);
        CompanionFeed.forget(id);
    }

    /**
     * Sends one payload to one player if, and only if, that player is an
     * admitted companion. Answers whether it went.
     */
    static boolean send(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || payload == null || !COMPANIONS.containsKey(player.getUUID())) {
            return false;
        }
        ServerPlayNetworking.send(player, payload);
        return true;
    }

    /** Tells a companion its crossing lands in a resident destination, so the loading screen is ceremony. */
    public static void preloaded(ServerPlayer player, ResourceKey<Level> destination) {
        if (send(player, new CompanionPayloads.PreloadedTransfer(destination.identifier()))) {
            CustomDimensions.LOG.info("{} player={} dimension={}", PRELOADED_MARKER, player.getName().getString(),
                    destination.identifier());
        }
    }

    /** The bytes one payload occupies on the wire after its channel id. */
    static <T extends CustomPacketPayload> int size(T payload, StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                                   RegistryAccess access) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        try {
            codec.encode(buf, payload);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }
}
