package com.customdimensions.compat;

import com.customdimensions.MultiverseServer;
import com.mojang.datafixers.util.Pair;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Extracts exact per-biome climate cells from TerraBlender's registered
 * regions, via reflection. TB is optional — consumers assemble their own
 * mod list, so a missing TB degrades to "no TB entries", never a class
 * load failure.
 *
 * <p>Each TB {@code Region} provides its biomes via
 * {@code addBiomes(registry, consumer)}, yielding
 * {@code (NoiseHypercube, RegistryEntry<Biome>)} pairs — the same
 * representation as vanilla's static multinoise entries, and the data
 * the seed roller needs for exact biome placement.
 *
 * <p>TB's {@code RegionType} enum has exactly two values: {@code OVERWORLD}
 * and {@code NETHER}. There is no {@code END} region type — end biomes
 * are registered via {@code EndBiomeRegistry} with weighted biome lists
 * per zone (highlands/midlands/edge/island), not {@code NoiseHypercube}
 * cells. The vanilla end uses {@code TheEndBiomeSource}, which is not a
 * {@code MultiNoiseBiomeSource} and is excluded from the dump path
 * entirely. A Nullscape-modded end that uses MNBS gets its entries via
 * the datapack JSON (Phase 1 static).
 *
 * <p>Fails open: every failure path returns an empty list, and a
 * throwing call disables itself permanently after one WARN.
 */
public final class TerraBlenderCompat {

    private static final String MOD_ID = "terrablender";

    private static boolean resolved;
    private static boolean available;
    private static Method regionsGet;
    private static Method addBiomes;
    private static Object overworldType;
    private static Object netherType;

    private TerraBlenderCompat() {
    }

    /**
     * Exact {@code (NoiseHypercube, biome)} pairs from every TB-registered
     * overworld region. Empty when TB is absent or its API has changed.
     */
    public static List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>>
            overworldEntries(Registry<Biome> biomeRegistry) {
        return regionEntries(biomeRegistry, true);
    }

    /**
     * Exact pairs from every TB-registered nether region.
     */
    public static List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>>
            netherEntries(Registry<Biome> biomeRegistry) {
        return regionEntries(biomeRegistry, false);
    }

    public static boolean isAvailable() {
        return ensureResolved();
    }

    @SuppressWarnings("unchecked")
    private static List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>>
            regionEntries(Registry<Biome> biomeRegistry, boolean overworld) {
        if (!ensureResolved()) {
            return Collections.emptyList();
        }
        Object type = overworld ? overworldType : netherType;
        try {
            List<?> regions = (List<?>) regionsGet.invoke(null, type);
            if (regions == null || regions.isEmpty()) {
                return Collections.emptyList();
            }
            List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> result =
                    new ArrayList<>();
            for (Object region : regions) {
                addBiomes.invoke(region, biomeRegistry,
                        (Consumer<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>>)
                                result::add);
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable("region entry extraction failed (" + e + ")");
            return Collections.emptyList();
        }
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
            Class<?> regionsClass = Class.forName("terrablender.api.Regions");
            Class<?> regionTypeClass = Class.forName("terrablender.api.RegionType");
            Class<?> regionClass = Class.forName("terrablender.api.Region");

            regionsGet = regionsClass.getMethod("get", regionTypeClass);
            addBiomes = regionClass.getMethod("addBiomes", Registry.class, Consumer.class);
            overworldType = regionTypeClass.getField("OVERWORLD").get(null);
            netherType = regionTypeClass.getField("NETHER").get(null);

            available = true;
            MultiverseServer.LOGGER.info(
                    "TerraBlender: exact parameter extraction available");
        } catch (ReflectiveOperationException | RuntimeException e) {
            MultiverseServer.LOGGER.warn(
                    "TerraBlender: {} is installed but API resolution failed ({}) "
                    + "— biome parameters for TB biomes will not be available",
                    MOD_ID, e.toString());
            available = false;
        }
        return available;
    }

    private static void disable(String reason) {
        available = false;
        MultiverseServer.LOGGER.warn(
                "TerraBlender: exact extraction disabled — {}", reason);
    }

    /** Test hook: forget resolved state so the next call re-resolves. */
    static void reset() {
        resolved = false;
        available = false;
        regionsGet = null;
        addBiomes = null;
        overworldType = null;
        netherType = null;
    }
}
