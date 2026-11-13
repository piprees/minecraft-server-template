package com.customdimensions.server.level;

import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.VanillaForms;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.mixin.HolderReferenceAccessor;
import com.customdimensions.server.mixin.MappedRegistryAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.dimension.LevelStem;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Binds the pack's {@code data/customdimensions/runtime_registry/<ns>/<registry>/<path>.json}
 * files in place of the entries vanilla loaded from {@code data/<ns>/<registry>/<path>.json}
 * ({@link VanillaForms}): the files vanilla reads hold vanilla forms, these the compiled
 * forms with our types. It runs first at SERVER_STARTING, before any level, generator or
 * other listener reads the registries, so every reader of the entry, by holder or by key,
 * sees the compiled form. A file it cannot bind stops the start: generating from the
 * vanilla form would change terrain for good (D2).
 */
public final class RuntimeRegistry {

    /** One bound entry: its registry, its id and the value now bound. */
    public record Bound(ResourceKey<? extends Registry<?>> registry, Identifier id, Object value) {}

    /** A runtime file's registry directory and the element id it replaces. */
    record Target(String directory, Identifier id) {}

    private static volatile List<Bound> bound = List.of();

    private RuntimeRegistry() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(RuntimeRegistry::bind);
    }

    /** What the last start bound. */
    public static List<Bound> bound() {
        return bound;
    }

    /**
     * The registry directory and element id of {@code runtime_registry/<ns>/<directory>/<path>.json}:
     * the longest of {@code directories} the path continues with; null when none does.
     */
    static Target target(String path, Collection<String> directories) {
        String prefix = VanillaForms.FOLDER + "/";
        if (!path.startsWith(prefix) || !path.endsWith(".json")) return null;
        String rest = path.substring(prefix.length(), path.length() - ".json".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String namespace = rest.substring(0, slash);
        String inner = rest.substring(slash + 1);
        String best = null;
        for (String directory : directories) {
            if (inner.startsWith(directory + "/") && inner.length() > directory.length() + 1
                    && (best == null || directory.length() > best.length())) {
                best = directory;
            }
        }
        return best == null ? null : new Target(best, Identifier.fromNamespaceAndPath(namespace, inner.substring(best.length() + 1)));
    }

    private static void bind(MinecraftServer server) {
        Map<String, RegistryDataLoader.RegistryData<?>> byDirectory = new TreeMap<>();
        List<RegistryDataLoader.RegistryData<?>> registries = new ArrayList<>(RegistryDataLoader.WORLD_REGISTRIES);
        registries.addAll(RegistryDataLoader.DIMENSION_REGISTRIES);
        registries.forEach(r -> byDirectory.put(Registries.elementsDirPath(r.key()), r));
        RegistryAccess access = server.registryAccess();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Map<Identifier, Resource> files = new TreeMap<>(server.getResourceManager().listResources(VanillaForms.FOLDER,
                id -> id.getNamespace().equals(DatapackCompiler.RUNTIME_NAMESPACE) && id.getPath().endsWith(".json")));
        List<Bound> out = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
            Target target = target(file.getKey().getPath(), byDirectory.keySet());
            if (target == null) {
                throw new IllegalStateException(file.getKey() + " names no registry the runtime binds");
            }
            JsonElement json;
            try (Reader reader = file.getValue().openAsReader()) {
                json = JsonParser.parseReader(reader);
            } catch (IOException e) {
                throw new UncheckedIOException(file.getKey() + " is unreadable", e);
            }
            out.add(bind(access, ops, byDirectory.get(target.directory()), target.id(), json, file.getKey()));
        }
        bound = List.copyOf(out);
        CustomDimensions.LOG.info("runtime registry: {} compiled form(s) bound in place of their vanilla forms: {}", out.size(),
                out.stream().map(b -> b.id().toString()).toList());
    }

    private static <T> Bound bind(RegistryAccess access, RegistryOps<JsonElement> ops, RegistryDataLoader.RegistryData<T> data,
                                  Identifier id, JsonElement json, Identifier file) {
        Registry<T> registry = access.lookupOrThrow(data.key());
        Holder.Reference<T> holder = registry.get(ResourceKey.create(data.key(), id)).orElseThrow(() -> new IllegalStateException(
                file + ": " + id + " is not in " + data.key().identifier() + ", so its vanilla form never loaded"));
        T value = data.elementCodec().parse(ops, json).getOrThrow(message -> new IllegalStateException(file + ": " + message));
        T vanilla = holder.value();
        ((HolderReferenceAccessor) (Object) holder).customdimensions$bindValue(value);
        if (registry instanceof MappedRegistry<T> mapped) {
            @SuppressWarnings("unchecked")
            MappedRegistryAccessor<T> lookups = (MappedRegistryAccessor<T>) (Object) mapped;
            lookups.customdimensions$byValue().remove(vanilla);
            lookups.customdimensions$byValue().put(value, holder);
            int index = lookups.customdimensions$toId().removeInt(vanilla);
            lookups.customdimensions$toId().put(value, index);
        }
        return new Bound(data.key(), id, value);
    }

    /**
     * The self-test's line: each bound level stem's level generates from the bound generator,
     * so a base world takes its compiled biome source and router.
     */
    public static List<String> selfTest(MinecraftServer server) {
        List<String> wrong = new ArrayList<>();
        List<String> levels = new ArrayList<>();
        for (Bound b : bound) {
            if (!b.registry().equals(Registries.LEVEL_STEM)) continue;
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, b.id()));
            if (level == null) continue;
            if (level.getChunkSource().getGenerator() != ((LevelStem) b.value()).generator()) {
                wrong.add(b.id() + " generates from another generator than its bound stem's");
            } else {
                levels.add(b.id().toString());
            }
        }
        return List.of((wrong.isEmpty() ? "PASS" : "FAIL") + " runtime registry binds the compiled forms: " + (wrong.isEmpty()
                ? bound.size() + " entries " + bound.stream().map(b -> b.id().toString()).toList() + ", open levels on their bound stems "
                + levels : String.join("; ", wrong)));
    }

    /** Each bound entry is what its registry answers by key; throws naming the first that is not. */
    public static String byKey(MinecraftServer server) {
        for (Bound b : bound) {
            if (value(server.registryAccess(), b) != b.value()) throw new AssertionError(b.id() + " reads as another value by key");
        }
        return bound.size() + " entries read their compiled forms";
    }

    /** Each bound entry's value reads back its key; throws naming the first that does not. */
    public static String byValue(MinecraftServer server) {
        for (Bound b : bound) {
            Identifier key = key(server.registryAccess(), b);
            if (!b.id().equals(key)) throw new AssertionError(b.id() + "'s compiled form reads back as " + key);
        }
        return bound.size() + " compiled forms read back their keys";
    }

    @SuppressWarnings("unchecked")
    private static <T> T value(RegistryAccess access, Bound b) {
        Registry<T> registry = access.lookupOrThrow((ResourceKey<? extends Registry<T>>) b.registry());
        return registry.getValue(ResourceKey.create(registry.key(), b.id()));
    }

    @SuppressWarnings("unchecked")
    private static <T> Identifier key(RegistryAccess access, Bound b) {
        Registry<T> registry = access.lookupOrThrow((ResourceKey<? extends Registry<T>>) b.registry());
        return registry.getKey((T) b.value());
    }
}
