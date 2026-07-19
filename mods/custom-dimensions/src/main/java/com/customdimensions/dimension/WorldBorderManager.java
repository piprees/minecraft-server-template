package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-dimension world borders from config (v4 Phase 3): every world with a
 * DimensionConfig gets its vanilla WorldBorder set from borders.player at
 * boot (and runtime-created dimensions on load). This replaces the deploy
 * script's RCON worldborder/ChunkyBorder dance — ChunkyBorder remains
 * installed for PRE-GENERATION only; its border-enforcement role is gone.
 *
 * borders.generation is deliberately NOT applied here: it is metadata for
 * tooling (Chunky pre-gen bounds, BlueMap render bounds in deploy.sh).
 *
 * Ordering trap: vanilla links every createWorlds-time world's border to
 * the overworld's via WorldBorderListener.WorldBorderSyncer, and loads the
 * overworld's persisted border AFTER creating the secondary worlds — any
 * change to the overworld border propagates to nether/end. So boot-time
 * application happens ONCE at SERVER_STARTED, overworld first, children
 * after (their explicit values then stand until the overworld border next
 * changes, which nothing does any more — the deploy RCON dance is gone).
 * A borders.player of 0 means "no border" and leaves the world untouched.
 */
public final class WorldBorderManager {

    private static volatile boolean serverStarted = false;

    private WorldBorderManager() {
    }

    /** SERVER_STARTED: apply to every world, overworld first (syncer trap). */
    public static void applyAll(MinecraftServer server) {
        serverStarted = true;
        List<ServerWorld> worlds = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey() == World.OVERWORLD) {
                worlds.add(0, world);
            } else {
                worlds.add(world);
            }
        }
        int applied = 0;
        for (ServerWorld world : worlds) {
            if (apply(world)) {
                applied++;
            }
        }
        MultiverseServer.LOGGER.info("World borders applied from config to {} world(s)", applied);
    }

    /** ServerWorldEvents.LOAD: runtime-created dimensions (post-boot only —
     * boot-time loads are covered by applyAll AFTER vanilla's border-load). */
    public static void onWorldLoad(ServerWorld world) {
        if (serverStarted) {
            apply(world);
        }
    }

    static boolean apply(ServerWorld world) {
        DimensionConfig config = DifficultyManager.configFor(world.getRegistryKey());
        if (config == null) {
            return false;
        }
        int radius = config.getPlayerBorderRadius();
        if (radius <= 0) {
            return false; // 0 = explicitly borderless
        }
        WorldBorder border = world.getWorldBorder();
        double diameter = radius * 2.0;
        if (border.getCenterX() == 0.0 && border.getCenterZ() == 0.0
                && Math.abs(border.getSize() - diameter) < 1.0) {
            return true; // already right (persisted from a previous boot)
        }
        border.setCenter(0.0, 0.0);
        border.setSize(diameter);
        MultiverseServer.LOGGER.info("World border for {}: radius {} (from config)",
                world.getRegistryKey().getValue(), radius);
        return true;
    }

    /** Test seam. */
    static void resetForTest() {
        serverStarted = false;
    }
}
