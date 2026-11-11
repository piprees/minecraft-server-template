package com.customdimensions.server.level;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.DefinitionSettings;
import com.customdimensions.core.definition.Seed;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The seed every level generates from (decisions.md RB-37): its definition's
 * {@code seed}, or the world seed for {@code "env"} or none, held to the seed
 * its chunks already generated from ({@link LevelMaths#chooseSeed}). Each
 * level's seed is recorded in {@value #RECORD} in its dimension folder.
 * <p>
 * The base worlds' seeds are chosen when the server starts, before vanilla
 * creates their levels; {@code ServerLevelSeedMixin} answers
 * {@code getSeed()} from them and {@code MinecraftServerLevelsMixin} gives
 * each its own biome-zoom seed. A lazy level's seed is chosen as it opens and
 * handed to Fantasy, whose level answers {@code getSeed()} itself.
 */
public final class LevelSeeds {
    public static final String RECORD = "customdimensions-seed.json";

    private static volatile Map<ResourceKey<Level>, Long> base = Map.of();

    private LevelSeeds() {
    }

    /** Registered before every other start-up listener that reads a level's seed. */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(LevelSeeds::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> base = Map.of());
    }

    /** The seed of the base world {@code key}, or null for any other level; read on every {@code getSeed()}. */
    public static Long baseSeedOrNull(ResourceKey<Level> key) {
        return base.get(key);
    }

    /** The world seed: {@code level-seed}, what {@code "env"} means. */
    public static long worldSeed(MinecraftServer server) {
        return server.getWorldGenSettings().options().seed();
    }

    private static void start(MinecraftServer server) {
        long world = worldSeed(server);
        Optional<Path> dir = LevelIndex.definitionsDir();
        String namespace = LevelIndex.DEFAULT_NAMESPACE;
        if (dir.isPresent()) {
            try {
                DefinitionSettings settings = DefinitionReader.readSettings(dir.get().resolve("settings.json"));
                if (settings.namespace() != null) namespace = settings.namespace();
            } catch (RuntimeException e) {
                CustomDimensions.LOG.warn("seeds: settings.json does not read ({}); the base worlds use the world seed", e.getMessage());
            }
        }
        Map<ResourceKey<Level>, Long> seeds = new LinkedHashMap<>();
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, String> w : LevelIndex.baseWorldKeys(namespace).entrySet()) {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(w.getValue()));
            Seed seed = dir.map(d -> definitionSeed(d.resolve(w.getKey() + ".json"))).orElse(null);
            long chosen = choose(server, key, LevelMaths.resolveSeed(seed, world), world, true);
            seeds.put(key, chosen);
            line.append(line.isEmpty() ? "" : ", ").append(w.getKey()).append(' ').append(chosen);
        }
        base = Map.copyOf(seeds);
        CustomDimensions.LOG.info("seeds: world {}; {}", world, line);
    }

    /**
     * The seed the lazy level {@code key} opens with, recorded beside its
     * chunks; {@code definitionSeed} is its definition's, or null.
     */
    public static long lazySeed(MinecraftServer server, ResourceKey<Level> key, Seed definitionSeed) {
        long wanted = LevelMaths.resolveSeed(definitionSeed, worldSeed(server));
        return choose(server, key, wanted, wanted, true);
    }

    /** As {@link #lazySeed} without writing the record: the seed a closed lazy level would open with. */
    public static long peekLazySeed(MinecraftServer server, ResourceKey<Level> key, Seed definitionSeed) {
        long wanted = LevelMaths.resolveSeed(definitionSeed, worldSeed(server));
        return choose(server, key, wanted, wanted, false);
    }

    private static long choose(MinecraftServer server, ResourceKey<Level> key, long wanted, long unrecorded, boolean write) {
        Path folder = dimensionPath(server, key);
        LevelMaths.SeedChoice choice = LevelMaths.chooseSeed(key.identifier().toString(), wanted, readRecord(folder),
                PackInstaller.holdsChunks(folder.resolve("region")), unrecorded);
        if (write) {
            if (choice.warning() != null) CustomDimensions.LOG.warn("seeds: {}", choice.warning());
            if (choice.write()) writeRecord(folder, choice.seed());
        }
        return choice.seed();
    }

    static Path dimensionPath(MinecraftServer server, ResourceKey<Level> key) {
        return server.getWorldPath(LevelResource.ROOT).resolve("dimensions")
                .resolve(key.identifier().getNamespace()).resolve(key.identifier().getPath());
    }

    private static Seed definitionSeed(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            Definition definition = DefinitionReader.read(file);
            return definition.seed();
        } catch (RuntimeException e) {
            CustomDimensions.LOG.warn("seeds: {} does not read ({}); its level uses the world seed", file, e.getMessage());
            return null;
        }
    }

    /** The recorded seed in {@code folder}, or null when there is none or it does not read. */
    static Long readRecord(Path folder) {
        Path file = folder.resolve(RECORD);
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return json.get("seed").getAsLong();
        } catch (IOException | RuntimeException e) {
            CustomDimensions.LOG.warn("seeds: {} does not read ({}); treated as absent", file, e.getMessage());
            return null;
        }
    }

    static void writeRecord(Path folder, long seed) {
        JsonObject json = new JsonObject();
        json.addProperty("seed", seed);
        try {
            Files.createDirectories(folder);
            Path tmp = folder.resolve(RECORD + ".tmp");
            Files.writeString(tmp, json + "\n");
            Files.move(tmp, folder.resolve(RECORD), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CustomDimensions.LOG.warn("seeds: could not record seed {} in {}: {}", seed, folder, e.getMessage());
        }
    }
}
