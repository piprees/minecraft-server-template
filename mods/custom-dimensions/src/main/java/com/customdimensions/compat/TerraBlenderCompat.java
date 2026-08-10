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
 * the datapack JSON.
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
    private static Method regionGetName;
    private static Method regionGetWeight;
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

    /**
     * One TB region as dumped for the region-selection mirror: the name and
     * weight identify it, {@code index} is its position in TB's registered
     * order (the uniqueness assignment follows registration order), and the
     * entries are its exact climate cells.
     */
    public record RegionDump(
            String name, int weight, int index,
            List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> entries) {
    }

    /**
     * Per-region table for the selection mirror. Region membership decides
     * which parameter list answers once TB's uniqueness layer picks the
     * region, so the flat union (overworldEntries/netherEntries) cannot
     * drive selection — this can. Empty when TB is absent or the Region
     * accessors are unavailable.
     */
    @SuppressWarnings("unchecked")
    public static List<RegionDump> regionTable(
            Registry<Biome> biomeRegistry, boolean overworld) {
        if (!ensureResolved() || regionGetName == null || regionGetWeight == null) {
            return Collections.emptyList();
        }
        Object type = overworld ? overworldType : netherType;
        try {
            List<?> regions = (List<?>) regionsGet.invoke(null, type);
            if (regions == null || regions.isEmpty()) {
                return Collections.emptyList();
            }
            List<RegionDump> result = new ArrayList<>();
            int index = 0;
            for (Object region : regions) {
                String name = String.valueOf(regionGetName.invoke(region));
                int weight = (Integer) regionGetWeight.invoke(region);
                List<Pair<MultiNoiseUtil.NoiseHypercube,
                        net.minecraft.registry.RegistryKey<Biome>>> raw = new ArrayList<>();
                addBiomes.invoke(region, biomeRegistry,
                        (Consumer<Pair<MultiNoiseUtil.NoiseHypercube,
                                net.minecraft.registry.RegistryKey<Biome>>>) raw::add);
                List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> cells =
                        new ArrayList<>();
                for (var pair : raw) {
                    biomeRegistry.getEntry(pair.getSecond()).ifPresent(
                            entry -> cells.add(Pair.of(pair.getFirst(), entry)));
                }
                result.add(new RegionDump(name, weight, index, cells));
                index++;
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable("region table extraction failed (" + e + ")");
            return Collections.emptyList();
        }
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
                // TB's consumer yields Pair<NoiseHypercube, RegistryKey<Biome>>
                // (verified by javap against the installed jar) — resolve each
                // key through the registry; a key the registry cannot resolve
                // contributes nothing (the dump's unresolved check covers it).
                List<Pair<MultiNoiseUtil.NoiseHypercube,
                        net.minecraft.registry.RegistryKey<Biome>>> raw = new ArrayList<>();
                addBiomes.invoke(region, biomeRegistry,
                        (Consumer<Pair<MultiNoiseUtil.NoiseHypercube,
                                net.minecraft.registry.RegistryKey<Biome>>>) raw::add);
                for (var pair : raw) {
                    biomeRegistry.getEntry(pair.getSecond()).ifPresent(
                            entry -> result.add(Pair.of(pair.getFirst(), entry)));
                }
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
            // Region-table accessors are optional: their absence degrades to
            // "no region table" while the flat entry paths keep working.
            try {
                regionGetName = regionClass.getMethod("getName");
                regionGetWeight = regionClass.getMethod("getWeight");
            } catch (ReflectiveOperationException e) {
                MultiverseServer.LOGGER.warn(
                        "TerraBlender: Region name/weight accessors unavailable ({}) "
                        + "— region table dump disabled", e.toString());
            }

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
        regionGetName = null;
        regionGetWeight = null;
        overworldType = null;
        netherType = null;
    }
    /**
     * Per-position probe of a live TB-extended parameter list: the
     * uniqueness index and the biome the list answers for the given
     * NoiseValuePoint. Returns "uniqueness,biomeId", or null when TB is
     * absent or the list is not TB-extended (vanilla lists never are).
     * The registry-level region table cannot say which list a custom
     * dimension's wrapper actually consults — this asks the wrapper.
     */
    public static String probePositional(Object parameterList,
                                         MultiNoiseUtil.NoiseValuePoint point,
                                         int qx, int qy, int qz) {
        try {
            Class<?> iface = Class.forName(
                    "terrablender.worldgen.IExtendedParameterList");
            if (!iface.isInstance(parameterList)) {
                return null;
            }
            Method getUniqueness = iface.getMethod(
                    "getUniqueness", int.class, int.class, int.class);
            Method findValuePositional = iface.getMethod(
                    "findValuePositional",
                    MultiNoiseUtil.NoiseValuePoint.class,
                    int.class, int.class, int.class);
            int uniqueness = (int) getUniqueness.invoke(
                    parameterList, qx, qy, qz);
            Object result = findValuePositional.invoke(
                    parameterList, point, qx, qy, qz);
            String biomeId = "unknown";
            if (result instanceof RegistryEntry<?> entry) {
                biomeId = entry.getKey()
                        .map(k -> k.getValue().toString()).orElse("unknown");
            }
            return uniqueness + "," + biomeId;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

}