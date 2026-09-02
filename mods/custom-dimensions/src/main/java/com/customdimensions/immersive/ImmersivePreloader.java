package com.customdimensions.immersive;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalHelper;
import com.customdimensions.portal.PortalShape;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-generates arrival chunks around an immersive portal's target the first
 * time a player approaches it, so the target world's terrain is ready
 * before the player steps through.
 *
 * <p>{@link #preloadIfNeeded} runs from {@code ServerWorldMixin.onTick} and
 * so must never block: it registers portal chunk tickets and lets the chunk
 * system generate on its own schedule. A synchronous {@code getChunk} here
 * parks the main thread on a future nothing is working on ([K1]/[K6]).
 */
public final class ImmersivePreloader {
    /** Chunk radius to pre-generate around the arrival column (2 = 5x5 = 25 chunks). */
    private static final int PRELOAD_RADIUS = 2;

    // Dedupe key is per ZONE (source world + interior centre), not per
    // world — multiple portals can target the same world. Keyed by TARGET
    // world first so the idle unloader's ServerWorldEvents.UNLOAD listener
    // can invalidate exactly the right entries when a pre-loaded-but-
    // unvisited world is closed.
    private static final Map<RegistryKey<World>, Set<String>> PRELOADED = new ConcurrentHashMap<>();

    private ImmersivePreloader() {
    }

    /**
     * Pre-generates the arrival chunk grid for one zone's target, once per
     * session. No-op on repeat calls for the same zone (idempotent —
     * dedupe key survives until the target world is unloaded or the
     * server shuts down).
     */
    public static void preloadIfNeeded(ServerWorld targetWorld, PortalHelper.PortalZone zone, PortalDefinition def) {
        BlockPos centre = PortalShape.centreOf(zone.interior);
        if (centre == null) {
            return;
        }
        RegistryKey<World> targetKey = targetWorld.getRegistryKey();
        String key = keyFor(zone, centre);
        Set<String> keysForTarget = PRELOADED.computeIfAbsent(targetKey, k -> ConcurrentHashMap.newKeySet());
        if (!keysForTarget.add(key)) {
            return;
        }
        MultiverseServer.LOGGER.info("immersive: proximity pre-load triggered for zone in {} -> {}",
                zone.sourceWorld.getValue(), targetKey.getValue());

        int cx;
        int cz;
        if (def.hasAnchor()) {
            int[] anchor = def.getAnchorPos();
            cx = anchor[0] >> 4;
            cz = anchor[2] >> 4;
        } else {
            double scale = def.getScale();
            cx = (int) Math.round(centre.getX() * scale) >> 4;
            cz = (int) Math.round(centre.getZ() * scale) >> 4;
        }

        for (int dx = -PRELOAD_RADIUS; dx <= PRELOAD_RADIUS; dx++) {
            for (int dz = -PRELOAD_RADIUS; dz <= PRELOAD_RADIUS; dz++) {
                ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                targetWorld.getChunkManager().addTicket(
                        ChunkTicketType.PORTAL, pos, 1, pos.getStartPos());
            }
        }
        MultiverseServer.LOGGER.debug(
                "immersive: requested {} arrival chunks around ({}, {}) in {} for zone {}",
                (2 * PRELOAD_RADIUS + 1) * (2 * PRELOAD_RADIUS + 1), cx, cz, targetKey.getValue(), key);
    }

    /** Whether this zone's target has had its arrival chunks requested this session. */
    public static boolean hasPreloaded(RegistryKey<World> targetWorld, PortalHelper.PortalZone zone) {
        BlockPos centre = PortalShape.centreOf(zone.interior);
        Set<String> keys = PRELOADED.get(targetWorld);
        return centre != null && keys != null && keys.contains(keyFor(zone, centre));
    }

    // One expression, both callers: a second copy of this format would drift
    // and hasPreloaded would silently answer false forever.
    private static String keyFor(PortalHelper.PortalZone zone, BlockPos centre) {
        return zone.sourceWorld.getValue() + "|" + centre.toShortString();
    }

    /**
     * Drops every pre-load record for one target world — called from the
     * {@code ServerWorldEvents.UNLOAD} listener so a world closed by the
     * idle unloader re-triggers pre-loading on the next approach instead
     * of silently no-opping forever.
     */
    public static void invalidate(RegistryKey<World> targetWorld) {
        PRELOADED.remove(targetWorld);
    }

    /** Resets all session state (server shutdown). */
    public static void clear() {
        PRELOADED.clear();
    }
}
