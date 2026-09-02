package com.customdimensions.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for the Custom Dimensions companion client.
 *
 * Announces itself once per join and holds the one-shot preloaded flag the
 * loading-screen mixin reads. A server that never answers leaves the flag
 * unarmed, so every screen behaves exactly as vanilla.
 */
public class CustomDimensionsClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("customdimensionsclient");

    /** Grepped in the client log to turn the screen check into an assertion. */
    public static final String SUPPRESS_MARKER = "companion-suppress:arrival-screen";

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(
                CompanionPayloads.Hello.ID, CompanionPayloads.Hello.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionPayloads.PreloadedTransfer.ID, CompanionPayloads.PreloadedTransfer.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.PreloadedTransfer.ID,
                (payload, context) -> PendingTransfer.arm(payload.destination()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PendingTransfer.clear();
            ClientPlayNetworking.send(
                    new CompanionPayloads.Hello(CompanionPayloads.PROTOCOL_VERSION));
        });

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> PendingTransfer.clear());
    }
}
