package com.customdimensions.server.portal;

import com.customdimensions.server.CustomDimensions;
import java.lang.reflect.Method;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Chunk-claim lookups against Open Parties and Claims' server API
 * ({@code xaero.pac.common.server.api.OpenPACServerAPI}), by reflection so the
 * mod stays optional.
 * <p>
 * Fails open: an absent mod, an API that does not resolve or a lookup that
 * throws all answer "not claimed", because failing closed would switch auras
 * off on any upstream rename. A throwing lookup disables protection after one
 * WARN. An aura pass makes at most {@code blocksPerPass} lookups per
 * {@code interval}, so nothing here is cached.
 */
public final class ClaimsCompat {
    static final String MOD_ID = "openpartiesandclaims";

    private static boolean resolved;
    private static boolean available;
    private static Method apiGet;
    private static Method claimsManager;
    private static Method claimAt;

    private ClaimsCompat() {
    }

    /** Whether the cell is inside a claimed chunk; false whenever that cannot be established. */
    public static boolean isClaimed(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !ensureResolved()) {
            return false;
        }
        try {
            Object manager = manager(level.getServer());
            return manager != null && claimAt.invoke(manager, level.dimension().identifier(), pos) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            available = false;
            CustomDimensions.LOG.warn("Portal auras: claim protection disabled, the claim lookup failed ({})", e.toString());
            return false;
        }
    }

    /** Whether claim protection is live. */
    public static boolean isAvailable() {
        return ensureResolved();
    }

    /** Self-test hook: claims the chunk holding {@code pos} for {@code owner}; false when it could not. */
    static boolean claim(ServerLevel level, BlockPos pos, UUID owner) {
        if (!ensureResolved()) {
            return false;
        }
        try {
            Object manager = manager(level.getServer());
            Method claim = claimAt.getDeclaringClass().getMethod("claim", Identifier.class, UUID.class, int.class, int.class,
                    int.class, boolean.class);
            return claim.invoke(manager, level.dimension().identifier(), owner, 0, pos.getX() >> 4, pos.getZ() >> 4,
                    false) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            CustomDimensions.LOG.warn("Portal auras: test claim failed ({})", e.toString());
            return false;
        }
    }

    /** Self-test hook: releases the chunk holding {@code pos}. */
    static void unclaim(ServerLevel level, BlockPos pos) {
        if (!ensureResolved()) {
            return;
        }
        try {
            Object manager = manager(level.getServer());
            Method unclaim = claimAt.getDeclaringClass().getMethod("unclaim", Identifier.class, int.class, int.class);
            unclaim.invoke(manager, level.dimension().identifier(), pos.getX() >> 4, pos.getZ() >> 4);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CustomDimensions.LOG.warn("Portal auras: test unclaim failed ({})", e.toString());
        }
    }

    private static Object manager(MinecraftServer server) throws ReflectiveOperationException {
        Object api = server == null ? null : apiGet.invoke(null, server);
        return api == null ? null : claimsManager.invoke(api);
    }

    private static boolean ensureResolved() {
        if (resolved) {
            return available;
        }
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            CustomDimensions.LOG.info("Portal auras: {} is not installed, claim protection inactive", MOD_ID);
            return false;
        }
        try {
            Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Class<?> manager = Class.forName("xaero.pac.common.server.claims.api.IServerClaimsManagerAPI");
            apiGet = api.getMethod("get", MinecraftServer.class);
            claimsManager = api.getMethod("getServerClaimsManager");
            claimAt = manager.getMethod("get", Identifier.class, BlockPos.class);
            available = true;
            CustomDimensions.LOG.info("Portal auras: claim protection active via {}", MOD_ID);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CustomDimensions.LOG.warn("Portal auras: {} is installed but its server API did not resolve ({}), "
                    + "claim protection inactive", MOD_ID, e.toString());
            available = false;
        }
        return available;
    }
}
