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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.UnmodifiableLevelProperties;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DimensionManager {
    private static final DimensionManager INSTANCE = new DimensionManager();
    private MinecraftServer server;

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
        Identifier typeId = Identifier.of(def.getType());
        String path = typeId.getPath();

        if (path.equals("void")) {
            DimensionOptions overworldOpts = dimRegistry.get(DimensionOptions.OVERWORLD);
            if (overworldOpts != null) {
                DynamicRegistryManager.Immutable regManager = this.server.getCombinedDynamicRegistries().getCombinedRegistryManager();
                Registry<Biome> biomeRegistry = regManager.get(RegistryKeys.BIOME);
                Biome biomeValue = biomeRegistry.get(BiomeKeys.THE_VOID);
                RegistryEntry<Biome> voidBiome = biomeRegistry.getEntry(biomeValue);
                FlatChunkGeneratorConfig voidConfig = new FlatChunkGeneratorConfig(Optional.empty(), voidBiome, List.of());
                FlatChunkGenerator voidGen = new FlatChunkGenerator(voidConfig);
                return new DimensionOptions(overworldOpts.dimensionTypeEntry(), voidGen);
            }
        }

        RegistryKey<DimensionOptions> sourceKey = switch (path) {
            case "overworld" -> DimensionOptions.OVERWORLD;
            case "nether" -> DimensionOptions.NETHER;
            case "end" -> DimensionOptions.END;
            default -> DimensionOptions.OVERWORLD;
        };

        DimensionOptions source = dimRegistry.get(sourceKey);
        if (source != null) {
            return new DimensionOptions(source.dimensionTypeEntry(), source.chunkGenerator());
        }

        DimensionOptions fallback = dimRegistry.get(DimensionOptions.OVERWORLD);
        if (fallback != null) {
            return new DimensionOptions(fallback.dimensionTypeEntry(), fallback.chunkGenerator());
        }

        throw new IllegalStateException("Cannot create dimension options: no source dimension found");
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

        ServerWorld newWorld = new ServerWorld(
                this.server,
                serverAccessor.getWorkerExecutor(),
                serverAccessor.getSession(),
                worldProperties,
                worldKey,
                options,
                false,
                overworld.getSeed(),
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

    public boolean dimensionExists(String name) {
        return MultiverseConfig.getInstance().getDimension(name) != null;
    }

    public MinecraftServer getServer() {
        return this.server;
    }
}
