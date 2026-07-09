package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionDefinition;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.mixin.MinecraftServerAccessor;
import com.customdimensions.mixin.SimpleRegistryAccessor;
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

    public static DimensionManager getInstance() {
        return INSTANCE;
    }

    public void onServerStart(MinecraftServer server) {
        this.server = server;
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
            for (DimensionDefinition def : MultiverseConfig.getInstance().getDimensions()) {
                RegistryKey<DimensionOptions> key = RegistryKey.of(RegistryKeys.DIMENSION, def.getDimensionIdentifier());
                if (dimRegistry.contains(key)) {
                    continue;
                }
                DimensionOptions options = this.createDimensionOptions(def);
                dimRegistry.add(key, options, RegistryEntryInfo.DEFAULT);
                MultiverseServer.LOGGER.info("Registered dimension: {}", key);
            }
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to register dimensions", e);
        } finally {
            if (wasFrozen) {
                accessor.setFrozen(true);
            }
        }
    }

    private DimensionOptions createDimensionOptions(DimensionDefinition def) {
        MutableRegistry<DimensionOptions> dimRegistry = this.getDimensionRegistry();
        DynamicRegistryManager.Immutable regManager = this.server.getCombinedDynamicRegistries().getCombinedRegistryManager();
        Registry<Biome> biomeRegistry = regManager.get(RegistryKeys.BIOME);
        String type = def.getType();
        long worldSeed = def.getSeed() != null ? def.getSeed() : this.server.getOverworld().getSeed();

        DimensionOptions overworldOpts = dimRegistry.get(DimensionOptions.OVERWORLD);
        if (overworldOpts == null) {
            throw new IllegalStateException("Cannot create dimension options: overworld not found");
        }

        return switch (type) {
            case "void" -> {
                RegistryEntry<Biome> voidBiome = biomeRegistry.getEntry(biomeRegistry.get(BiomeKeys.THE_VOID));
                FlatChunkGeneratorConfig config = new FlatChunkGeneratorConfig(Optional.empty(), voidBiome, List.of())
                    .with(List.of(), Optional.empty(), voidBiome);
                yield new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(new FlatChunkGenerator(config), worldSeed));
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
                yield new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(new FlatChunkGenerator(config), worldSeed));
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
                    yield new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(newGen, worldSeed));
                }
                yield new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(overworldOpts.chunkGenerator(), worldSeed));
            }
            case "multi_biome" -> {
                String biomeList = def.getBiome();
                if (biomeList == null || biomeList.isEmpty()) {
                    biomeList = "minecraft:plains";
                }
                Set<Identifier> allowedIds = Arrays.stream(biomeList.split(","))
                        .map(String::trim)
                        .map(Identifier::tryParse)
                        .filter(id -> id != null)
                        .collect(Collectors.toSet());

                if (overworldOpts.chunkGenerator() instanceof NoiseChunkGenerator noiseGen
                        && noiseGen.getBiomeSource() instanceof MultiNoiseBiomeSource multiSource) {
                    // Extract biome entries from the live overworld source (includes Terralith)
                    // and filter to only the allowed biomes
                    List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> filtered = multiSource.getBiomeEntries()
                            .getEntries().stream()
                            .filter(pair -> {
                                Identifier biomeId = pair.getSecond().getKey()
                                        .map(key -> key.getValue())
                                        .orElse(null);
                                return biomeId != null && allowedIds.contains(biomeId);
                            })
                            .collect(Collectors.toList());

                    if (filtered.isEmpty()) {
                        MultiverseServer.LOGGER.warn("No matching biomes found for multi_biome dimension {}, falling back to plains", def.getName());
                        RegistryEntry<Biome> plains = biomeRegistry.getEntry(biomeRegistry.get(BiomeKeys.PLAINS));
                        filtered.add(Pair.of(MultiNoiseUtil.createNoiseHypercube(0, 0, 0, 0, 0, 0, 0), plains));
                    }

                    MultiNoiseBiomeSource filteredSource = MultiNoiseBiomeSource.create(new MultiNoiseUtil.Entries<>(filtered));
                    NoiseChunkGenerator newGen = new NoiseChunkGenerator(filteredSource, noiseGen.getSettings());
                    yield new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(newGen, worldSeed));
                }
                yield new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(overworldOpts.chunkGenerator(), worldSeed));
            }
            case "nether" -> {
                DimensionOptions source = dimRegistry.get(DimensionOptions.NETHER);
                yield source != null
                        ? new DimensionOptions(source.dimensionTypeEntry(), withSeed(source.chunkGenerator(), worldSeed))
                        : new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(overworldOpts.chunkGenerator(), worldSeed));
            }
            case "end" -> {
                DimensionOptions source = dimRegistry.get(DimensionOptions.END);
                yield source != null
                        ? new DimensionOptions(source.dimensionTypeEntry(), withSeed(source.chunkGenerator(), worldSeed))
                        : new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(overworldOpts.chunkGenerator(), worldSeed));
            }
            case "amplified", "large_biomes" -> {
                MultiverseServer.LOGGER.warn("Dimension type '{}' currently maps to overworld terrain settings", type);
                yield new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(overworldOpts.chunkGenerator(), worldSeed));
            }
            default -> new DimensionOptions(overworldOpts.dimensionTypeEntry(), withSeed(overworldOpts.chunkGenerator(), worldSeed));
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
        DimensionDefinition def = MultiverseConfig.getInstance().getDimension(dimName);
        if (def == null) {
            return null;
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
        MultiverseServer.LOGGER.info("Created runtime world: {}", worldKey.getValue());
        return newWorld;
    }

    public void registerDimension(DimensionDefinition def) {
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
            ServerWorld world = worlds.get(key);
            if (world == null) {
                continue;
            }
            try {
                world.save(null, false, false);
                world.close();
                worlds.remove(key);
                lastPlayerPresence.remove(key);
                MultiverseServer.LOGGER.info("Unloading idle dimension: {} (no players for {} min)", key.getValue(), idleMinutes);
            } catch (Exception e) {
                MultiverseServer.LOGGER.error("Failed to save dimension before unload: {}", key.getValue(), e);
            }
        }
    }

    public boolean dimensionExists(String name) {
        return MultiverseConfig.getInstance().getDimension(name) != null;
    }

    public MinecraftServer getServer() {
        return this.server;
    }
}
