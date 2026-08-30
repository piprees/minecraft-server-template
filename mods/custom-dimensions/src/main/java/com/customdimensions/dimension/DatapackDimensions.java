package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mojang.serialization.JsonOps;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.resource.Resource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.dimension.DimensionOptions;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The dimension entry a DATAPACK declares for a key, past whatever replaced it
 * in the live registry.
 *
 * <p>A world preset applied at creation wins the DIMENSION registry entry, so a
 * mod that replaces the nether's or the End's generator leaves that family with
 * no {@link MultiNoiseBiomeSource} to compose from and every managed dimension
 * of that family falls back to the overworld's table — which is a ~300-entry
 * table against a router crossing a sliver of it. The datapack resource is a
 * separate structure from the registry and is untouched by the replacement, so
 * the family's own author-written table is still readable there.
 *
 * <p>This reads data. It does not touch {@code minecraft:the_nether} or
 * {@code minecraft:the_end} themselves — the replacing mod keeps its generator
 * and its own placement of biomes in those worlds.
 *
 * <p>Parsed through Gson in lenient mode, which is how the game reads worldgen
 * JSON itself: line and block comments survive, a trailing comma does not.
 * Matching the game exactly is the point — an entry the game refused never
 * reached the registry, so composing from it would be worse than not reading
 * it ([T35] covers the strict-parser trap on the Python side).
 *
 * <p>Fails open: absent, unparseable or undecodable all return null after one
 * WARN, leaving the caller's existing fallback in place.
 */
public final class DatapackDimensions {

    private static final Map<RegistryKey<DimensionOptions>, MultiNoiseBiomeSource> CACHE = new HashMap<>();
    private static final Map<RegistryKey<DimensionOptions>, Boolean> TRIED = new HashMap<>();

    private DatapackDimensions() {
    }

    /**
     * The multi-noise source of the datapack's own entry for {@code key}, or
     * null when there is none, it cannot be read, or it is not multi-noise.
     * Cached per key for the life of the server.
     */
    public static MultiNoiseBiomeSource multiNoiseFor(MinecraftServer server,
                                                      RegistryKey<DimensionOptions> key) {
        if (server == null || key == null) {
            return null;
        }
        if (TRIED.containsKey(key)) {
            return CACHE.get(key);
        }
        TRIED.put(key, Boolean.TRUE);
        MultiNoiseBiomeSource source = read(server, key);
        CACHE.put(key, source);
        return source;
    }

    private static MultiNoiseBiomeSource read(MinecraftServer server,
                                              RegistryKey<DimensionOptions> key) {
        Identifier id = key.getValue();
        Identifier resourceId = Identifier.of(id.getNamespace(),
                resourcePathFor(RegistryKeys.getPath(RegistryKeys.DIMENSION), id.getPath()));
        Optional<Resource> resource = server.getResourceManager().getResource(resourceId);
        if (resource.isEmpty()) {
            MultiverseServer.LOGGER.warn(
                    "Datapack dimension {} not found as {} — the {} family has no author-written "
                    + "table to compose from", id, resourceId, id.getPath());
            return null;
        }
        try (BufferedReader reader = resource.get().getReader()) {
            StringBuilder text = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                text.append(buf, 0, n);
            }
            JsonElement json = parseLenient(text.toString());
            DynamicRegistryManager.Immutable regManager =
                    server.getCombinedDynamicRegistries().getCombinedRegistryManager();
            DimensionOptions options = DimensionOptions.CODEC
                    .parse(RegistryOps.of(JsonOps.INSTANCE, regManager), json)
                    .resultOrPartial(why -> MultiverseServer.LOGGER.warn(
                            "Datapack dimension {} did not decode ({})", id, why))
                    .orElse(null);
            if (options == null) {
                return null;
            }
            MultiNoiseBiomeSource source = DimensionManager.multiNoiseOf(options.chunkGenerator());
            if (source == null) {
                MultiverseServer.LOGGER.warn(
                        "Datapack dimension {} decoded but carries no multi-noise source ({})",
                        id, options.chunkGenerator().getBiomeSource().getClass().getName());
                return null;
            }
            // BIOMES, not entries: a datapack may name one biome twice, as
            // Nullscape does for crystal_peaks, and getBiomes is a set.
            MultiverseServer.LOGGER.info(
                    "Datapack dimension {}: composing from its own {} biome(s); the live entry "
                    + "was replaced and carries no multi-noise source",
                    id, source.getBiomes().size());
            return source;
        } catch (Exception e) {
            MultiverseServer.LOGGER.warn(
                    "Datapack dimension {} could not be read ({})", id, e.toString());
            return null;
        }
    }

    /** The datapack resource path for a dimension: {@code dimension/<name>.json}. */
    static String resourcePathFor(String registryDirectory, String dimensionPath) {
        return registryDirectory + "/" + dimensionPath + ".json";
    }

    /**
     * Mod worldgen JSON, read exactly as the game reads it.
     * {@code JsonParser.parseReader} is lenient by default, so line and block
     * comments survive and a trailing comma does not. The behaviour is Gson's,
     * not ours — {@code DatapackDimensionsTest} asserts it so a Gson upgrade
     * that changed it would fail here rather than in a boot.
     */
    static JsonElement parseLenient(String text) {
        return JsonParser.parseReader(new JsonReader(new StringReader(text)));
    }

    /** Test hook: forget what was read so the next call re-reads. */
    static void reset() {
        CACHE.clear();
        TRIED.clear();
    }
}
