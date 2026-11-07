package com.customdimensions.client;

import com.customdimensions.client.dev.Rig;
import com.customdimensions.client.feed.Feed;
import com.customdimensions.client.view.PortalRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The companion's entry point: says hello with this release, declares that it
 * draws the far side itself, keeps what the server feeds, and draws it in the
 * opening. A server that never answers, or answers with another release,
 * feeds nothing and the game looks and plays as vanilla.
 */
public final class CompanionClient implements ClientModInitializer {
    public static final String ID = "customdimensionsclient";
    public static final Logger LOG = LoggerFactory.getLogger("customdimensions-companion");
    /** Ticks between feed count lines while anything is held: ten seconds. */
    static final int REPORT_TICKS = 200;

    private static int ticks;

    @Override
    public void onInitializeClient() {
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

        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.PortalFrame.TYPE, (p, c) -> Feed.frame(p));
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.DestinationChunk.TYPE, (p, c) -> Feed.chunk(p));
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.DestinationEntities.TYPE, (p, c) -> Feed.entities(p));
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.EntityHandover.TYPE, (p, c) -> Feed.handover(p));
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.ProjectionClear.TYPE, (p, c) -> Feed.clear(p));
        ClientPlayNetworking.registerGlobalReceiver(CompanionPayloads.PreloadedTransfer.TYPE,
                (p, c) -> LOG.info("companion-feed: preloaded {}", p.destination()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> join(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Feed.reset();
            PortalRenderer.reset();
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(PortalRenderer::submit);
        ClientTickEvents.END_CLIENT_TICK.register(CompanionClient::tick);
        Rig.register();
    }

    /** This jar's release: the server admits a hello naming exactly its own. */
    public static String version() {
        return FabricLoader.getInstance().getModContainer(ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** Whether this run says hello at all; {@code -Dcustomdimensions.companion=off} plays as an unmodded client. */
    public static boolean enabled() {
        return !"off".equals(System.getProperty("customdimensions.companion", "on"));
    }

    /** Whether the far side is drawn this frame. */
    public static boolean drawing() {
        return enabled() && !ShaderGuard.blocks();
    }

    private static void join(Minecraft client) {
        Feed.reset();
        PortalRenderer.reset();
        if (!enabled()) {
            LOG.info("companion-hello: off for this run, nothing sent");
            return;
        }
        if (!ClientPlayNetworking.canSend(CompanionPayloads.Hello.TYPE)) {
            LOG.info("companion-hello: this server has no companion feed");
            return;
        }
        int distance = client.options.renderDistance().get();
        ClientPlayNetworking.send(new CompanionPayloads.Hello(version()));
        ClientPlayNetworking.send(new CompanionPayloads.PortalView(true, false, distance, 48));
        LOG.info("companion-hello: version={} renderLocally=true maxRenderDistance={} viewDepth=48", version(), distance);
    }

    private static void tick(Minecraft client) {
        if (client.level == null || ++ticks % REPORT_TICKS != 0) {
            return;
        }
        if (Feed.framesReceived == 0 && Feed.chunksReceived == 0) {
            return;
        }
        LOG.info("companion-feed: frames={} received chunks={} held chunks={} entity snapshots={} handovers={} "
                        + "held entities={} clears={} drawn openings={} drawn faces={} last faces={}",
                Feed.frames().size(), Feed.chunksReceived, Feed.chunksHeld(), Feed.entitySnapshots, Feed.handovers,
                Feed.entitiesHeld(), Feed.clears, PortalRenderer.openingsDrawn, PortalRenderer.facesDrawn,
                PortalRenderer.lastFaces);
    }
}
