package com.customdimensions.server.level;

import com.customdimensions.compiler.datapack.RuntimeWorldgen;
import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.mixin.ChunkGeneratorAccessor;
import com.customdimensions.server.mixin.NoiseBasedChunkGeneratorAccessor;
import com.customdimensions.server.zone.LevelZones;
import com.google.common.base.Suppliers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Applies the pack's {@code data/customdimensions/worldgen_runtime/} files to
 * level generators before they generate: a lava level in place of the
 * generator's -54, no sea or lava where a dry zone may reach, and per-biome feature lists with placed features removed
 * and added ores appended to the underground-ores step. Base worlds are
 * patched at server start, before their levels exist; a lazy level as the
 * {@link LevelManager} decodes its stem. Biomes stay shared; only this
 * generator's view of them changes.
 */
public final class WorldgenRuntime {

    /** One level's runtime worldgen. */
    public record Spec(String level, Integer lavaLevel, List<String> addUndergroundOres, Set<String> removeFeatures) {}

    private static volatile Map<String, Spec> specs = Map.of();

    private WorldgenRuntime() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(WorldgenRuntime::start);
    }

    /** The spec for a level id, if the pack has one. */
    public static Optional<Spec> spec(String levelId) {
        return Optional.ofNullable(specs.get(levelId));
    }

    private static void start(MinecraftServer server) {
        specs = load(server);
        Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> stem : stems.entrySet()) {
            apply(server, stem.getKey().identifier().toString(), stem.getValue().generator());
        }
    }

    /** Applies the level's spec, if any, and its dry zones to a generator about to serve it. */
    public static void apply(MinecraftServer server, ResourceKey<Level> level, ChunkGenerator generator) {
        apply(server, level.identifier().toString(), generator);
    }

    private static void apply(MinecraftServer server, String levelId, ChunkGenerator generator) {
        Spec spec = specs.get(levelId);
        if (spec != null) {
            apply(server, spec, generator);
        }
        List<ZoneMask> dry = LevelZones.dryMasks(levelId);
        if (!dry.isEmpty() && generator instanceof NoiseBasedChunkGenerator noise) {
            NoiseBasedChunkGeneratorAccessor accessor = (NoiseBasedChunkGeneratorAccessor) (Object) noise;
            Supplier<Aquifer.FluidPicker> beneath = accessor.customdimensions$globalFluidPicker();
            accessor.customdimensions$setGlobalFluidPicker(Suppliers.memoize(() -> dry(beneath.get(), dry)));
            CustomDimensions.LOG.info("worldgen runtime applied to {}: no sea or lava where {} dry zone(s) may reach", levelId, dry.size());
        }
    }

    /**
     * {@code beneath} with no sea and no lava where a dry zone may reach: the
     * generator places lava below its lava level before any aquifer is
     * consulted, and an aquifer cell takes the sea from the cells round it, so
     * a dry zone's aquifer data alone leaves both in the zone's blend.
     */
    static Aquifer.FluidPicker dry(Aquifer.FluidPicker beneath, List<ZoneMask> dry) {
        BlockState air = Blocks.AIR.defaultBlockState();
        return (x, y, z) -> {
            Aquifer.FluidStatus status = beneath.computeFluid(x, y, z);
            for (ZoneMask mask : dry) {
                if (mask.mayCover(x, z)) return new Aquifer.FluidStatus(status.fluidLevel(), air);
            }
            return status;
        };
    }

    static Map<String, Spec> load(MinecraftServer server) {
        Map<String, Spec> out = new HashMap<>();
        Map<Identifier, Resource> files = server.getResourceManager().listResources(RuntimeWorldgen.FOLDER,
                id -> id.getNamespace().equals(com.customdimensions.compiler.datapack.DatapackCompiler.RUNTIME_NAMESPACE)
                        && id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
            try (Reader reader = file.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                Spec spec = new Spec(json.get(RuntimeWorldgen.LEVEL).getAsString(),
                        json.has(RuntimeWorldgen.LAVA_LEVEL) ? json.get(RuntimeWorldgen.LAVA_LEVEL).getAsInt() : null,
                        strings(json, RuntimeWorldgen.ADD_UNDERGROUND_ORES),
                        new LinkedHashSet<>(strings(json, RuntimeWorldgen.REMOVE_FEATURES)));
                out.put(spec.level(), spec);
            } catch (Exception e) {
                CustomDimensions.LOG.warn("worldgen runtime file {} is unreadable: {}", file.getKey(), e.toString());
            }
        }
        return Map.copyOf(out);
    }

    private static void apply(MinecraftServer server, Spec spec, ChunkGenerator generator) {
        if (spec.lavaLevel() != null && generator instanceof NoiseBasedChunkGenerator noise) {
            NoiseGeneratorSettings settings = noise.generatorSettings().value();
            ((NoiseBasedChunkGeneratorAccessor) (Object) noise).customdimensions$setGlobalFluidPicker(
                    Suppliers.memoize(() -> fluidPicker(settings, spec.lavaLevel())));
        }
        if (!spec.addUndergroundOres().isEmpty() || !spec.removeFeatures().isEmpty()) {
            Registry<PlacedFeature> placed = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
            List<Holder<PlacedFeature>> added = new ArrayList<>();
            for (String id : spec.addUndergroundOres()) {
                Optional<Holder.Reference<PlacedFeature>> holder = placed.get(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(id)));
                if (holder.isPresent()) {
                    added.add(holder.get());
                } else {
                    CustomDimensions.LOG.warn("{}: added ore {} is not a placed feature in the registry", spec.level(), id);
                }
            }
            ChunkGeneratorAccessor accessor = (ChunkGeneratorAccessor) (Object) generator;
            Function<Holder<Biome>, BiomeGenerationSettings> original = accessor.customdimensions$generationSettingsGetter();
            Map<Holder<Biome>, BiomeGenerationSettings> cache = new ConcurrentHashMap<>();
            Function<Holder<Biome>, BiomeGenerationSettings> getter =
                    biome -> cache.computeIfAbsent(biome, b -> filtered(original.apply(b), spec.removeFeatures(), added));
            accessor.customdimensions$setGenerationSettingsGetter(getter);
            accessor.customdimensions$setFeaturesPerStep(Suppliers.memoize(() -> FeatureSorter.buildFeaturesPerStep(
                    List.copyOf(generator.getBiomeSource().possibleBiomes()), b -> getter.apply(b).features(), true)));
        }
        CustomDimensions.LOG.info("worldgen runtime applied to {}: lava level {}, {} ore(s) added, {} feature(s) removed",
                spec.level(), spec.lavaLevel(), spec.addUndergroundOres().size(), spec.removeFeatures().size());
    }

    /** Vanilla's global fluid rule with {@code lavaLevel} in place of -54. */
    static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings, int lavaLevel) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        int below = Math.min(lavaLevel, seaLevel);
        return (x, y, z) -> y < below ? lava : sea;
    }

    private static BiomeGenerationSettings filtered(BiomeGenerationSettings settings, Set<String> remove,
                                                    List<Holder<PlacedFeature>> add) {
        BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
        settings.getCarvers().forEach(builder::addCarver);
        List<HolderSet<PlacedFeature>> steps = settings.features();
        int ores = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
        for (int step = 0; step < Math.max(steps.size(), add.isEmpty() ? 0 : ores + 1); step++) {
            if (step < steps.size()) {
                for (Holder<PlacedFeature> feature : steps.get(step)) {
                    String id = feature.unwrapKey().map(k -> k.identifier().toString()).orElse("");
                    if (!remove.contains(id)) {
                        builder.addFeature(step, feature);
                    }
                }
            }
            if (step == ores) {
                add.forEach(f -> builder.addFeature(ores, f));
            }
        }
        return builder.build();
    }

    private static List<String> strings(JsonObject json, String key) {
        List<String> out = new ArrayList<>();
        if (json.has(key)) {
            for (JsonElement e : json.getAsJsonArray(key)) {
                out.add(e.getAsString());
            }
        }
        return out;
    }
}
