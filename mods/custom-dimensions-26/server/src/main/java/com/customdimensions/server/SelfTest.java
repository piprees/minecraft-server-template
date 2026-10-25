package com.customdimensions.server;

import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.VanillaData;
import com.customdimensions.compiler.datapack.VersionTable;
import com.customdimensions.compiler.zone.ZoneDensity;
import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.core.zone.ZoneShape;
import com.customdimensions.server.command.CommandSelfTest;
import com.customdimensions.server.exit.ExitSelfTest;
import com.customdimensions.server.level.PackInstaller;
import com.customdimensions.server.level.RuntimeVanilla;
import com.customdimensions.server.level.GeneratorSelfTest;
import com.customdimensions.server.level.MaterialsSelfTest;
import com.customdimensions.server.level.TerrainSelfTest;
import com.customdimensions.server.difficulty.DifficultySelfTest;
import com.customdimensions.server.level.LevelSelfTest;
import com.customdimensions.server.portal.PortalSelfTest;
import com.customdimensions.server.render.RenderSelfTest;
import com.customdimensions.server.companion.CompanionSelfTest;
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
import net.minecraft.advancements.Advancement;
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
 * fails the build. The pack-install and pack-equivalence lines come first,
 * then the compiled-pack checks; the runtime-level checks ({@link LevelSelfTest}),
 * the generator checks ({@link GeneratorSelfTest}), the materials, liquids and ores scans
 * ({@link MaterialsSelfTest}), the render checks ({@link RenderSelfTest}), the portal and exit checks,
 * {@link DifficultySelfTest} and {@link CommandSelfTest} run across ticks, so
 * the report is written when they finish. The compiled-pack checks read the
 * definitions from {@code -Dcustomdimensions.definitions} and vanilla's files
 * from the running game; the equivalence line also reads
 * {@code -Dcustomdimensions.vanilla.reference}.
 */
final class SelfTest {
    private static final String PROPERTY = "customdimensions.selftest";
    private static final String DEFINITIONS = "customdimensions.definitions";
    /** An extracted vanilla folder the runtime source is compared against; {@code -Dcustomdimensions.vanilla} also counts. */
    private static final String REFERENCE = "customdimensions.vanilla.reference";
    /** {@code exits}, {@code commands}, {@code materials}, {@code render}, {@code firewall} or {@code terrain} runs the compiled-pack section and that one only, for iterating on it. */
    private static final String ONLY = "customdimensions.selftest.only";

    private SelfTest() {}

    static void register() {
        String report = System.getProperty(PROPERTY, "");
        if (report.isEmpty()) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> run(server, Path.of(report)));
    }

    private static void run(MinecraftServer server, Path report) {
        List<String> lines = new ArrayList<>();
        lines.add(check("zone mask parses and samples like core", () -> zoneMask(server)));
        lines.add(check("pack installed at start-up by the mod", SelfTest::packInstall));
        lines.add(equivalence());
        lines.addAll(compiledPack(server));
        lines.addAll(com.customdimensions.server.firewall.FirewallSelfTest.run(server));
        String only = System.getProperty(ONLY, "");
        boolean materialsOnly = "materials".equals(only);
        boolean firewallOnly = "firewall".equals(only);
        boolean commandsOnly = "commands".equals(only);
        boolean renderOnly = "render".equals(only);
        boolean terrainOnly = "terrain".equals(only);
        boolean exitsOnly = "exits".equals(only) || commandsOnly || materialsOnly || renderOnly || firewallOnly || terrainOnly;
        CompletableFuture<List<String>> levels;
        try {
            levels = exitsOnly ? CompletableFuture.completedFuture(List.of()) : LevelSelfTest.run(server);
        } catch (Throwable t) {
            levels = CompletableFuture.completedFuture(List.of("FAIL runtime levels: " + t));
        }
        levels.handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL runtime levels: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return exitsOnly ? CompletableFuture.completedFuture(List.<String>of()) : GeneratorSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL generators: " + t));
            }
        }).handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL generators: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return exitsOnly && !terrainOnly ? CompletableFuture.completedFuture(List.<String>of()) : TerrainSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL terrain: " + t));
            }
        }).handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL terrain: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return exitsOnly && !materialsOnly ? CompletableFuture.completedFuture(List.<String>of()) : MaterialsSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL materials: " + t));
            }
        }).handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL materials: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return exitsOnly && !renderOnly ? CompletableFuture.completedFuture(List.<String>of()) : RenderSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL render: " + t));
            }
        }).handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL render: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return exitsOnly ? CompletableFuture.completedFuture(List.<String>of()) : PortalSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL portals: " + t));
            }
        }).handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL portals: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return exitsOnly ? CompletableFuture.completedFuture(List.<String>of()) : CompanionSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL companion: " + t));
            }
        }).handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL companion: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return commandsOnly || materialsOnly || renderOnly || firewallOnly || terrainOnly ? CompletableFuture.completedFuture(List.<String>of()) : ExitSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL exits: " + t));
            }
        }).handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL exits: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return exitsOnly ? CompletableFuture.completedFuture(List.<String>of()) : DifficultySelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL difficulty: " + t));
            }
        }).handle((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL difficulty: " + error));
            return null;
        }).thenCompose(v -> {
            try {
                return exitsOnly && !commandsOnly || firewallOnly ? CompletableFuture.completedFuture(List.<String>of()) : CommandSelfTest.run(server);
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(List.of("FAIL commands: " + t));
            }
        }).whenComplete((more, error) -> {
            lines.addAll(error == null ? more : List.of("FAIL commands: " + error));
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

    /** The pack in the world holds what the mod compiled at initialisation, from the source it names. */
    private static String packInstall() {
        PackInstaller.Result result = PackInstaller.last().orElseThrow(() -> new AssertionError("the installer did not run"));
        if (!java.nio.file.Files.isRegularFile(result.pack().resolve("pack.mcmeta"))) throw new AssertionError("no pack.mcmeta in " + result.pack());
        String hash = com.customdimensions.compiler.datapack.PackInputs.read(result.pack()).orElse("");
        if (!hash.equals(result.hash())) throw new AssertionError("pack hash " + hash + ", inputs " + result.hash());
        return (result.compiled() ? "compiled " + result.files() + " files from " + result.vanilla()
                : "current, " + result.files() + " files kept") + ", inputs " + hash.substring(0, 12)
                + ", " + result.drift().size() + " drift warnings";
    }

    /**
     * The shipped definitions compiled from the extracted vanilla folder and
     * from the running game give the same files, byte for byte, and the same report.
     */
    private static String equivalence() {
        String reference = System.getProperty(REFERENCE, System.getProperty(PackInstaller.VANILLA_PROPERTY, ""));
        if (reference.isEmpty()) {
            return "SKIP pack equivalence: no -D" + REFERENCE + " to compare the running game against";
        }
        return check("pack equivalence: extracted vanilla and the running game compile the same pack", () -> {
            Path definitions = Path.of(requiredProperty(DEFINITIONS));
            VersionTable.Version version = VersionTable.forId(PackInstaller.minecraftVersion());
            RuntimeVanilla runtime = RuntimeVanilla.load();
            DatapackCompiler.Output extracted = DatapackCompiler.compile(definitions, new VanillaData(Path.of(reference)), version);
            DatapackCompiler.Output live = DatapackCompiler.compile(definitions, new VanillaData(runtime), version);
            Path dir = Files.createTempDirectory("customdim-equivalence");
            DatapackCompiler.write(extracted, dir.resolve("extracted"), dir.resolve("extracted.md"));
            DatapackCompiler.write(live, dir.resolve("runtime"), dir.resolve("runtime.md"));
            List<String> a = tree(dir.resolve("extracted"));
            List<String> b = tree(dir.resolve("runtime"));
            if (!a.equals(b)) {
                List<String> onlyA = new ArrayList<>(a);
                onlyA.removeAll(b);
                List<String> onlyB = new ArrayList<>(b);
                onlyB.removeAll(a);
                throw new AssertionError("file sets differ: extracted only " + onlyA + ", runtime only " + onlyB);
            }
            for (String path : a) {
                if (!java.util.Arrays.equals(Files.readAllBytes(dir.resolve("extracted").resolve(path)),
                        Files.readAllBytes(dir.resolve("runtime").resolve(path)))) {
                    throw new AssertionError("first difference: " + path);
                }
            }
            if (!Files.readString(dir.resolve("extracted.md")).equals(Files.readString(dir.resolve("runtime.md")))) {
                throw new AssertionError("the compile reports differ");
            }
            for (String report : runtime.reportPaths()) {
                java.nio.file.Path file = Path.of(reference).resolve(report);
                if (!JsonParser.parseString(Files.readString(file)).equals(JsonParser.parseString(runtime.read(report).orElseThrow()))) {
                    throw new AssertionError(report + " differs from the data generator's report");
                }
            }
            return a.size() + " files identical; " + runtime.reportPaths().size() + " parameter lists equal the reports";
        });
    }

    private static List<String> tree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
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
        List<String> kinds = List.of("timelines", "dimension types", "noise settings", "level stems", "advancements");
        Path pack;
        try {
            Path definitions = Path.of(requiredProperty(DEFINITIONS));
            VanillaData vanilla = PackInstaller.vanilla();
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
        List<PackFile> advancements = files(pack, "advancement");
        lines.add(advancements.isEmpty() ? "PASS compiled advancements decode: the definitions have no wins"
                : decodeAll("advancements", advancements, Advancement.CODEC, withPack, (id, v) -> {
                    if (server.getAdvancements().get(id) == null) {
                        throw new IllegalStateException(id + " decodes but the server did not load it");
                    }
                }));
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
