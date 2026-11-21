package com.customdimensions.server.lod;

import com.customdimensions.server.CustomDimensions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * The optional Distant Horizons integration's door: touches no DH class
 * itself, and loads {@link DhLodGenerators} and {@link DhLodSelfTest} only
 * when DH is installed, so the mod runs the same without it.
 */
public final class DistantHorizonsLods {
    public static final String MOD_ID = "distanthorizons";

    private DistantHorizonsLods() {}

    public static boolean installed() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** Binds the LOD generator to every level DH loads, when DH is installed. */
    public static void register() {
        if (!installed()) return;
        try {
            DhLodGenerators.register();
        } catch (LinkageError | RuntimeException e) {
            CustomDimensions.LOG.warn("Distant Horizons is installed but its API did not take the LOD generator ({}); "
                    + "DH generates its own LODs", e.toString());
        }
    }

    /** The self-test's LOD section: no lines without DH. */
    public static CompletableFuture<List<String>> selfTest(MinecraftServer server) {
        if (!installed()) return CompletableFuture.completedFuture(List.of());
        return DhLodSelfTest.run(server);
    }
}
