package com.customdimensions.client;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The release that built this jar, read from the version {@code release.yml}
 * stamped into {@code fabric.mod.json} via {@code -Pmod_version}. It is the
 * whole of the companion protocol version, and the server's own answer to the
 * same question is {@code Artefacts.stackVersion()}.
 *
 * <p>The wire contract cannot hold this: both copies of
 * {@code CompanionPayloads} stay byte-identical and each side asks the loader
 * about a different mod id.
 */
public final class CompanionVersion {
    private static final String MOD_ID = "customdimensionsclient";

    private CompanionVersion() {}

    public static String current() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
