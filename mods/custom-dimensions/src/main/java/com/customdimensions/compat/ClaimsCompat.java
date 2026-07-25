package com.customdimensions.compat;

import com.customdimensions.MultiverseServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;

/**
 * Chunk-claim lookups against Open Parties and Claims, reflectively.
 *
 * <p>OPAC ships a real server-side API ({@code xaero.pac.common.server.api
 * .OpenPACServerAPI}), which is what this calls — no reaching into
 * internals. It is reached by reflection rather than a compile dependency
 * because the mod is optional: consumers assemble their own mod list, and
 * an absent claims mod must degrade to "nothing is claimed", never to a
 * missing class at load time.
 *
 * <p><b>Fails open, and that is deliberate.</b> Every failure path here —
 * mod absent, API moved, lookup threw — answers "not claimed", which
 * leaves the aura behaving exactly as it did before claims existed. The
 * alternative (fail closed, treat everything as claimed) would silently
 * switch the whole feature off on any upstream rename, and a cosmetic
 * feature quietly not happening is this codebase's most expensive failure
 * mode. A throwing lookup disables itself permanently after one WARN
 * rather than logging once per aura pass forever.
 *
 * <p>Call volume is tiny by construction: an aura pass makes at most
 * {@code blocksPerPass} (default 2) lookups every {@code interval}
 * (default 40) ticks per portal side, so this is nowhere near a hot path
 * and needs no cache.
 */
public final class ClaimsCompat {

    private static final String MOD_ID = "openpartiesandclaims";

    private static boolean resolved;
    private static boolean available;
    private static Method apiGet;
    private static Method claimsManagerGet;
    private static Method claimAt;

    private ClaimsCompat() {
    }

    /**
     * Is this position inside a claimed chunk? False whenever the answer
     * cannot be established, including when no claims mod is installed.
     */
    public static boolean isClaimed(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null || !ensureResolved()) {
            return false;
        }
        MinecraftServer server = world.getServer();
        if (server == null) {
            return false;
        }
        try {
            Object api = apiGet.invoke(null, server);
            if (api == null) {
                return false;
            }
            Object manager = claimsManagerGet.invoke(api);
            if (manager == null) {
                return false;
            }
            Identifier dimension = world.getRegistryKey().getValue();
            return claimAt.invoke(manager, dimension, pos) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable("claim lookup failed (" + e + ")");
            return false;
        }
    }

    /** Whether claim protection is live — for boot logging and tests. */
    public static boolean isAvailable() {
        return ensureResolved();
    }

    private static boolean ensureResolved() {
        if (resolved) {
            return available;
        }
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            MultiverseServer.LOGGER.info(
                    "Portal auras: {} is not installed — claim protection inactive", MOD_ID);
            return false;
        }
        try {
            Class<?> apiClass = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            Class<?> managerClass =
                    Class.forName("xaero.pac.common.server.claims.api.IServerClaimsManagerAPI");
            apiGet = apiClass.getMethod("get", MinecraftServer.class);
            claimsManagerGet = apiClass.getMethod("getServerClaimsManager");
            claimAt = managerClass.getMethod("get", Identifier.class, BlockPos.class);
            available = true;
            MultiverseServer.LOGGER.info(
                    "Portal auras: claim protection active via {}", MOD_ID);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MultiverseServer.LOGGER.warn(
                    "Portal auras: {} is installed but its server API did not resolve ({}) — "
                            + "claim protection inactive", MOD_ID, e.toString());
            available = false;
        }
        return available;
    }

    private static void disable(String reason) {
        available = false;
        MultiverseServer.LOGGER.warn("Portal auras: claim protection disabled — {}", reason);
    }

    /** Test hook: forget what was resolved so the next call re-resolves. */
    static void reset() {
        resolved = false;
        available = false;
        apiGet = null;
        claimsManagerGet = null;
        claimAt = null;
    }
}
