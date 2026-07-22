package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.mixin.MinecraftServerAccessor;
import com.customdimensions.mixin.MultiNoiseBiomeSourceAccessor;
import com.customdimensions.mixin.SimpleRegistryAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.MutableRegistry;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.block.Blocks;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.FixedBiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorLayer;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.UnmodifiableLevelProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class DimensionManager {
    private static final DimensionManager INSTANCE = new DimensionManager();
    private static final Set<RegistryKey<World>> PROTECTED_DIMENSIONS = Set.of(
            World.OVERWORLD, World.NETHER, World.END,
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("paradise_lost", "paradise_lost"))
    );
    private static final WorldGenerationProgressListener NO_OP_WORLD_GEN_PROGRESS = new WorldGenerationProgressListener() {
        @Override
        public void start(ChunkPos spawnPos) {
        }

        @Override
        public void setChunkStatus(ChunkPos pos, ChunkStatus status) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };

    private MinecraftServer server;
    private final Map<RegistryKey<World>, Long> lastPlayerPresence = new HashMap<>();
    // Dimensions whose ServerWorld must be (re)created at the next safe point.
    // Worlds are created lazily and dropped by the idle unloader, so a portal
    // can target a dimension with no live world. Creating it from inside
    // ServerWorld.tick would mutate the server's worlds map mid-iteration
    // (ConcurrentModificationException) — requests queue here and are drained
    // from END_SERVER_TICK instead.
    private final Set<String> pendingWorldLoads = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Dimensions whose live ServerWorld must be torn down at the next safe
    // point (deleted via /dimension delete). Same END_SERVER_TICK drain as
    // pendingWorldLoads, same reason. Without this, a deleted dimension's
    // world sat in the server's worlds map until restart — the idle unloader
    // skips any world with no config entry.
    private final Set<String> pendingWorldUnloads = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Definitions registered at runtime via /customdim create for dimensions
    // that have NO config entry. The seed/density/peaceful mixins consult
    // this after the config so command-created candidates get their real
    // seed (without it they silently clone the main world).
    private final Map<String, DimensionConfig> runtimeDefinitions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private boolean bootReconciled = false;

    public static DimensionManager getInstance() {
        return INSTANCE;
    }

    public void onServerStart(MinecraftServer server) {
        this.server = server;
        this.bootReconciled = false;
        this.cleanupDatapack();
    }

    private void cleanupDatapack() {
        try {
            Path datapackDir = this.server.getSavePath(WorldSavePath.DATAPACKS).resolve("customdimensions");
            if (Files.exists(datapackDir)) {
                Files.walk(datapackDir).sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
                MultiverseServer.LOGGER.info("Cleaned up old data pack: customdimensions");
            }
        } catch (Exception ignored) {
        }
    }

    private MutableRegistry<DimensionOptions> getDimensionRegistry() {
        DynamicRegistryManager.Immutable regManager = this.server.getCombinedDynamicRegistries().getCombinedRegistryManager();
        return (MutableRegistry<DimensionOptions>) regManager.get(RegistryKeys.DIMENSION);
    }

    public void registerDimensions() {
        if (this.server == null) {
            return;
        }
        MutableRegistry<DimensionOptions> dimRegistry = this.getDimensionRegistry();
        SimpleRegistryAccessor accessor = (SimpleRegistryAccessor) dimRegistry;
        boolean wasFrozen = accessor.isFrozen();
        if (wasFrozen) {
            accessor.setFrozen(false);
        }
        try {
            // Per-dim isolation: one broken config must not abort registration
            // of every dimension after it (2026-07-22: a seed-less config NPE'd
            // here and silently took an unrelated new dimension down with it).
            DimensionFingerprints.init(this.server);
            for (DimensionConfig def : MultiverseConfig.getInstance().getDimensions()) {
                RegistryKey<DimensionOptions> key = RegistryKey.of(RegistryKeys.DIMENSION, def.getDimensionIdentifier());
                if (dimRegistry.contains(key)) {
                    // The persisted generator (level.dat) wins for existing
                    // dimensions — warn on drift, never delete or regenerate.
                    DimensionFingerprints.checkExisting(def);
                    continue;
                }
                try {
                    DimensionOptions options = this.createDimensionOptions(def);
                    dimRegistry.add(key, options, RegistryEntryInfo.DEFAULT);
                    DimensionFingerprints.record(def);
                    MultiverseServer.LOGGER.info("Registered dimension: {}", key);
                } catch (Exception e) {
                    MultiverseServer.LOGGER.error("Failed to register dimension {}", key, e);
                }
            }
        } finally {
            if (wasFrozen) {
                accessor.setFrozen(true);
            }
        }
    }

    // Optional per-dimension ChunkGeneratorSettings override ("noiseSettings"
    // in multiverse_config.json, e.g. "adventure:wide"). Resolved against the
    // dynamic worldgen/noise_settings registry; the adventure:* presets ship
    // inside this mod's jar datapack. Additive: unset or unknown ids keep the
    // dimension's current generator settings — an unknown id must never turn
    // a boot into a crash loop, so it logs and falls back instead.
    private RegistryEntry<ChunkGeneratorSettings> resolveNoiseSettingsOverride(DimensionConfig def) {
        String id = def.getNoiseSettings();
        if (id == null || id.isEmpty()) {
            return null;
        }
        Identifier ident = Identifier.tryParse(id.toLowerCase());
        DynamicRegistryManager.Immutable regManager = this.server.getCombinedDynamicRegistries().getCombinedRegistryManager();
        Registry<ChunkGeneratorSettings> registry = regManager.get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
        Optional<? extends RegistryEntry<ChunkGeneratorSettings>> entry = ident == null
                ? Optional.empty()
                : registry.getEntry(RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, ident));
        if (entry.isEmpty()) {
            MultiverseServer.LOGGER.warn(
                    "noiseSettings '{}' for dimension {} not found in the noise_settings registry — using the type's default generator",
                    id, def.getName());
            return null;
        }
        MultiverseServer.LOGGER.info("Dimension {} uses noise settings {}", def.getName(), id);
        return entry.get();
    }

    // Custom dimension type from the "environment" block (v4 Phase 4): the
    // dimension's type entry is its registered {ns}:{slug}_type when an
    // environment is configured, else the base type it would clone anyway.
    private RegistryEntry<net.minecraft.world.dimension.DimensionType> typeEntryFor(
            DimensionConfig def, RegistryEntry<net.minecraft.world.dimension.DimensionType> base) {
        return DimensionTypeBuilder.typeEntryFor(this.server, def, base);
    }

    // Swap a noise generator's ChunkGeneratorSettings while keeping its biome
    // source. No-op for flat/void generators (noiseSettings has no meaning
    // there) and when no override is set.
    private static ChunkGenerator withSettings(ChunkGenerator generator, RegistryEntry<ChunkGeneratorSettings> settings) {
        if (settings != null && generator instanceof NoiseChunkGenerator noiseGen) {
            return new NoiseChunkGenerator(noiseGen.getBiomeSource(), settings);
        }
        return generator;
    }

    // Build a multi-noise source for an arbitrary biome list. Biomes native
    // to the base source keep their natural placement; every OTHER requested
    // biome (nether biomes in an overworld dim, cherry groves in the end —
    // cross-family mixing is the point) is dealt the remaining parameter
    // regions round-robin, so it genuinely appears in the layout instead of
    // being silently dropped. Before this, a list with no native matches
    // (the_crimson_nexus, the_souldrift) fell back to plains.
    private BiomeSource buildMixedSource(MultiNoiseBiomeSource base, Registry<Biome> biomeRegistry,
                                         String biomeList, String dimName) {
        Set<Identifier> allowedIds = Arrays.stream(biomeList.split(","))
                .map(String::trim).map(Identifier::tryParse).filter(id -> id != null)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        MultiNoiseUtil.Entries<RegistryEntry<Biome>> entries =
                ((MultiNoiseBiomeSourceAccessor) base).invokeGetBiomeEntries();
        List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> nativeEntries = new ArrayList<>();
        List<MultiNoiseUtil.NoiseHypercube> pool = new ArrayList<>();
        Set<Identifier> nativeIds = new HashSet<>();
        for (Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>> pair : entries.getEntries()) {
            Identifier id = pair.getSecond().getKey().map(RegistryKey::getValue).orElse(null);
            if (id != null && allowedIds.contains(id)) {
                nativeEntries.add(pair);
                nativeIds.add(id);
            } else {
                pool.add(pair.getFirst());
            }
        }

        List<RegistryEntry<Biome>> foreign = new ArrayList<>();
        for (Identifier id : allowedIds) {
            if (nativeIds.contains(id)) {
                continue;
            }
            Optional<RegistryEntry.Reference<Biome>> entry =
                    biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, id));
            if (entry.isPresent()) {
                foreign.add(entry.get());
            } else {
                MultiverseServer.LOGGER.warn("Dimension {}: biome {} not in the registry — skipped", dimName, id);
            }
        }

        List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> result = new ArrayList<>(nativeEntries);
        if (!foreign.isEmpty()) {
            for (int i = 0; i < pool.size(); i++) {
                result.add(Pair.of(pool.get(i), foreign.get(i % foreign.size())));
            }
        }
        if (result.isEmpty()) {
            MultiverseServer.LOGGER.warn("Dimension {}: no usable biomes in '{}' — keeping the base source", dimName, biomeList);
            return base;
        }
        MultiverseServer.LOGGER.info("Dimension {}: biome source built ({} native, {} mixed-in of {} requested)",
                dimName, nativeEntries.size(), foreign.size(), allowedIds.size());
        return MultiNoiseBiomeSource.create(new MultiNoiseUtil.Entries<>(result));
    }

    // Resolve the biome source for a dimension with a biome list: prefer the
    // dimension's own family source as the base (natural placements), fall
    // back to the overworld's. Null biome list -> null (caller keeps base).
    private BiomeSource resolveListedSource(DimensionConfig def, Registry<Biome> biomeRegistry,
                                            ChunkGenerator baseGenerator, ChunkGenerator overworldGenerator) {
        String biomeList = def.getBiome();
        if (biomeList == null || biomeList.isEmpty()) {
            return null;
        }
        if (baseGenerator != null && baseGenerator.getBiomeSource() instanceof MultiNoiseBiomeSource base) {
            return buildMixedSource(base, biomeRegistry, biomeList, def.getName());
        }
        if (overworldGenerator != null && overworldGenerator.getBiomeSource() instanceof MultiNoiseBiomeSource owBase) {
            return buildMixedSource(owBase, biomeRegistry, biomeList, def.getName());
        }
        return null;
    }

    private DimensionOptions createDimensionOptions(DimensionConfig def) {
        MutableRegistry<DimensionOptions> dimRegistry = this.getDimensionRegistry();
        DynamicRegistryManager.Immutable regManager = this.server.getCombinedDynamicRegistries().getCombinedRegistryManager();
        Registry<Biome> biomeRegistry = regManager.get(RegistryKeys.BIOME);
        String type = def.getType();
        // Registration runs at beforeCreateWorlds, when getOverworld() is still
        // null — fall back to the save's generator seed for seed-less configs.
        long worldSeed;
        if (def.getSeed() != null) {
            worldSeed = def.getSeed();
        } else {
            ServerWorld overworld = this.server.getOverworld();
            worldSeed = overworld != null ? overworld.getSeed()
                    : this.server.getSaveProperties().getGeneratorOptions().getSeed();
        }
        RegistryEntry<ChunkGeneratorSettings> settingsOverride = this.resolveNoiseSettingsOverride(def);
        if (settingsOverride != null && ("void".equals(type) || "superflat".equals(type))) {
            MultiverseServer.LOGGER.warn(
                    "noiseSettings on dimension {} is ignored: type '{}' uses a flat generator", def.getName(), type);
        }

        DimensionOptions overworldOpts = dimRegistry.get(DimensionOptions.OVERWORLD);
        if (overworldOpts == null) {
            throw new IllegalStateException("Cannot create dimension options: overworld not found");
        }

        return switch (type) {
            case "void" -> {
                // A void with a biome list keeps a REAL biome layout even
                // though no terrain generates — mob spawning, fog and
                // ambience still read the biome. This must be a NOISE
                // generator: a flat generator samples the multi-noise
                // source with zero climate noise, collapsing the layout to
                // one biome everywhere and ignoring the seed (verified
                // empirically 2026-07-17). adventure:void ships in the jar
                // datapack — overworld climate router, final_density -1.
                BiomeSource voidSource = this.resolveListedSource(def, biomeRegistry,
                        null, overworldOpts.chunkGenerator());
                Registry<ChunkGeneratorSettings> nsRegistry = regManager.get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
                Optional<? extends RegistryEntry<ChunkGeneratorSettings>> voidSettings =
                        nsRegistry.getEntry(RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS,
                                Identifier.of("adventure", "void")));
                if (voidSource != null && voidSettings.isPresent()) {
                    NoiseChunkGenerator voidGen = new NoiseChunkGenerator(voidSource, voidSettings.get());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(voidGen, worldSeed));
                }
                // Fallback (no biome list, or preset missing from the jar):
                // the old flat THE_VOID generator.
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: void fallback to flat generator (biome list: {}, adventure:void present: {})",
                        def.getName(), def.getBiome() != null, voidSettings.isPresent());
                RegistryEntry<Biome> voidBiome = biomeRegistry.getEntry(biomeRegistry.get(BiomeKeys.THE_VOID));
                FlatChunkGeneratorConfig config = new FlatChunkGeneratorConfig(Optional.empty(), voidBiome, List.of())
                    .with(List.of(), Optional.empty(), voidBiome);
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(new FlatChunkGenerator(config), worldSeed));
            }
            case "superflat" -> {
                RegistryEntry<Biome> plainsBiome = biomeRegistry.getEntry(biomeRegistry.get(BiomeKeys.PLAINS));
                List<FlatChunkGeneratorLayer> layers = List.of(
                        new FlatChunkGeneratorLayer(1, Blocks.BEDROCK),
                        new FlatChunkGeneratorLayer(2, Blocks.DIRT),
                        new FlatChunkGeneratorLayer(1, Blocks.GRASS_BLOCK)
                );
                FlatChunkGeneratorConfig config = new FlatChunkGeneratorConfig(Optional.empty(), plainsBiome, List.of())
                    .with(layers, Optional.empty(), plainsBiome);
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(new FlatChunkGenerator(config), worldSeed));
            }
            case "single_biome" -> {
                String biomeId = def.getBiome();
                if (biomeId == null) {
                    biomeId = "minecraft:plains";
                }
                Identifier biomeIdentifier = Identifier.tryParse(biomeId);
                Biome biome = biomeIdentifier != null ? biomeRegistry.get(biomeIdentifier) : null;
                if (biome == null) {
                    biome = biomeRegistry.get(BiomeKeys.PLAINS);
                }
                RegistryEntry<Biome> biomeEntry = biomeRegistry.getEntry(biome);
                FixedBiomeSource fixedSource = new FixedBiomeSource(biomeEntry);
                if (overworldOpts.chunkGenerator() instanceof NoiseChunkGenerator noiseGen) {
                    RegistryEntry<ChunkGeneratorSettings> settings = noiseGen.getSettings();
                    NoiseChunkGenerator newGen = new NoiseChunkGenerator(fixedSource, settings);
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(newGen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "multi_biome" -> {
                if (overworldOpts.chunkGenerator() instanceof NoiseChunkGenerator noiseGen) {
                    BiomeSource mixed = this.resolveListedSource(def, biomeRegistry,
                            overworldOpts.chunkGenerator(), overworldOpts.chunkGenerator());
                    if (mixed == null) {
                        mixed = noiseGen.getBiomeSource();
                    }
                    NoiseChunkGenerator newGen = new NoiseChunkGenerator(mixed, noiseGen.getSettings());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(newGen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "cave" -> {
                // Fully underground world: vanilla still ships the
                // minecraft:caves generator settings (bedrock roof at the top,
                // no sky access, sea/lava level 32 — verified live 2026-07-22
                // via a fixture using the noiseSettings override). Biome list
                // mixes as usual; an explicit noiseSettings still wins.
                BiomeSource caveSource = this.resolveListedSource(def, biomeRegistry,
                        overworldOpts.chunkGenerator(), overworldOpts.chunkGenerator());
                if (caveSource == null) {
                    caveSource = overworldOpts.chunkGenerator().getBiomeSource();
                }
                if (settingsOverride != null) {
                    NoiseChunkGenerator caveGen = new NoiseChunkGenerator(caveSource, settingsOverride);
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(caveGen, worldSeed));
                }
                Registry<ChunkGeneratorSettings> nsRegistry = regManager.get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
                Optional<? extends RegistryEntry<ChunkGeneratorSettings>> caveSettings =
                        nsRegistry.getEntry(RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS,
                                Identifier.of("minecraft", "caves")));
                if (caveSettings.isPresent()) {
                    NoiseChunkGenerator caveGen = new NoiseChunkGenerator(caveSource, caveSettings.get());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(caveGen, worldSeed));
                }
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: minecraft:caves noise settings not found — falling back to overworld generator", def.getName());
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "nether" -> {
                DimensionOptions source = dimRegistry.get(DimensionOptions.NETHER);
                if (source != null) {
                    ChunkGenerator gen = source.chunkGenerator();
                    // A biome list on a nether dim mixes ANY biome into the
                    // nether's layout (overworld greenery under the roof, end
                    // crystal fields — cross-family is deliberate).
                    BiomeSource mixed = this.resolveListedSource(def, biomeRegistry, gen, overworldOpts.chunkGenerator());
                    if (mixed != null && gen instanceof NoiseChunkGenerator noiseGen) {
                        gen = new NoiseChunkGenerator(mixed, noiseGen.getSettings());
                    }
                    yield new DimensionOptions(this.typeEntryFor(def, source.dimensionTypeEntry()), withSeed(withSettings(gen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "end" -> {
                DimensionOptions source = dimRegistry.get(DimensionOptions.END);
                if (source != null) {
                    ChunkGenerator gen = source.chunkGenerator();
                    BiomeSource mixed = this.resolveListedSource(def, biomeRegistry, gen, overworldOpts.chunkGenerator());
                    if (mixed != null && gen instanceof NoiseChunkGenerator noiseGen) {
                        gen = new NoiseChunkGenerator(mixed, noiseGen.getSettings());
                    }
                    yield new DimensionOptions(this.typeEntryFor(def, source.dimensionTypeEntry()), withSeed(withSettings(gen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "sky_islands" -> {
                // End terrain shape (floating islands); biome list mixes from
                // the full registry (overworld base for natural placements).
                DimensionOptions endOpts = dimRegistry.get(DimensionOptions.END);
                if (endOpts != null && endOpts.chunkGenerator() instanceof NoiseChunkGenerator endGen) {
                    BiomeSource biomeSource = this.resolveListedSource(def, biomeRegistry,
                            overworldOpts.chunkGenerator(), overworldOpts.chunkGenerator());
                    if (biomeSource == null) {
                        biomeSource = overworldOpts.chunkGenerator().getBiomeSource();
                    }
                    NoiseChunkGenerator skyGen = new NoiseChunkGenerator(biomeSource, endGen.getSettings());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(skyGen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "nether_islands" -> {
                // End terrain shape (floating islands) with the nether's
                // dimension type; biome list mixes from the full registry.
                DimensionOptions endOpts = dimRegistry.get(DimensionOptions.END);
                DimensionOptions netherOpts = dimRegistry.get(DimensionOptions.NETHER);
                if (endOpts != null && netherOpts != null && endOpts.chunkGenerator() instanceof NoiseChunkGenerator endGen) {
                    BiomeSource biomeSource = this.resolveListedSource(def, biomeRegistry,
                            netherOpts.chunkGenerator(), overworldOpts.chunkGenerator());
                    if (biomeSource == null) {
                        biomeSource = netherOpts.chunkGenerator().getBiomeSource();
                    }
                    NoiseChunkGenerator netherSkyGen = new NoiseChunkGenerator(biomeSource, endGen.getSettings());
                    yield new DimensionOptions(this.typeEntryFor(def, netherOpts.dimensionTypeEntry()), withSeed(withSettings(netherSkyGen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "amplified" -> {
                Registry<WorldPreset> presetRegistry = regManager.get(RegistryKeys.WORLD_PRESET);
                WorldPreset preset = presetRegistry.get(WorldPresets.AMPLIFIED);
                if (preset != null) {
                    Optional<DimensionOptions> presetOpts = preset.getOverworld();
                    if (presetOpts.isPresent()) {
                        yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(presetOpts.get().chunkGenerator(), settingsOverride), worldSeed));
                    }
                }
                MultiverseServer.LOGGER.warn("Amplified preset not found, falling back to overworld");
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "large_biomes" -> {
                Registry<WorldPreset> presetRegistry = regManager.get(RegistryKeys.WORLD_PRESET);
                WorldPreset preset = presetRegistry.get(WorldPresets.LARGE_BIOMES);
                if (preset != null) {
                    Optional<DimensionOptions> presetOpts = preset.getOverworld();
                    if (presetOpts.isPresent()) {
                        yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(presetOpts.get().chunkGenerator(), settingsOverride), worldSeed));
                    }
                }
                MultiverseServer.LOGGER.warn("Large biomes preset not found, falling back to overworld");
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            default -> {
                // A type containing ':' clones ANY registered dimension —
                // modded datapack dimensions included (paradise_lost:
                // paradise_lost is a vanilla noise generator with its own
                // noise settings, so per-dimension seeds and biome mixing
                // work exactly like nether/end clones).
                if (type != null && type.contains(":")) {
                    Identifier srcId = Identifier.tryParse(type);
                    DimensionOptions source = srcId == null ? null
                            : dimRegistry.get(RegistryKey.of(RegistryKeys.DIMENSION, srcId));
                    if (source != null) {
                        ChunkGenerator gen = source.chunkGenerator();
                        BiomeSource mixed = this.resolveListedSource(def, biomeRegistry, gen, overworldOpts.chunkGenerator());
                        if (mixed != null && gen instanceof NoiseChunkGenerator noiseGen) {
                            gen = new NoiseChunkGenerator(mixed, noiseGen.getSettings());
                        }
                        yield new DimensionOptions(this.typeEntryFor(def, source.dimensionTypeEntry()), withSeed(withSettings(gen, settingsOverride), worldSeed));
                    }
                    MultiverseServer.LOGGER.warn("Dimension {}: clone source '{}' not registered — falling back to overworld", def.getName(), type);
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
        };
    }

    private static ChunkGenerator withSeed(ChunkGenerator generator, long seed) {
        if (generator instanceof NoiseChunkGenerator noiseGenerator) {
            Object seededSource = invokeWithSeedReflectively(noiseGenerator.getBiomeSource(), seed);
            if (!(seededSource instanceof net.minecraft.world.biome.source.BiomeSource biomeSource)) {
                return generator;
            }
            return new NoiseChunkGenerator(biomeSource, noiseGenerator.getSettings());
        }

        // Flat and other deterministic generators either ignore seed or do not expose
        // seed-specific constructors in 1.21.1. Preserve the original generator instance.
        return generator;
    }

    static Object invokeWithSeedReflectively(Object seedable, long seed) {
        if (seedable == null) {
            return null;
        }
        try {
            return seedable.getClass().getMethod("withSeed", long.class).invoke(seedable, seed);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public ServerWorld getOrCreateDimension(String dimName) {
        if (this.server == null) {
            return null;
        }
        DimensionConfig def = MultiverseConfig.getInstance().getDimension(dimName);
        if (def == null) {
            // Command-created dimensions have no config entry — their options
            // are already in the registry (registerDimension), load directly.
            return getOrCreateDimensionDirect(dimName);
        }

        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, def.getDimensionIdentifier());
        MinecraftServerAccessor serverAccessor = (MinecraftServerAccessor) this.server;
        Map<RegistryKey<World>, ServerWorld> worlds = serverAccessor.getWorlds();

        ServerWorld existing = worlds.get(worldKey);
        if (existing != null) {
            return existing;
        }

        MutableRegistry<DimensionOptions> dimRegistry = this.getDimensionRegistry();
        RegistryKey<DimensionOptions> dimOptionsKey = RegistryKey.of(RegistryKeys.DIMENSION, def.getDimensionIdentifier());
        DimensionOptions options = dimRegistry.get(dimOptionsKey);
        if (options == null) {
            return null;
        }

        ServerWorld overworld = this.server.getOverworld();
        SaveProperties saveProperties = serverAccessor.getSaveProperties();
        ServerWorldProperties worldProperties = (ServerWorldProperties) new UnmodifiableLevelProperties(saveProperties, saveProperties.getMainWorldProperties());
        long worldSeed = def.getSeed() != null ? def.getSeed() : overworld.getSeed();

        ServerWorld newWorld = new ServerWorld(
                this.server,
                serverAccessor.getWorkerExecutor(),
                serverAccessor.getSession(),
                worldProperties,
                worldKey,
                options,
            NO_OP_WORLD_GEN_PROGRESS,
                false,
                worldSeed,
                List.of(),
                false,
                overworld.getRandomSequences()
        );

        worlds.put(worldKey, newWorld);
        lastPlayerPresence.put(worldKey, (long) this.server.getTicks());
        // Fabric contract for dynamic world registration: mods that add a
        // ServerWorld outside createWorlds MUST fire LOAD, or every mod that
        // builds a per-level map from this event (Distant Horizons, BlueMap,
        // c2me) never learns the world exists. Skipping it NPE'd Distant
        // Horizons on the first portal teleport in production (2026-07-12).
        ServerWorldEvents.LOAD.invoker().onWorldLoad(this.server, newWorld);
        MultiverseServer.LOGGER.info("Created runtime world: {}", worldKey.getValue());
        return newWorld;
    }

    public void registerDimension(DimensionConfig def) {
        if (this.server == null) {
            return;
        }
        MutableRegistry<DimensionOptions> dimRegistry = this.getDimensionRegistry();
        SimpleRegistryAccessor accessor = (SimpleRegistryAccessor) dimRegistry;
        RegistryKey<DimensionOptions> key = RegistryKey.of(RegistryKeys.DIMENSION, def.getDimensionIdentifier());

        if (dimRegistry.contains(key)) {
            return;
        }

        boolean wasFrozen = accessor.isFrozen();
        if (wasFrozen) {
            accessor.setFrozen(false);
        }
        try {
            DimensionOptions options = this.createDimensionOptions(def);
            dimRegistry.add(key, options, RegistryEntryInfo.DEFAULT);
            MultiverseServer.LOGGER.info("Registered dimension: {}", key);
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to register dimension {}", key, e);
        } finally {
            if (wasFrozen) {
                accessor.setFrozen(true);
            }
        }
    }

    public void updatePlayerPresence(RegistryKey<World> worldKey, boolean hasPlayers) {
        if (hasPlayers) {
            lastPlayerPresence.put(worldKey, server != null ? (long) server.getTicks() : 0L);
        }
    }

    public void unloadIdleDimensions(MinecraftServer server, int idleMinutes) {
        if (server == null) {
            return;
        }
        long currentTick = server.getTicks();
        long idleTicks = (long) idleMinutes * 60 * 20;
        MinecraftServerAccessor serverAccessor = (MinecraftServerAccessor) server;
        Map<RegistryKey<World>, ServerWorld> worlds = serverAccessor.getWorlds();

        List<RegistryKey<World>> toUnload = new ArrayList<>();
        for (Map.Entry<RegistryKey<World>, ServerWorld> entry : worlds.entrySet()) {
            RegistryKey<World> key = entry.getKey();
            if (PROTECTED_DIMENSIONS.contains(key)) {
                continue;
            }
            // Namespace first, then path: another mod's dimension whose PATH
            // happens to match one of our names must never be closed by us.
            if (!MultiverseConfig.getInstance().isManagedNamespace(key.getValue().getNamespace())) {
                continue;
            }
            if (MultiverseConfig.getInstance().getDimension(key.getValue().getPath()) == null) {
                continue;
            }

            ServerWorld world = entry.getValue();
            if (!world.getPlayers().isEmpty()) {
                continue;
            }
            if (!world.getForcedChunks().isEmpty()) {
                continue;
            }

            long lastPresence = lastPlayerPresence.getOrDefault(key, 0L);
            if (currentTick - lastPresence >= idleTicks) {
                toUnload.add(key);
            }
        }

        for (RegistryKey<World> key : toUnload) {
            if (this.closeWorld(server, key)) {
                MultiverseServer.LOGGER.info("Unloading idle dimension: {} (no players for {} min)", key.getValue(), idleMinutes);
            }
        }
    }

    // Shared teardown for idle unload and delete: save, fire UNLOAD (before
    // close, so listeners can release handles while the world is usable —
    // matches Fabric's own shutdown ordering), close, drop from the map.
    private boolean closeWorld(MinecraftServer server, RegistryKey<World> key) {
        Map<RegistryKey<World>, ServerWorld> worlds = ((MinecraftServerAccessor) server).getWorlds();
        ServerWorld world = worlds.get(key);
        if (world == null) {
            return false;
        }
        try {
            world.save(null, false, false);
            ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, world);
            world.close();
            worlds.remove(key);
            lastPlayerPresence.remove(key);
            return true;
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to save dimension before unload: {}", key.getValue(), e);
            return false;
        }
    }

    public void requestWorldUnload(String name) {
        this.pendingWorldUnloads.add(name);
    }

    public void processPendingWorldUnloads() {
        if (this.pendingWorldUnloads.isEmpty() || this.server == null) {
            return;
        }
        for (String name : new ArrayList<>(this.pendingWorldUnloads)) {
            this.pendingWorldUnloads.remove(name);
            RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, this.identifierFor(name));
            if (PROTECTED_DIMENSIONS.contains(key)) {
                continue;
            }
            Map<RegistryKey<World>, ServerWorld> worlds = ((MinecraftServerAccessor) this.server).getWorlds();
            ServerWorld world = worlds.get(key);
            if (world == null) {
                continue;
            }
            // Evacuate before teardown — a player inside a closed world is a
            // guaranteed desync/disconnect (that's how the DH incident felt).
            ServerWorld overworld = this.server.getOverworld();
            net.minecraft.util.math.BlockPos spawn = overworld.getSpawnPos();
            for (net.minecraft.server.network.ServerPlayerEntity player : new ArrayList<>(world.getPlayers())) {
                player.teleport(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(), player.getYaw(), player.getPitch());
            }
            if (this.closeWorld(this.server, key)) {
                MultiverseServer.LOGGER.info("Unloaded deleted dimension: {} (world files remain on disk; registry entry clears on next restart)", key.getValue());
            }
        }
    }

    public void requestWorldLoad(String name) {
        if (MultiverseConfig.getInstance().getDimension(name) != null) {
            this.pendingWorldLoads.add(name);
        }
    }

    // Command path: queue a load for a dimension that has no config entry
    // (its options were registered directly by /customdim create).
    public void requestWorldLoadDirect(String name) {
        this.pendingWorldLoads.add(name);
    }

    public ServerWorld getOrCreateDimensionDirect(String dimName) {
        if (this.server == null) {
            return null;
        }
        Identifier dimId = this.identifierFor(dimName);
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
        MinecraftServerAccessor serverAccessor = (MinecraftServerAccessor) this.server;
        Map<RegistryKey<World>, ServerWorld> worlds = serverAccessor.getWorlds();

        ServerWorld existing = worlds.get(worldKey);
        if (existing != null) {
            return existing;
        }

        MutableRegistry<DimensionOptions> dimRegistry = this.getDimensionRegistry();
        RegistryKey<DimensionOptions> dimOptionsKey = RegistryKey.of(RegistryKeys.DIMENSION, dimId);
        DimensionOptions options = dimRegistry.get(dimOptionsKey);
        if (options == null) {
            MultiverseServer.LOGGER.error("No dimension options registered for {}", dimId);
            return null;
        }

        ServerWorld overworld = this.server.getOverworld();
        SaveProperties saveProperties = serverAccessor.getSaveProperties();
        ServerWorldProperties worldProperties = (ServerWorldProperties) new UnmodifiableLevelProperties(saveProperties, saveProperties.getMainWorldProperties());
        DimensionConfig runtimeDef = this.runtimeDefinitions.get(dimName);
        long worldSeed = runtimeDef != null && runtimeDef.getSeed() != null
                ? runtimeDef.getSeed() : overworld.getSeed();

        ServerWorld newWorld = new ServerWorld(
                this.server, serverAccessor.getWorkerExecutor(), serverAccessor.getSession(),
                worldProperties, worldKey, options, NO_OP_WORLD_GEN_PROGRESS,
                false, worldSeed, List.of(), false, overworld.getRandomSequences());

        worlds.put(worldKey, newWorld);
        lastPlayerPresence.put(worldKey, (long) this.server.getTicks());
        ServerWorldEvents.LOAD.invoker().onWorldLoad(this.server, newWorld);
        MultiverseServer.LOGGER.info("Created runtime world (direct): {}", worldKey.getValue());
        return newWorld;
    }

    public void processPendingWorldLoads() {
        if (this.pendingWorldLoads.isEmpty()) {
            return;
        }
        for (String name : new ArrayList<>(this.pendingWorldLoads)) {
            this.pendingWorldLoads.remove(name);
            this.getOrCreateDimension(name);
        }
    }

    public void bootCreateDimensions() {
        for (DimensionConfig def : MultiverseConfig.getInstance().getDimensions()) {
            this.requestWorldLoad(def.getName());
        }
    }

    public void reconcileOrphansOnce() {
        if (this.bootReconciled || this.server == null) {
            return;
        }
        this.bootReconciled = true;
        Map<RegistryKey<World>, ServerWorld> worlds = ((MinecraftServerAccessor) this.server).getWorlds();
        List<String> configNames = MultiverseConfig.getInstance().getDimensionNames();
        for (RegistryKey<World> key : worlds.keySet()) {
            if (PROTECTED_DIMENSIONS.contains(key)) {
                continue;
            }
            if (!MultiverseConfig.getInstance().isManagedNamespace(key.getValue().getNamespace())) {
                continue;
            }
            String path = key.getValue().getPath();
            if (!configNames.contains(path)) {
                MultiverseServer.LOGGER.info("Orphan dimension detected: {} — queuing unload", key.getValue());
                this.requestWorldUnload(path);
            }
        }
    }

    public boolean dimensionExists(String name) {
        return MultiverseConfig.getInstance().getDimension(name) != null;
    }

    // Config first, then runtime (command-created) definitions.
    public DimensionConfig resolveDefinition(String name) {
        DimensionConfig def = MultiverseConfig.getInstance().getDimension(name);
        return def != null ? def : this.runtimeDefinitions.get(name);
    }

    public void rememberRuntimeDefinition(DimensionConfig def) {
        this.runtimeDefinitions.put(def.getName(), def);
    }

    // Identifier for a dimension slug: the config's own namespace when a
    // definition exists (consumer-added dims may live under BRAND_SLUG),
    // otherwise the platform namespace.
    private Identifier identifierFor(String name) {
        DimensionConfig def = this.resolveDefinition(name);
        return def != null
                ? def.getDimensionIdentifier()
                : Identifier.of(MultiverseConfig.getInstance().getNamespace(), name);
    }

    public void forgetRuntimeDefinition(String name) {
        this.runtimeDefinitions.remove(name);
    }

    public MinecraftServer getServer() {
        return this.server;
    }
}
