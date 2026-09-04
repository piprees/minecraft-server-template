package com.customdimensions.client;

import com.customdimensions.client.config.RealtimeControls;
import com.customdimensions.client.config.RealtimeSettings;
import com.customdimensions.client.dev.DevBridge;
import com.customdimensions.client.realtime.DestinationChunks;
import com.customdimensions.client.realtime.DestinationWorlds;
import com.customdimensions.client.realtime.PortalFrames;
import com.customdimensions.client.realtime.RealtimeView;
import com.customdimensions.client.realtime.SpectatorPass;
import com.customdimensions.client.render.ProjectionRenderer;
import com.customdimensions.client.render.ProjectionStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for the Custom Dimensions companion client.
 *
 * The handshake goes out on a client tick, not from the JOIN callback:
 * {@code ClientPlayNetworking.send} routes through
 * {@code MinecraftClient.getNetworkHandler()}, which JOIN does not guarantee is
 * assigned, and Fabric swallows a throw from a JOIN callback into its own log.
 * A server that never answers leaves the flag unarmed, so every screen behaves
 * exactly as vanilla.
 */
public class CustomDimensionsClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("customdimensionsclient");

    /** Grepped in the client log to prove the entrypoint ran at all. */
    public static final String INIT_MARKER = "companion-client:initialised";

    /** Grepped in the client log to prove the handshake left this side. */
    public static final String HELLO_MARKER = "companion-client:hello-sent";

    /** Grepped in the client log to turn the screen check into an assertion. */
    public static final String SUPPRESS_MARKER = "companion-suppress:arrival-screen";

    /** Grepped in the client log to prove the declaration left this side. */
    public static final String VIEW_MARKER = "companion-client:portal-view";

    /** The world the store's source coordinates belong to. */
    private static ClientWorld boundWorld;

    /**
     * A settings change waiting for a tick to declare itself on. The change can
     * arrive from the dev bridge's own thread, and {@code getNetworkHandler}
     * is the render thread's to read.
     */
    private static volatile boolean viewDeclarationPending;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(
                CompanionPayloads.Hello.ID, CompanionPayloads.Hello.CODEC);
        PayloadTypeRegistry.playC2S().register(
                CompanionPayloads.PortalView.ID, CompanionPayloads.PortalView.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.PreloadedTransfer.ID, CompanionPayloads.PreloadedTransfer.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.Projection.ID, CompanionPayloads.Projection.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.PortalFrame.ID, CompanionPayloads.PortalFrame.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.DestinationChunk.ID, CompanionPayloads.DestinationChunk.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.ProjectionClear.ID, CompanionPayloads.ProjectionClear.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.PreloadedTransfer.ID,
                (payload, context) -> PendingTransfer.arm(payload.destination()));
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.Projection.ID,
                (payload, context) -> {
                    // One opening is described by one store or the other, never
                    // both, or the far side is drawn twice.
                    PortalFrames.remove(payload.apertureOrigin());
                    ProjectionStore.accept(payload);
                    LOGGER.info("{} dimension={} cells={} aperture={}", ProjectionStore.RECEIVE_MARKER,
                            payload.destination(), payload.cellCount(),
                            payload.apertureOrigin().toShortString());
                });
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.PortalFrame.ID,
                (payload, context) -> {
                    ProjectionStore.remove(payload.apertureOrigin());
                    RealtimeView.forget(payload.apertureOrigin());
                    boolean changed = PortalFrames.accept(payload);
                    // The destination's own world, centred on the arrival: a map
                    // centred anywhere else silently discards every chunk fed to
                    // it. See ChunkMapWindow.
                    net.minecraft.util.math.BlockPos origin = payload.apertureOrigin();
                    ClientWorld destination = DestinationWorlds.ensure(context.client(),
                            payload.destination(),
                            payload.dimensionType(),
                            RealtimeControls.settings().maxRenderDistance(),
                            com.customdimensions.client.realtime.ChunkMapWindow
                                    .centreChunk(origin.getX(), payload.dx()),
                            com.customdimensions.client.realtime.ChunkMapWindow
                                    .centreChunk(origin.getZ(), payload.dz()));
                    standDownIfRefused(PortalViewDeclaration.destinationRefused(
                            destination == null));
                    if (changed) {
                        LOGGER.info("{} dimension={} type={} aperture={} offset=({}, {}, {})",
                                PortalFrames.RECEIVE_MARKER, payload.destination(),
                                payload.dimensionType(), origin.toShortString(),
                                payload.dx(), payload.dy(), payload.dz());
                    }
                });
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.DestinationChunk.ID,
                (payload, context) -> {
                    int chunkX = payload.chunk().getChunkX();
                    int chunkZ = payload.chunk().getChunkZ();
                    boolean loaded = DestinationWorlds.load(payload.destination(), payload.chunk());
                    if (DestinationChunks.accept(payload.destination(), chunkX, chunkZ)) {
                        LOGGER.debug("{} dimension={} chunk={},{} received={} loaded={} inWorld={}",
                                DestinationChunks.RECEIVE_MARKER, payload.destination(),
                                chunkX, chunkZ, DestinationChunks.count(payload.destination()),
                                loaded, DestinationWorlds.loadedChunks(payload.destination()));
                    }
                });
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.ProjectionClear.ID,
                (payload, context) -> {
                    ProjectionStore.remove(payload.apertureOrigin());
                    PortalFrames.remove(payload.apertureOrigin());
                });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PendingTransfer.clear();
            ProjectionStore.clear();
            PortalFrames.clear();
            DestinationChunks.clear();
            DestinationWorlds.clear();
            RealtimeView.clear();
            SpectatorPass.reset();
            PortalViewDeclaration.clear();
            HandshakeSender.arm();
            // The join declaration rides with the handshake, which has to land
            // first; a pending one here would race ahead of it and be ignored.
            viewDeclarationPending = false;
        });

        ClientTickEvents.END_CLIENT_TICK.register(RealtimeView::tick);
        ClientTickEvents.END_CLIENT_TICK.register(CustomDimensionsClient::sendHelloWhenReady);
        ClientTickEvents.END_CLIENT_TICK.register(CustomDimensionsClient::declarePortalViewWhenPending);
        ClientTickEvents.END_CLIENT_TICK.register(CustomDimensionsClient::dropProjectionsOnWorldChange);
        // After the world-change reset, so a pass re-armed this tick is not read
        // as the failure it was re-armed from.
        ClientTickEvents.END_CLIENT_TICK.register(CustomDimensionsClient::standDownOnRenderFailure);
        WorldRenderEvents.BEFORE_ENTITIES.register(ProjectionRenderer::render);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PendingTransfer.clear();
            ProjectionStore.clear();
            PortalFrames.clear();
            DestinationChunks.clear();
            DestinationWorlds.clear();
            RealtimeView.clear();
            SpectatorPass.reset();
            PortalViewDeclaration.clear();
            HandshakeSender.disarm();
        });

        RealtimeControls.register();
        RealtimeControls.store().onChange(settings -> {
            // A player who has changed a setting is asking to be tried again.
            PortalViewDeclaration.clear();
            viewDeclarationPending = true;
        });
        DevBridge.start();

        LOGGER.info("{} protocol={} renderClientSidePortals={}", INIT_MARKER,
                CompanionPayloads.PROTOCOL_VERSION,
                RealtimeControls.settings().renderClientSidePortals());
    }

    /** Queues the one declaration a refusal owes the server. */
    private static void standDownIfRefused(boolean owed) {
        if (!owed) {
            return;
        }
        LOGGER.info("{} reason={}", PortalViewDeclaration.REFUSAL_MARKER,
                PortalViewDeclaration.reason());
        viewDeclarationPending = true;
    }

    /**
     * A projection addresses source-world block positions, so the same numbers
     * mean somewhere else the moment the client changes dimension.
     */
    private static void dropProjectionsOnWorldChange(MinecraftClient client) {
        if (client.world == boundWorld) {
            return;
        }
        boundWorld = client.world;
        ProjectionStore.clear();
        PortalFrames.clear();
        DestinationChunks.clear();
        DestinationWorlds.clear();
        RealtimeView.clear();
        SpectatorPass.reset();
        PortalViewDeclaration.clear();
    }

    /**
     * The local pass disabled itself after a throw, so this client can no
     * longer draw the far side and asks the server to describe it again.
     */
    private static void standDownOnRenderFailure(MinecraftClient client) {
        standDownIfRefused(PortalViewDeclaration.renderPassDisabled(SpectatorPass.disabled()));
    }

    private static void sendHelloWhenReady(MinecraftClient client) {
        if (!HandshakeSender.shouldSend(client.getNetworkHandler() != null)) {
            return;
        }
        // Diagnostic only. send() never consults canSend, so gating on it here
        // could suppress a send that would otherwise have succeeded.
        boolean declared = ClientPlayNetworking.canSend(CompanionPayloads.Hello.ID);
        try {
            ClientPlayNetworking.send(
                    new CompanionPayloads.Hello(CompanionPayloads.PROTOCOL_VERSION));
        } catch (RuntimeException e) {
            LOGGER.warn("companion handshake send failed, retrying while attempts remain", e);
            return;
        }
        HandshakeSender.sent();
        LOGGER.info("{} protocol={} serverDeclaredChannel={}",
                HELLO_MARKER, CompanionPayloads.PROTOCOL_VERSION, declared);
        // Sent here rather than from the pending flag: the handshake may take
        // several ticks to land, and a declaration that arrives ahead of it is
        // dropped by a server that does not yet know this client at all.
        declarePortalView();
    }

    /** A runtime settings change, once there is a connection to declare it on. */
    private static void declarePortalViewWhenPending(MinecraftClient client) {
        if (!viewDeclarationPending || client.getNetworkHandler() == null) {
            return;
        }
        viewDeclarationPending = false;
        declarePortalView();
    }

    /**
     * Tells the server what this client will draw for itself. A server that
     * never receives it keeps describing the far side, which is the behaviour
     * every vanilla client already has.
     */
    private static void declarePortalView() {
        RealtimeSettings settings = PortalViewDeclaration.declared(RealtimeControls.settings());
        boolean serverSide = settings.effectiveServerSide();
        try {
            ClientPlayNetworking.send(new CompanionPayloads.PortalView(
                    settings.renderClientSidePortals(), serverSide, settings.maxRenderDistance()));
        } catch (RuntimeException e) {
            LOGGER.warn("could not declare the portal view; the server keeps describing it", e);
            return;
        }
        LOGGER.info("{} renderLocally={} keepSlab={} maxRenderDistance={} refused={}", VIEW_MARKER,
                settings.renderClientSidePortals(), serverSide, settings.maxRenderDistance(),
                PortalViewDeclaration.refused());
    }
}
