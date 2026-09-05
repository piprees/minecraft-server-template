package com.customdimensions.client.realtime;

import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.mixin.ClientPlayNetworkHandlerLightAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.dimension.DimensionType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The destination dimensions this client holds locally, one {@link ClientWorld}
 * per destination however many portals lead there.
 *
 * <h2>Each destination owns its renderer</h2>
 * A destination's {@link WorldRenderer} is given that world with
 * {@code setWorld} and the client's own is put straight back into the ONE
 * shared {@code EntityRenderDispatcher} the call re-points. Both halves are
 * load-bearing: without the first, every fed chunk NPEs in the renderer's
 * chunk-rendering state; without the second, the player's own world renders
 * its entities against a dimension they are not in.
 *
 * <h2>The centring trap</h2>
 * A chunk map centred where a world was constructed accepts nothing near a
 * distant arrival: {@code loadChunkFromPacket} warns "Ignoring chunk since
 * it's not in the view range" and returns null. The map is centred on the
 * arrival the moment a world is stood up, and moved when the arrival does.
 * {@link ChunkMapWindow} holds the arithmetic.
 *
 * <h2>Light</h2>
 * Light goes in through vanilla's own {@code readLightData} with the handler's
 * world swapped to the destination, so Sodium's injection on that method flags
 * the destination's chunk tracker. Without that flag no fed chunk ever becomes
 * a render section and the second pass draws sky only.
 * {@link ClientPlayNetworkHandlerLightAccessor} holds the reasoning.
 */
public final class DestinationWorlds {

    /** Grepped in the client log when a destination is stood up or dropped. */
    public static final String WORLD_MARKER = "companion-client:destination-world";

    /** Grepped in the client log when a destination cannot be stood up. */
    public static final String REFUSED_MARKER = "companion-client:destination-refused";

    private static final Map<Identifier, ClientWorld> WORLDS = new ConcurrentHashMap<>();

    /** Each destination's own renderer, so dropping one frees its chunk builder. */
    private static final Map<Identifier, WorldRenderer> RENDERERS = new ConcurrentHashMap<>();

    /** Destinations already refused, so the reason is logged once, not per chunk. */
    private static final java.util.Set<Identifier> REFUSED = ConcurrentHashMap.newKeySet();

    private DestinationWorlds() {}

    public static ClientWorld get(Identifier destination) {
        return destination == null ? null : WORLDS.get(destination);
    }

    public static int count() {
        return WORLDS.size();
    }

    /** The renderer holding this destination's chunk state, for a second pass. */
    public static WorldRenderer rendererFor(Identifier destination) {
        return destination == null ? null : RENDERERS.get(destination);
    }

    /**
     * How many render sections this destination's SPECTATOR renderer last drew.
     * It says nothing about the aperture path, which draws the far side in its
     * DESTINATION_FAR stage and leaves this at zero. With {@code spectatorPass}
     * off — the shipped default — there is no renderer and this is always 0,
     * at a portal that renders correctly and at one that does not alike.
     */
    public static int renderedSections(Identifier destination) {
        WorldRenderer renderer = rendererFor(destination);
        return renderer == null ? 0 : renderer.getCompletedChunkCount();
    }

    /** Render sections drawn across every standing destination. */
    public static int renderedSections() {
        int total = 0;
        for (WorldRenderer renderer : RENDERERS.values()) {
            total += renderer.getCompletedChunkCount();
        }
        return total;
    }

    /** How many chunks this destination's own manager is holding. */
    public static int loadedChunks(Identifier destination) {
        ClientWorld world = get(destination);
        return world == null ? 0 : world.getChunkManager().getLoadedChunkCount();
    }

    /**
     * The world for one destination, standing it up on first sight.
     *
     * <p>Null when this client has no {@code DimensionType} for it. That is
     * the honest outcome for a dimension created after the player joined,
     * whose type never reached their registry — the server keeps describing
     * that portal instead, and the reason is logged once.
     */
    public static ClientWorld ensure(MinecraftClient client, Identifier destination,
            Identifier dimensionType, int feedRadius, int centreChunkX, int centreChunkZ) {
        if (client == null || destination == null || dimensionType == null) {
            return null;
        }
        ClientWorld held = WORLDS.get(destination);
        if (held != null) {
            held.getChunkManager().setChunkMapCenter(centreChunkX, centreChunkZ);
            return held;
        }
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) {
            return null;
        }
        RegistryEntry<DimensionType> type = handler.getRegistryManager()
                .get(RegistryKeys.DIMENSION_TYPE)
                .getEntry(RegistryKey.of(RegistryKeys.DIMENSION_TYPE, dimensionType))
                .orElse(null);
        if (type == null) {
            if (REFUSED.add(destination)) {
                CustomDimensionsClient.LOGGER.info(
                        "{} dimension={} type={} reason=type-not-in-this-client's-registry",
                        REFUSED_MARKER, destination, dimensionType);
            }
            return null;
        }
        int loadDistance = ChunkMapWindow.loadDistanceFor(feedRadius);
        WorldRenderer renderer = new WorldRenderer(client, client.getEntityRenderDispatcher(),
                client.getBlockEntityRenderDispatcher(), client.getBufferBuilders());
        ClientWorld made = new ClientWorld(
                handler,
                new ClientWorld.Properties(Difficulty.NORMAL, false, false),
                RegistryKey.of(RegistryKeys.WORLD, destination),
                type,
                loadDistance,
                loadDistance,
                client::getProfiler,
                renderer,
                false,
                0L);
        adopt(client, renderer, made);
        made.getChunkManager().setChunkMapCenter(centreChunkX, centreChunkZ);
        WORLDS.put(destination, made);
        RENDERERS.put(destination, renderer);
        CustomDimensionsClient.LOGGER.info("{} dimension={} type={} loadDistance={} centre={},{}",
                WORLD_MARKER, destination, dimensionType, loadDistance, centreChunkX, centreChunkZ);
        return made;
    }

    /**
     * Gives the destination's renderer its world, then puts the client's own
     * back where the call moved it.
     *
     * <p>{@code loadChunkFromPacket} ends inside the renderer's
     * {@code ChunkRenderingDataPreparer}, whose state is null until
     * {@code setWorld} builds it, so an unadopted renderer NPEs on every fed
     * chunk. {@code setWorld} also re-points the ONE shared
     * {@code EntityRenderDispatcher}; left there, the player's own world would
     * render its entities against a dimension they are not in.
     */
    private static void adopt(MinecraftClient client, WorldRenderer renderer, ClientWorld made) {
        renderer.setWorld(made);
        client.getEntityRenderDispatcher().setWorld(client.world);
    }

    /**
     * Loads one fed chunk into its destination. Returns false when there is no
     * world for it, or when vanilla refused it as out of range.
     */
    public static boolean load(Identifier destination, ChunkDataS2CPacket packet) {
        ClientWorld world = get(destination);
        if (world == null || packet == null) {
            return false;
        }
        int chunkX = packet.getChunkX();
        int chunkZ = packet.getChunkZ();
        ChunkData data = packet.getChunkData();
        WorldChunk chunk = world.getChunkManager().loadChunkFromPacket(
                chunkX, chunkZ, data.getSectionsDataBuf(), data.getHeightmap(),
                data.getBlockEntities(chunkX, chunkZ));
        if (chunk == null) {
            return false;
        }
        applyLight(world, chunkX, chunkZ, packet.getLightData());
        markRenderable(world, chunk, chunkX, chunkZ);
        return true;
    }

    /**
     * The second half of vanilla's own chunk load, which
     * {@code loadChunkFromPacket} does not do:
     * {@code ClientPlayNetworkHandler.scheduleRenderChunk}, run after the light
     * as vanilla's chunk-update queue runs it. Without it every fed section
     * stays flagged not-ready, nothing reaches the chunk builder, and the
     * destination pass draws sky. {@link SectionRenderStatus} holds the loop.
     */
    private static void markRenderable(ClientWorld world, WorldChunk chunk,
            int chunkX, int chunkZ) {
        LightingProvider lighting = world.getChunkManager().getLightingProvider();
        ChunkSection[] sections = chunk.getSectionArray();
        ChunkPos pos = chunk.getPos();
        boolean[] empty = new boolean[sections.length];
        for (int index = 0; index < sections.length; index++) {
            empty[index] = sections[index].isEmpty();
        }
        SectionRenderStatus.mark(chunkX, chunkZ, world.getBottomSectionCoord(), empty,
                new SectionRenderStatus.Sink() {
                    @Override
                    public void sectionStatus(int sectionY, boolean sectionEmpty) {
                        lighting.setSectionStatus(ChunkSectionPos.from(pos, sectionY),
                                sectionEmpty);
                    }

                    @Override
                    public void scheduleBlockRenders(int x, int sectionY, int z) {
                        world.scheduleBlockRenders(x, sectionY, z);
                    }
                });
    }

    /**
     * Runs vanilla's {@code readLightData} with the handler pointed at this
     * destination, then puts the source world straight back.
     *
     * <p>The method reads {@code this.world} for the lighting provider and for
     * {@code scheduleBlockRenders}, so the swap is what makes both this
     * world's. Sodium's injection on the same method reads the same field, so
     * the swap is also what puts {@code FLAG_HAS_LIGHT_DATA} on this world's
     * chunk tracker — the flag every render section is gated on.
     */
    private static void applyLight(ClientWorld world, int chunkX, int chunkZ, LightData data) {
        if (data == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler handler = client == null ? null : client.getNetworkHandler();
        if (handler == null) {
            return;
        }
        ClientPlayNetworkHandlerLightAccessor access =
                (ClientPlayNetworkHandlerLightAccessor) handler;
        ClientWorld source = access.customdimensionsclient$world();
        access.customdimensionsclient$setWorld(world);
        try {
            access.customdimensionsclient$readLightData(chunkX, chunkZ, data);
        } finally {
            access.customdimensionsclient$setWorld(source);
        }
    }

    /** Drops one destination and everything held for it. */
    public static void drop(Identifier destination) {
        ClientWorld gone = destination == null ? null : WORLDS.remove(destination);
        if (gone == null) {
            return;
        }
        WorldRenderer renderer = RENDERERS.remove(destination);
        if (renderer != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            renderer.setWorld(null);
            if (client != null) {
                client.getEntityRenderDispatcher().setWorld(client.world);
            }
        }
        DestinationChunks.drop(destination);
        DestinationEntities.drop(destination);
        CustomDimensionsClient.LOGGER.info("{} dimension={} dropped", WORLD_MARKER, destination);
    }

    /** Drops every destination (world change, disconnect). */
    public static void clear() {
        for (Identifier destination : List.copyOf(WORLDS.keySet())) {
            drop(destination);
        }
        WORLDS.clear();
        RENDERERS.clear();
        REFUSED.clear();
    }

    /** Test seam and diagnostics: what is standing, with its chunk count. */
    public static Map<Identifier, Integer> loadedCounts() {
        Map<Identifier, Integer> out = new java.util.HashMap<>();
        WORLDS.forEach((destination, world) ->
                out.put(destination, world.getChunkManager().getLoadedChunkCount()));
        return out;
    }
}
