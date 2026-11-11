package com.customdimensions.server.level;

import com.customdimensions.compiler.datapack.BiomeLibrary;
import com.customdimensions.compiler.datapack.RuntimeWorldgen;
import com.customdimensions.compiler.zone.ZoneDensity;
import com.customdimensions.core.zone.ZoneSpec;
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
import net.minecraft.core.RegistryAccess;
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
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.synth.Noise;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * generator's -54, a fluid in a terrain's basins ({@link BasinFluidPicker}),
 * no sea or lava where a dry zone's compiled mask reaches
 * ({@link DryFluidPicker}), and per-biome feature lists with placed features removed
 * and added ores appended to the underground-ores step. Base worlds are
 * patched at server start, before their levels exist; a lazy level as the
 * {@link LevelManager} decodes its stem. Biomes stay shared; only this
 * generator's view of them changes.
 */
public final class WorldgenRuntime {

    /** One level's runtime worldgen. */
    public record Spec(String level, Integer lavaLevel, Basins basins, List<String> addUndergroundOres, Set<String> removeFeatures,
                       Set<String> removeCarvers, List<String> addCarvers) {}

    /** The fluid of a terrain's basins: its level, its block and the terrain's basin and floor functions by id. */
    public record Basins(int level, String fluid, String basin, String floor) {}

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
        long world = LevelSeeds.worldSeed(server);
        Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> stem : stems.entrySet()) {
            Long own = LevelSeeds.baseSeedOrNull(ResourceKey.create(Registries.DIMENSION, stem.getKey().identifier()));
            apply(server, stem.getKey().identifier().toString(), stem.getValue().generator(), own != null ? own : world);
        }
    }

    /**
     * Applies the level's spec, if any, and its dry zones to a generator about to serve it under {@code seed}:
     * the level's seed, or the one a sampler builds its own copy of the generator under.
     */
    public static void apply(MinecraftServer server, ResourceKey<Level> level, ChunkGenerator generator, long seed) {
        apply(server, level.identifier().toString(), generator, seed);
    }

    private static void apply(MinecraftServer server, String levelId, ChunkGenerator generator, long seed) {
        Spec spec = specs.get(levelId);
        if (spec != null) {
            apply(server, spec, generator, seed);
        }
        List<ZoneSpec> dry = LevelZones.dryZones(levelId);
        if (!dry.isEmpty() && generator instanceof NoiseBasedChunkGenerator noise) {
            NoiseBasedChunkGeneratorAccessor accessor = (NoiseBasedChunkGeneratorAccessor) (Object) noise;
            Supplier<Aquifer.FluidPicker> beneath = accessor.customdimensions$globalFluidPicker();
            RegistryAccess access = server.registryAccess();
            String slug = LevelZones.slug(levelId);
            NoiseGeneratorSettings settings = noise.generatorSettings().value();
            accessor.customdimensions$setGlobalFluidPicker(Suppliers.memoize(
                    () -> new DryFluidPicker(beneath.get(), dryZones(access, slug, dry, settings, seed))));
            CustomDimensions.LOG.info("worldgen runtime applied to {}: no sea or lava where {} dry zone(s) reach", levelId, dry.size());
        }
    }

    /** Each dry zone's still edge and, under {@code seed}, the jitter noise its compiled mask reads. */
    static List<DryFluidPicker.Zone> dryZones(RegistryAccess access, String slug, List<ZoneSpec> dry,
                                             NoiseGeneratorSettings settings, long seed) {
        RandomState random = RandomState.create(access.lookupOrThrow(Registries.NOISE), seed, settings);
        Map<Integer, DensityFunction> compiled = LevelZones.compiledMasks(access, slug);
        List<DryFluidPicker.Zone> out = new ArrayList<>();
        for (ZoneSpec zone : dry) {
            if (!compiled.containsKey(zone.index())) {
                throw new IllegalStateException(slug + ": zones[" + zone.index() + "] is dry but has no compiled mask");
            }
            Noise jitter = null;
            if (zone.mask().jitter() > 0) {
                jitter = random.getOrCreateNoise(ResourceKey.create(Registries.NOISE, Identifier.parse(
                        compiledNamespace(access, slug, zone.index()) + ":" + ZoneDensity.JITTER_NOISE)));
            }
            out.add(new DryFluidPicker.Zone(zone.mask(), jitter, zone.mask().jitter() / (2 * ZoneDensity.JITTER_SIGMA)));
        }
        return out;
    }

    private static String compiledNamespace(RegistryAccess access, String slug, int index) {
        String path = "zones/" + slug + "/" + index + "/mask";
        return access.lookupOrThrow(Registries.DENSITY_FUNCTION).listElements()
                .filter(h -> h.key().identifier().getPath().equals(path)).findFirst()
                .map(h -> h.key().identifier().getNamespace()).orElseThrow();
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
                        json.has(RuntimeWorldgen.BASINS) ? basins(json.getAsJsonObject(RuntimeWorldgen.BASINS)) : null,
                        strings(json, RuntimeWorldgen.ADD_UNDERGROUND_ORES),
                        new LinkedHashSet<>(strings(json, RuntimeWorldgen.REMOVE_FEATURES)),
                        new LinkedHashSet<>(strings(json, RuntimeWorldgen.REMOVE_CARVERS)), strings(json, RuntimeWorldgen.ADD_CARVERS));
                out.put(spec.level(), spec);
            } catch (Exception e) {
                CustomDimensions.LOG.warn("worldgen runtime file {} is unreadable: {}", file.getKey(), e.toString());
            }
        }
        return Map.copyOf(out);
    }

    private static Basins basins(JsonObject o) {
        return new Basins(o.get("level").getAsInt(), o.get("fluid").getAsString(), o.get("basin").getAsString(), o.get("floor").getAsString());
    }

    private static void apply(MinecraftServer server, Spec spec, ChunkGenerator generator, long seed) {
        if (spec.lavaLevel() != null && generator instanceof NoiseBasedChunkGenerator noise) {
            NoiseGeneratorSettings settings = noise.generatorSettings().value();
            ((NoiseBasedChunkGeneratorAccessor) (Object) noise).customdimensions$setGlobalFluidPicker(
                    Suppliers.memoize(() -> fluidPicker(settings, spec.lavaLevel())));
        }
        if (spec.basins() != null && generator instanceof NoiseBasedChunkGenerator noise) {
            NoiseBasedChunkGeneratorAccessor accessor = (NoiseBasedChunkGeneratorAccessor) (Object) noise;
            Supplier<Aquifer.FluidPicker> beneath = accessor.customdimensions$globalFluidPicker();
            RegistryAccess access = server.registryAccess();
            NoiseGeneratorSettings settings = noise.generatorSettings().value();
            Basins b = spec.basins();
            accessor.customdimensions$setGlobalFluidPicker(Suppliers.memoize(() -> new BasinFluidPicker(beneath.get(),
                    RandomState.create(access.lookupOrThrow(Registries.NOISE), seed, settings),
                    densityFunction(access, b.basin()), densityFunction(access, b.floor()), b.level(),
                    access.lookupOrThrow(Registries.BLOCK).getValue(Identifier.parse(b.fluid())).defaultBlockState())));
            CustomDimensions.LOG.info("worldgen runtime applied to {}: {} fills its basins up to y {}", spec.level(), b.fluid(), b.level());
        }
        if (!spec.addUndergroundOres().isEmpty() || !spec.removeFeatures().isEmpty() || !spec.removeCarvers().isEmpty()
                || !spec.addCarvers().isEmpty()) {
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
            Set<String> library = libraryBiomes();
            Registry<WorldCarver> carvers = server.registryAccess().lookupOrThrow(Registries.CARVER);
            List<Holder<WorldCarver>> addCarvers = new ArrayList<>();
            for (String id : spec.addCarvers()) {
                carvers.get(ResourceKey.create(Registries.CARVER, Identifier.parse(id))).ifPresentOrElse(addCarvers::add,
                        () -> CustomDimensions.LOG.warn("{}: added carver {} is not in the registry", spec.level(), id));
            }
            Function<Holder<Biome>, BiomeGenerationSettings> getter = biome -> cache.computeIfAbsent(biome, b -> filtered(original.apply(b),
                    library.contains(id(b)) ? Set.of() : spec.removeFeatures(), library.contains(id(b)) ? List.of() : added,
                    spec.removeCarvers(), addCarvers));
            accessor.customdimensions$setGenerationSettingsGetter(getter);
            accessor.customdimensions$setFeaturesPerStep(Suppliers.memoize(() -> FeatureSorter.buildFeaturesPerStep(
                    List.copyOf(generator.getBiomeSource().possibleBiomes()), b -> getter.apply(b).features(), true)));
        }
        CustomDimensions.LOG.info("worldgen runtime applied to {}: lava level {}, {} ore(s) added, {} feature(s) removed, carvers off {} on {}",
                spec.level(), spec.lavaLevel(), spec.addUndergroundOres().size(), spec.removeFeatures().size(), spec.removeCarvers(),
                spec.addCarvers());
    }

    /**
     * The biome library's ids in the pack's namespace. A library biome keeps its
     * own feature list in every level: per-level ore edits change vanilla's biomes only.
     */
    public static Set<String> libraryBiomes() {
        String namespace = LevelIndex.definitionsDir().map(PackInstaller::namespace).orElse(LevelIndex.DEFAULT_NAMESPACE);
        return BiomeLibrary.shipped().ids(namespace);
    }

    private static String id(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.identifier().toString()).orElse("");
    }

    private static DensityFunction densityFunction(RegistryAccess access, String id) {
        return access.lookupOrThrow(Registries.DENSITY_FUNCTION).getValue(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(id)));
    }

    /** Vanilla's global fluid rule with {@code lavaLevel} in place of -54. */
    static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings, int lavaLevel) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus sea = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        int below = Math.min(lavaLevel, seaLevel);
        return (x, y, z) -> y < below ? lava : sea;
    }

    static BiomeGenerationSettings filtered(BiomeGenerationSettings settings, Set<String> remove, List<Holder<PlacedFeature>> add,
                                            Set<String> removeCarvers, List<Holder<WorldCarver>> addCarvers) {
        BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
        Set<String> kept = new HashSet<>();
        for (Holder<WorldCarver> carver : settings.getCarvers()) {
            String id = carver.unwrapKey().map(k -> k.identifier().toString()).orElse("");
            if (!removeCarvers.contains(id) && kept.add(id)) {
                builder.addCarver(carver);
            }
        }
        for (Holder<WorldCarver> carver : addCarvers) {
            if (kept.add(carver.unwrapKey().map(k -> k.identifier().toString()).orElse(""))) {
                builder.addCarver(carver);
            }
        }
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
