package com.customdimensions.compat;

import com.customdimensions.MultiverseServer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;

import java.lang.reflect.Method;

/**
 * Unwraps Lithostitched's {@code InjectorBiomeSource} to reach the
 * underlying {@code MultiNoiseBiomeSource}. Lithostitched can wrap a
 * dimension's biome source with its own source that applies biome
 * injectors (add/replace/force operations). The wrapper delegates
 * biome lookups but is NOT a {@code MultiNoiseBiomeSource}, so the
 * dump's {@code instanceof} check fails without this unwrap.
 *
 * <p>Any mod's datapack can add one, so a base world's source may be wrapped
 * on any given mod list. Every path that composes a dimension from a base
 * world's source has to unwrap first or it silently drops the dimension's
 * whole biome list.
 *
 * <p>Fails open: if Lithostitched is absent or the API changed, returns
 * the source unchanged.
 */
public final class LithostitchedCompat {

    private static final String MOD_ID = "lithostitched";

    private static boolean resolved;
    private static boolean available;
    private static Class<?> injectorClass;
    private static Method rootDelegate;

    private LithostitchedCompat() {
    }

    /**
     * If the source is a Lithostitched {@code InjectorBiomeSource},
     * unwrap to the root delegate (which is the original
     * {@code MultiNoiseBiomeSource}). Otherwise return the source
     * unchanged.
     */
    public static BiomeSource unwrap(BiomeSource source) {
        if (source instanceof MultiNoiseBiomeSource) {
            return source;
        }
        if (!ensureResolved()) {
            return source;
        }
        if (!injectorClass.isInstance(source)) {
            return source;
        }
        try {
            Object root = rootDelegate.invoke(source);
            if (root instanceof BiomeSource bs) {
                MultiverseServer.LOGGER.info(
                        "Lithostitched: unwrapped InjectorBiomeSource to {}",
                        bs.getClass().getSimpleName());
                return bs;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            MultiverseServer.LOGGER.warn(
                    "Lithostitched: unwrap failed ({})", e.toString());
        }
        return source;
    }

    public static boolean isAvailable() {
        return ensureResolved();
    }

    private static boolean ensureResolved() {
        if (resolved) {
            return available;
        }
        resolved = true;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return false;
        }
        try {
            injectorClass = Class.forName(
                    "dev.worldgen.lithostitched.impl.worldgen"
                    + ".biomeinjector.internal.InjectorBiomeSource");
            rootDelegate = injectorClass.getMethod("rootDelegate");
            available = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MultiverseServer.LOGGER.debug(
                    "Lithostitched: InjectorBiomeSource resolution failed ({})"
                    + " — unwrapping not available", e.toString());
            available = false;
        }
        return available;
    }

    static void reset() {
        resolved = false;
        available = false;
        injectorClass = null;
        rootDelegate = null;
    }
}
