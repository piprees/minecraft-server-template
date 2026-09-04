package com.customdimensions.client.realtime;

import com.customdimensions.client.CustomDimensionsClient;
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
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.dimension.DimensionType;

import java.util.BitSet;
import java.util.Iterator;
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
 * Vanilla's own light path is {@code ClientPlayNetworkHandler.updateLighting},
 * which takes the {@code LightingProvider} as a parameter and looks
 * world-agnostic. It is not: its last act is
 * {@code this.world.scheduleBlockRenders}, so invoking it would enqueue this
 * destination's light against the SOURCE world's renderers. The loop is
 * reproduced here instead, against public API only.
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
        return true;
    }

    /**
     * Vanilla's {@code readLightData}, against this world's own provider and
     * this world's own renderers. See the class comment for why the vanilla
     * method cannot be invoked instead.
     */
    private static void applyLight(ClientWorld world, int chunkX, int chunkZ, LightData data) {
        if (data == null) {
            return;
        }
        LightingProvider provider = world.getChunkManager().getLightingProvider();
        updateLighting(world, chunkX, chunkZ, provider, LightType.SKY,
                data.getInitedSky(), data.getUninitedSky(), data.getSkyNibbles().iterator());
        updateLighting(world, chunkX, chunkZ, provider, LightType.BLOCK,
                data.getInitedBlock(), data.getUninitedBlock(), data.getBlockNibbles().iterator());
        provider.setColumnEnabled(new ChunkPos(chunkX, chunkZ), true);
    }

    private static void updateLighting(ClientWorld world, int chunkX, int chunkZ,
            LightingProvider provider, LightType type, BitSet inited, BitSet uninited,
            Iterator<byte[]> nibbles) {
        int bottomSection = provider.getBottomY();
        for (LightSteps.Step step : LightSteps.of(provider.getHeight(), inited, uninited)) {
            int sectionY = bottomSection + step.section();
            provider.enqueueSectionData(type, ChunkSectionPos.from(chunkX, sectionY, chunkZ),
                    step.fromWire() ? new ChunkNibbleArray(nibbles.next().clone())
                            : new ChunkNibbleArray());
            world.scheduleBlockRenders(chunkX, sectionY, chunkZ);
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
