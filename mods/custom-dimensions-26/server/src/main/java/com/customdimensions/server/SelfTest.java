package com.customdimensions.server;

import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.VanillaData;
import com.customdimensions.compiler.datapack.VersionTable;
import com.customdimensions.compiler.zone.ZoneDensity;
import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.core.zone.ZoneShape;
import com.customdimensions.server.level.GeneratorSelfTest;
import com.customdimensions.server.level.LevelSelfTest;
import com.customdimensions.server.portal.PortalSelfTest;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.timeline.Timeline;

/**
 * Start-up checks against the running game, enabled by
 * {@code -Dcustomdimensions.selftest=<report file>}: the server writes the
 * report and stops. CI boots the mod this way; a line starting {@code FAIL}
 * fails the build. The runtime-level checks ({@link LevelSelfTest}), then the
 * generator checks ({@link GeneratorSelfTest}), run across ticks, so the report
 * is written when they finish. The compiled-pack checks read the definitions from
 * {@code -Dcustomdimensions.definitions} and vanilla's files from
 * {@code -Dcustomdimensions.vanilla}.
 */
final class SelfTest {
    private static final String PROPERTY = "customdimensions.selftest";
    private static final String DEFINITIONS = "customdimensions.definitions";
    private static final String VANILLA = "customdimensions.vanilla";

    private SelfTest() {}

    static void register() {
        String report = System.getProperty(PROPERTY, "");
        if (report.isEmpty()) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> run(server, Path.of(report)));
    }

    private static void run(MinecraftServer server, Path report) {
        List<String> lines = new ArrayList<>();
        lines.add(check("zone mask parses and samples like core", () -> zoneMask(server)));
        lines.addAll(compiledPack(server));
        CompletableFuture<List<String>> levels;
        try {
            levels = LevelSelfTest.run(server);
        } catch (Throwable t) {
            levels = CompletableFuture.completedFuture(List.of("FAIL runtime levels: " + t));
        }
        levels.handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL runtime levels: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return GeneratorSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL generators: " + t));
            }
        }).handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL generators: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return PortalSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL portals: " + t));
            }
        }).whenComplete((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL portals: " + error));
            try {
                Files.write(report, lines);
            } catch (IOException e) {
                CustomDimensions.LOG.error("self-test report not written", e);
            }
            lines.forEach(CustomDimensions.LOG::info);
            server.halt(false);
        });
    }

    private static String check(String name, Check check) {
        try {
            return "PASS " + name + ": " + check.run();
        } catch (Throwable t) {
            return "FAIL " + name + ": " + t;
        }
    }

    /** Vanilla decodes the compiler's mask and its sampler matches core's maths. */
    private static String zoneMask(MinecraftServer server) {
        ZoneMask zone = new ZoneMask(37, -90, new ZoneShape.Ring(600, 1200), 120);
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        DensityFunction function = DensityFunction.CODEC.parse(ops, ZoneDensity.mask(zone)).getOrThrow();
        RandomState random = server.overworld().getChunkSource().randomState();
        Random points = new Random(11);
        double worst = 0;
        for (int i = 0; i < 2000; i++) {
            int x = 37 + points.nextInt(3001) - 1500;
            int z = -90 + points.nextInt(3001) - 1500;
            int y = points.nextInt(384) - 64;
            double error = Math.abs(random.sampleBlockValueUncached(function, x, y, z) - zone.at(x, z));
            worst = Math.max(worst, error);
        }
        if (worst > 2e-3) {
            throw new AssertionError("vanilla and core differ by " + worst);
        }
        return "2000 points, worst difference " + worst;
    }

    /**
     * Compiles the definitions into a temporary pack and decodes every file with
     * the game's own codecs: timelines, dimension types (which name the pack's
     * timelines), noise settings, then level stems (which name the pack's types
     * and settings). One line per kind.
     */
    private static List<String> compiledPack(MinecraftServer server) {
        List<String> kinds = List.of("timelines", "dimension types", "noise settings", "level stems");
        Path pack;
        try {
            Path definitions = Path.of(requiredProperty(DEFINITIONS));
            VanillaData vanilla = new VanillaData(Path.of(requiredProperty(VANILLA)));
            DatapackCompiler.Output output = DatapackCompiler.compile(definitions, vanilla,
                    VersionTable.forId(server.getServerVersion()));
            Path dir = Files.createTempDirectory("customdim-pack");
            pack = dir.resolve("pack");
            DatapackCompiler.write(output, pack, dir.resolve(DatapackCompiler.REPORT_FILE));
        } catch (Throwable t) {
            return kinds.stream().map(k -> "FAIL compiled " + k + " decode: compile failed: " + t).toList();
        }
        HolderLookup.Provider access = server.registryAccess();
        MappedRegistry<Timeline> timelines = new MappedRegistry<>(Registries.TIMELINE, Lifecycle.stable());
        MappedRegistry<DimensionType> types = new MappedRegistry<>(Registries.DIMENSION_TYPE, Lifecycle.stable());
        MappedRegistry<NoiseGeneratorSettings> settings = new MappedRegistry<>(Registries.NOISE_SETTINGS, Lifecycle.stable());
        RegistryOps<JsonElement> vanillaOps = RegistryOps.create(JsonOps.INSTANCE, access);
        RegistryOps<JsonElement> withPack = RegistryOps.create(JsonOps.INSTANCE,
                withPack(access, Map.of(Registries.TIMELINE, timelines, Registries.DIMENSION_TYPE, types,
                        Registries.NOISE_SETTINGS, settings)));
        List<String> lines = new ArrayList<>();
        lines.add(decodeAll("timelines", files(pack, "timeline"), Timeline.DIRECT_CODEC, vanillaOps,
                (id, v) -> timelines.register(ResourceKey.create(Registries.TIMELINE, id), v, RegistrationInfo.BUILT_IN)));
        lines.add(decodeAll("dimension types", files(pack, "dimension_type"), DimensionType.DIRECT_CODEC, withPack,
                (id, v) -> types.register(ResourceKey.create(Registries.DIMENSION_TYPE, id), v, RegistrationInfo.BUILT_IN)));
        lines.add(decodeAll("noise settings", files(pack, "worldgen/noise_settings"), NoiseGeneratorSettings.DIRECT_CODEC,
                vanillaOps, (id, v) -> settings.register(ResourceKey.create(Registries.NOISE_SETTINGS, id), v,
                        RegistrationInfo.BUILT_IN)));
        List<PackFile> stems = new ArrayList<>(files(pack, "dimension"));
        stems.addAll(files(pack, DatapackCompiler.LAZY_FOLDER));
        lines.add(decodeAll("level stems", stems, LevelStem.CODEC, withPack, (id, v) -> {}));
        lines.add(check("compiled-pack decoding refuses an unknown reference", () -> {
            com.google.gson.JsonObject stem = new com.google.gson.JsonObject();
            stem.addProperty("type", "customdimensions:not_in_the_pack");
            stem.add("generator", JsonParser.parseString("{\"type\": \"minecraft:flat\", \"settings\": {\"biome\": "
                    + "\"minecraft:plains\", \"layers\": [], \"structure_overrides\": []}}"));
            Optional<String> error = LevelStem.CODEC.parse(withPack, stem).error().map(DataResult.Error::message);
            if (error.isEmpty()) throw new AssertionError("a level stem naming a missing dimension type decoded");
            return error.get();
        }));
        return lines;
    }

    private record PackFile(Identifier id, Path path) {}

    /** Every {@code data/<ns>/<folder>/**.json} in the pack, by id, in path order. */
    private static List<PackFile> files(Path pack, String folder) {
        List<PackFile> out = new ArrayList<>();
        try (Stream<Path> namespaces = Files.list(pack.resolve("data"))) {
            for (Path ns : namespaces.sorted().toList()) {
                Path root = ns.resolve(folder);
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path file : walk.filter(p -> p.toString().endsWith(".json")).sorted(Comparator.naturalOrder()).toList()) {
                        String path = root.relativize(file).toString().replace('\\', '/');
                        out.add(new PackFile(Identifier.parse(ns.getFileName() + ":" + path.substring(0, path.length() - 5)), file));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static <T> String decodeAll(String kind, List<PackFile> files, Codec<T> codec, RegistryOps<JsonElement> ops,
                                        BiConsumer<Identifier, T> register) {
        List<String> failures = new ArrayList<>();
        for (PackFile file : files) {
            try {
                DataResult<T> result = codec.parse(ops, JsonParser.parseString(Files.readString(file.path())));
                Optional<String> error = result.error().map(DataResult.Error::message);
                if (error.isPresent()) {
                    failures.add(file.id() + ": " + error.get());
                } else {
                    register.accept(file.id(), result.getOrThrow());
                }
            } catch (Throwable t) {
                failures.add(file.id() + ": " + t);
            }
        }
        int passed = files.size() - failures.size();
        String counts = passed + "/" + files.size();
        if (files.isEmpty()) {
            return "FAIL compiled " + kind + " decode: the pack has none";
        }
        return failures.isEmpty()
                ? "PASS compiled " + kind + " decode: " + counts
                : "FAIL compiled " + kind + " decode: " + counts + "; " + String.join(" | ", failures);
    }

    /** The server's registries, with the pack's own entries in front for the registries given. */
    private static RegistryOps.RegistryInfoLookup withPack(HolderLookup.Provider access,
                                                           Map<ResourceKey<? extends Registry<?>>, MappedRegistry<?>> pack) {
        return new RegistryOps.RegistryInfoLookup() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<HolderGetter<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                Optional<HolderGetter<T>> server = access.lookup(key).map(l -> (HolderGetter<T>) l);
                MappedRegistry<T> local = (MappedRegistry<T>) pack.get(key);
                if (local == null) return server;
                HolderGetter<T> fallback = server.orElse(null);
                return Optional.of(new HolderGetter<T>() {
                    @Override
                    public Optional<Holder.Reference<T>> get(ResourceKey<T> id) {
                        Optional<Holder.Reference<T>> own = local.get(id);
                        return own.isPresent() || fallback == null ? own : fallback.get(id);
                    }

                    @Override
                    public Optional<HolderSet.Named<T>> get(TagKey<T> tag) {
                        return fallback == null ? Optional.empty() : fallback.get(tag);
                    }
                });
            }
        };
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "");
        if (value.isEmpty()) {
            throw new IllegalStateException("-D" + name + " is not set");
        }
        return value;
    }

    @FunctionalInterface
    private interface Check {
        String run() throws Exception;
    }
}
