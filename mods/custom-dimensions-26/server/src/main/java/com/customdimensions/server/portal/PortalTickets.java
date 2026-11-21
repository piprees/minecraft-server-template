package com.customdimensions.server.portal;

import com.customdimensions.server.CustomDimensions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Chunk tickets on arrival sites and exit portals: 26.x keeps no spawn
 * chunks, so a site an entity is travelling to is loaded and kept ticking by
 * a ticket of ours (TROUBLESHOOTING.md T117). Each expires on its own after
 * {@link #TIMEOUT_TICKS}, and every one in a level is removed before
 * {@code LevelManager} unloads it, since a ticket keeps a Fantasy level open (T118).
 */
public final class PortalTickets {
    public static final long TIMEOUT_TICKS = 300;
    /** Radius 2 makes the centre chunk entity-ticking; radius 1 loads it but never ticks an entity in it. */
    public static final int RADIUS = 2;
    public static final TicketType ARRIVAL = new TicketType(TIMEOUT_TICKS,
            TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

    private static final Map<ResourceKey<Level>, Set<Long>> HELD = new HashMap<>();

    private PortalTickets() {
    }

    static void register() {
        Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(CustomDimensions.ID, "portal_arrival"),
                ARRIVAL);
    }

    /** Loads and holds the chunk under ({@code x}, {@code z}); repeated calls refresh the timeout. */
    public static void hold(ServerLevel level, int x, int z) {
        ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
        level.getChunkSource().addTicketAndLoadWithRadius(ARRIVAL, chunk, RADIUS);
        HELD.computeIfAbsent(level.dimension(), k -> new HashSet<>()).add(chunk.pack());
    }

    /** Removes every ticket this class placed in {@code level}. */
    public static int release(ServerLevel level) {
        Set<Long> chunks = HELD.remove(level.dimension());
        if (chunks == null) {
            return 0;
        }
        for (long packed : chunks) {
            level.getChunkSource().removeTicketWithRadius(ARRIVAL, ChunkPos.unpack(packed), RADIUS);
        }
        return chunks.size();
    }

    public static int held(ResourceKey<Level> level) {
        return HELD.getOrDefault(level, Set.of()).size();
    }

    static void clear() {
        HELD.clear();
    }
}
