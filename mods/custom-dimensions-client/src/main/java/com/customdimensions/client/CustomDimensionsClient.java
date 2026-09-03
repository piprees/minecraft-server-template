package com.customdimensions.client;

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

    /** The world the store's source coordinates belong to. */
    private static ClientWorld boundWorld;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(
                CompanionPayloads.Hello.ID, CompanionPayloads.Hello.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.PreloadedTransfer.ID, CompanionPayloads.PreloadedTransfer.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.Projection.ID, CompanionPayloads.Projection.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.ProjectionClear.ID, CompanionPayloads.ProjectionClear.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.PreloadedTransfer.ID,
                (payload, context) -> PendingTransfer.arm(payload.destination()));
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.Projection.ID,
                (payload, context) -> {
                    ProjectionStore.accept(payload);
                    LOGGER.info("{} dimension={} cells={} aperture={}", ProjectionStore.RECEIVE_MARKER,
                            payload.destination(), payload.cellCount(),
                            payload.apertureOrigin().toShortString());
                });
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.ProjectionClear.ID,
                (payload, context) -> ProjectionStore.remove(payload.apertureOrigin()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PendingTransfer.clear();
            ProjectionStore.clear();
            HandshakeSender.arm();
        });

        ClientTickEvents.END_CLIENT_TICK.register(CustomDimensionsClient::sendHelloWhenReady);
        ClientTickEvents.END_CLIENT_TICK.register(CustomDimensionsClient::dropProjectionsOnWorldChange);
        WorldRenderEvents.BEFORE_ENTITIES.register(ProjectionRenderer::render);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PendingTransfer.clear();
            ProjectionStore.clear();
            HandshakeSender.disarm();
        });

        LOGGER.info("{} protocol={}", INIT_MARKER, CompanionPayloads.PROTOCOL_VERSION);
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
    }
}
