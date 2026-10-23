package com.customdimensions.server.level;

import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.DefinitionSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * Every dimension the runtime knows: the lazy level stems the compiled pack
 * carries in {@code data/customdimensions/lazy_dimension/}, each decoded with
 * the game's codec and paired with its v2 definition, and the four base
 * worlds vanilla loads at start. Built at SERVER_STARTED from the server's
 * resource manager; definitions come from {@link #definitionsDir()}.
 */
public final class LevelIndex {
    public static final String DEFINITIONS_PROPERTY = "customdimensions.definitions";
    /** Where definitions live when the property is unset, under the game directory. */
    public static final String DEFINITIONS_FALLBACK = "config/customdimensions/definitions";
    public static final String DEFAULT_NAMESPACE = "adventure";

    /** A lazy dimension: its stem as compiled, its definition (null when missing) and its border radius. */
    public record Entry(String slug, ResourceKey<Level> key, JsonElement stem, Definition definition, Integer borderRadius) {}

    /** A world vanilla loads at start, and the border its definition asks for. */
    public record BaseWorld(String slug, ResourceKey<Level> key, Definition definition, Integer borderRadius) {}

    private final String namespace;
    private final Integer idleUnloadMinutes;
    private final SortedMap<String, Entry> lazy;
    private final List<BaseWorld> baseWorlds;
    private final List<String> problems;

    private LevelIndex(String namespace, Integer idleUnloadMinutes, SortedMap<String, Entry> lazy,
                       List<BaseWorld> baseWorlds, List<String> problems) {
        this.namespace = namespace;
        this.idleUnloadMinutes = idleUnloadMinutes;
        this.lazy = Collections.unmodifiableSortedMap(lazy);
        this.baseWorlds = List.copyOf(baseWorlds);
        this.problems = List.copyOf(problems);
    }

    /** {@code -Dcustomdimensions.definitions}, else {@value #DEFINITIONS_FALLBACK} when it exists, else empty. */
    public static Optional<Path> definitionsDir() {
        String property = System.getProperty(DEFINITIONS_PROPERTY, "");
        if (!property.isEmpty()) return Optional.of(Path.of(property));
        Path fallback = FabricLoader.getInstance().getGameDir().resolve(DEFINITIONS_FALLBACK);
        return Files.isDirectory(fallback) ? Optional.of(fallback) : Optional.empty();
    }

    public static LevelIndex load(MinecraftServer server) {
        List<String> problems = new ArrayList<>();
        Optional<Path> dir = definitionsDir();
        DefinitionSettings settings = null;
        if (dir.isEmpty()) {
            problems.add("no definitions: set -D" + DEFINITIONS_PROPERTY + " or provide " + DEFINITIONS_FALLBACK);
        } else {
            try {
                settings = DefinitionReader.readSettings(dir.get().resolve("settings.json"));
            } catch (RuntimeException e) {
                problems.add("settings.json: " + e.getMessage());
            }
        }
        String namespace = settings != null && settings.namespace() != null ? settings.namespace() : DEFAULT_NAMESPACE;
        Integer idle = settings == null ? null : settings.idleUnloadMinutes();
        var defaults = settings == null ? null : settings.defaults();

        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        SortedMap<String, Entry> lazy = new TreeMap<>();
        Map<Identifier, Resource> stems = server.getResourceManager().listResources(DatapackCompiler.LAZY_FOLDER,
                id -> id.getNamespace().equals(DatapackCompiler.RUNTIME_NAMESPACE) && id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> file : new TreeMap<>(stems).entrySet()) {
            String path = file.getKey().getPath();
            String slug = path.substring(DatapackCompiler.LAZY_FOLDER.length() + 1, path.length() - ".json".length());
            JsonElement json;
            try (Reader reader = file.getValue().openAsReader()) {
                json = JsonParser.parseReader(reader);
            } catch (Exception e) {
                problems.add(slug + ": unreadable stem: " + e);
                continue;
            }
            Optional<String> error = LevelStem.CODEC.parse(ops, json).error().map(DataResult.Error::message);
            if (error.isPresent()) {
                problems.add(slug + ": stem does not decode: " + error.get());
                continue;
            }
            Definition definition = dir.map(d -> definition(d, slug, problems)).orElse(null);
            Integer radius = LevelMaths.playerBorderRadius(definition == null ? null : definition.borders(), defaults);
            lazy.put(slug, new Entry(slug, levelKey(namespace, slug), json, definition, radius));
        }

        List<BaseWorld> base = new ArrayList<>();
        for (Map.Entry<String, String> world : baseWorldKeys(namespace).entrySet()) {
            Definition definition = dir.map(d -> definition(d, world.getKey(), problems)).orElse(null);
            Integer radius = LevelMaths.playerBorderRadius(definition == null ? null : definition.borders(), defaults);
            base.add(new BaseWorld(world.getKey(), ResourceKey.create(Registries.DIMENSION, Identifier.parse(world.getValue())),
                    definition, radius));
        }
        return new LevelIndex(namespace, idle, lazy, base, problems);
    }

    /** Base worlds by slug, in load order: the level id vanilla gives each. */
    static Map<String, String> baseWorldKeys(String namespace) {
        Map<String, String> keys = new java.util.LinkedHashMap<>();
        keys.put("overworld", "minecraft:overworld");
        keys.put("the_nether", "minecraft:the_nether");
        keys.put("the_end", "minecraft:the_end");
        keys.put("the_aviary", namespace + ":the_aviary");
        return keys;
    }

    private static Definition definition(Path dir, String slug, List<String> problems) {
        Path file = dir.resolve(slug + ".json");
        if (!Files.isRegularFile(file)) {
            problems.add(slug + ": no definition at " + file);
            return null;
        }
        try {
            return DefinitionReader.read(file);
        } catch (RuntimeException e) {
            problems.add(slug + ": definition does not read: " + e.getMessage());
            return null;
        }
    }

    static ResourceKey<Level> levelKey(String namespace, String slug) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(namespace, slug));
    }

    /** Decodes a fresh stem for one opening; the index has already checked that it decodes. */
    public LevelStem decode(MinecraftServer server, Entry entry) {
        return LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), entry.stem()).getOrThrow();
    }

    public String namespace() {
        return namespace;
    }

    public Integer idleUnloadMinutes() {
        return idleUnloadMinutes;
    }

    public SortedMap<String, Entry> lazy() {
        return lazy;
    }

    public Optional<Entry> lazy(String slug) {
        return Optional.ofNullable(lazy.get(slug));
    }

    public List<BaseWorld> baseWorlds() {
        return baseWorlds;
    }

    /** Stems that did not decode, definitions that are missing or unreadable. */
    public List<String> problems() {
        return problems;
    }

    /** Lazy entries that decoded and have a definition. */
    public long paired() {
        return lazy.values().stream().filter(e -> e.definition() != null).count();
    }
}
