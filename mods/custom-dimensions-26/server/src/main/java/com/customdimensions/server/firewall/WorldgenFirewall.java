package com.customdimensions.server.firewall;

import com.customdimensions.compiler.datapack.VanillaSource;
import com.customdimensions.core.firewall.AllowList;
import com.customdimensions.core.firewall.BiomeBaseline;
import com.customdimensions.core.firewall.Bridge;
import com.customdimensions.core.firewall.BridgeReader;
import com.customdimensions.core.firewall.Finding;
import com.customdimensions.core.firewall.Firewall;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.PackInstaller;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.biome.v1.BiomeModificationContext;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * The start-up audit (docs/design/rebuild-26/worldgen.md § Firewall and
 * bridges). At SERVER_STARTING, before any level exists, it reads the mods,
 * their mixin targets ({@link MixinTargets}) and the worldgen registries, applies {@link Firewall}
 * and either refuses to start or records which structure sets are switched
 * off. A POST_PROCESSING biome modification removes features and spawns that
 * other mods added to vanilla biomes without a bridge.
 */
public final class WorldgenFirewall {
    public static final String BRIDGES_PROPERTY = "customdimensions.bridges";

    /** What the audit found at start-up; {@code removals} is one line per removed feature or spawn with its biome count. */
    public record Report(Firewall.Verdict verdict, Set<String> switchedOff, List<String> removals, Set<String> bridges, Path bridgesDir) {}

    /** The refusal, thrown from SERVER_STARTING so no level is created. */
    public static final class Refusal extends RuntimeException {
        Refusal(String message) {
            super(message);
        }
    }

    private static volatile Set<ResourceKey<StructureSet>> switchedOff = Set.of();
    private static volatile Report report;
    private static Map<String, Bridge> bridges = Map.of();
    private static RuntimeException bridgeError;
    private static final Map<String, Set<String>> removed = new ConcurrentHashMap<>();
    private static final Map<String, Optional<BiomeBaseline>> baselines = new ConcurrentHashMap<>();
    private static VanillaSource vanilla;

    private WorldgenFirewall() {}

    public static void register() {
        Path dir = bridgesDir();
        try {
            bridges = BridgeReader.readAll(dir);
        } catch (RuntimeException e) {
            bridgeError = e;
        }
        BiomeModifications.create(Identifier.fromNamespaceAndPath(CustomDimensions.ID, "firewall"))
                .add(ModificationPhase.POST_PROCESSING, WorldgenFirewall::isVanillaBiome, WorldgenFirewall::strip);
        ServerLifecycleEvents.SERVER_STARTING.register(WorldgenFirewall::audit);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> switchedOff = Set.of());
    }

    public static Path bridgesDir() {
        String property = System.getProperty(BRIDGES_PROPERTY, "");
        return property.isEmpty() ? FabricLoader.getInstance().getGameDir().resolve(Firewall.BRIDGES_DIR) : Path.of(property);
    }

    public static Optional<Report> report() {
        return Optional.ofNullable(report);
    }

    /** Whether a structure set keeps placing; {@code StructureStateMixin} drops the others from every level's structure state. */
    public static boolean placed(ResourceKey<StructureSet> key) {
        return !switchedOff.contains(key);
    }

    /** Replaces the switched-off sets, for the self-test; returns the previous ones. */
    public static Set<ResourceKey<StructureSet>> switchOff(Set<ResourceKey<StructureSet>> keys) {
        Set<ResourceKey<StructureSet>> previous = switchedOff;
        switchedOff = Set.copyOf(keys);
        return previous;
    }

    private static void audit(MinecraftServer server) {
        if (bridgeError != null) refuse("custom-dimensions refuses to start: a bridge in " + bridgesDir() + " is malformed: " + bridgeError.getMessage());
        RegistryAccess access = server.registryAccess();
        Map<Finding.Kind, Set<String>> registries = new TreeMap<>();
        registries.put(Finding.Kind.DIMENSION, ids(access.lookupOrThrow(Registries.LEVEL_STEM)));
        registries.put(Finding.Kind.DIMENSION_TYPE, ids(access.lookupOrThrow(Registries.DIMENSION_TYPE)));
        registries.put(Finding.Kind.NOISE_SETTINGS, ids(access.lookupOrThrow(Registries.NOISE_SETTINGS)));
        registries.put(Finding.Kind.BIOME_SOURCE, ids(BuiltInRegistries.BIOME_SOURCE));
        registries.put(Finding.Kind.CHUNK_GENERATOR, ids(BuiltInRegistries.CHUNK_GENERATOR));
        Set<String> own = LevelIndex.definitionsDir().map(PackInstaller::namespace).map(Set::of).orElse(Set.of());
        Set<String> foreign = registries.values().stream().flatMap(Collection::stream).map(WorldgenFirewall::namespace)
                .filter(ns -> !AllowList.NAMESPACES.contains(ns) && !own.contains(ns)).collect(Collectors.toCollection(TreeSet::new));
        Set<String> mods = FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId()).collect(Collectors.toCollection(TreeSet::new));
        Firewall.Game game = new Firewall.Game(mods, MixinTargets.read(), registries, owners(foreign), own);
        Firewall.Verdict verdict = Firewall.evaluate(game, bridges);
        if (verdict.refused()) refuse(Firewall.message(verdict.refusals()));

        Registry<StructureSet> sets = access.lookupOrThrow(Registries.STRUCTURE_SET);
        Set<String> off = Firewall.switchedOff(ids(sets), bridges, own);
        Set<ResourceKey<StructureSet>> keys = new HashSet<>();
        off.forEach(id -> keys.add(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse(id))));
        switchedOff = Set.copyOf(keys);

        List<String> removals = new TreeMap<>(removed).entrySet().stream()
                .map(e -> e.getKey() + " from " + e.getValue().size() + " vanilla biome" + (e.getValue().size() == 1 ? "" : "s")).toList();
        report = new Report(verdict, off, removals, new TreeSet<>(bridges.keySet()), bridgesDir());
        log(report);
    }

    private static void log(Report r) {
        r.verdict().allowed().forEach(a -> CustomDimensions.LOG.info("firewall: worldgen mixins allowed: {}", a));
        r.verdict().unreviewed().forEach(m -> CustomDimensions.LOG.warn("firewall: bridge {} is not reviewed and adopts nothing", m));
        Map<String, List<String>> byNamespace = new TreeMap<>();
        r.switchedOff().forEach(id -> byNamespace.computeIfAbsent(namespace(id), k -> new ArrayList<>()).add(id.substring(id.indexOf(':') + 1)));
        CustomDimensions.LOG.info("firewall: {} structure sets switched off (no reviewed bridge), {} bridges", r.switchedOff().size(), r.bridges().size());
        byNamespace.forEach((ns, paths) -> CustomDimensions.LOG.info("firewall: switched off {} ({}): {}", ns, paths.size(), String.join(", ", paths)));
        r.removals().forEach(line -> CustomDimensions.LOG.info("firewall: removed {}", line));
    }

    private static void refuse(String message) {
        CustomDimensions.LOG.error(message);
        throw new Refusal(message);
    }

    /** The mods shipping data under each namespace, or named after it. */
    private static Map<String, Set<String>> owners(Set<String> namespaces) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (String ns : namespaces) {
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String id = mod.getMetadata().getId();
                if (id.equals(ns) || mod.findPath("data/" + ns).isPresent()) out.computeIfAbsent(ns, k -> new TreeSet<>()).add(id);
            }
        }
        return out;
    }

    private static Set<String> ids(Registry<?> registry) {
        return registry.keySet().stream().map(Identifier::toString).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String namespace(String id) {
        return id.substring(0, Math.max(0, id.indexOf(':')));
    }

    private static boolean isVanillaBiome(BiomeSelectionContext context) {
        return baseline(context.getBiomeKey().identifier().toString()).isPresent();
    }

    private static Optional<BiomeBaseline> baseline(String id) {
        if (!id.startsWith("minecraft:")) return Optional.empty();
        return baselines.computeIfAbsent(id, key -> {
            if (vanilla == null) vanilla = PackInstaller.vanilla().source();
            return vanilla.read("data/minecraft/worldgen/biome/" + key.substring("minecraft:".length()) + ".json").map(BiomeBaseline::parse);
        });
    }

    private static void strip(BiomeSelectionContext selection, BiomeModificationContext context) {
        String biome = selection.getBiomeKey().identifier().toString();
        BiomeBaseline base = baseline(biome).orElseThrow();
        Set<String> adoptedFeatures = adopted(Bridge::features);
        Set<String> adoptedSpawns = adopted(Bridge::spawns);
        GenerationStep.Decoration[] steps = GenerationStep.Decoration.values();
        List<List<String>> current = new ArrayList<>();
        for (GenerationStep.Decoration step : steps) {
            current.add(context.getGenerationSettings().getFeatures(step).stream().map(Holder::unwrapKey).flatMap(Optional::stream)
                    .map(k -> k.identifier().toString()).toList());
        }
        for (BiomeBaseline.Addition addition : base.featureAdditions(current, adoptedFeatures)) {
            GenerationStep.Decoration step = steps[addition.step()];
            ResourceKey<PlacedFeature> key = ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(addition.id()));
            if (context.getGenerationSettings().removeFeature(step, key)) {
                removed.computeIfAbsent("placed feature " + addition.id() + " (" + step.getSerializedName() + ")", k -> ConcurrentHashMap.newKeySet()).add(biome);
            }
        }
        context.getMobSpawnSettings().removeSpawns((category, data) -> {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(data.type()).toString();
            if (!base.spawnAdded(category.getSerializedName(), type, adoptedSpawns)) return false;
            removed.computeIfAbsent("spawn " + type + " (" + category.getSerializedName() + ")", k -> ConcurrentHashMap.newKeySet()).add(biome);
            return true;
        });
    }

    static Set<String> adoptedFeatures() {
        return adopted(Bridge::features);
    }

    /** The firewall section of {@code /customdim audit}. */
    public static String markdown() {
        StringBuilder out = new StringBuilder("\n## Firewall\n\n");
        Report r = report;
        if (r == null) return out.append("The start-up audit has not run.\n").toString();
        out.append("Bridges in `").append(r.bridgesDir()).append("`: ").append(r.bridges().isEmpty() ? "none" : String.join(", ", r.bridges())).append(".\n");
        list(out, "Mods allowed to mix into worldgen classes", List.copyOf(r.verdict().allowed()));
        list(out, "Bridges not reviewed (adopt nothing)", r.verdict().unreviewed());
        list(out, "Structure sets switched off", List.copyOf(r.switchedOff()));
        list(out, "Removed from vanilla biomes", r.removals());
        return out.toString();
    }

    private static void list(StringBuilder out, String title, List<String> items) {
        out.append("\n### ").append(title).append(" (").append(items.size()).append(")\n\n");
        items.forEach(i -> out.append("- ").append(i).append('\n'));
    }

    private static Set<String> adopted(java.util.function.Function<Bridge, List<String>> field) {
        Set<String> out = new HashSet<>();
        bridges.values().stream().filter(Bridge::reviewed).forEach(b -> out.addAll(field.apply(b)));
        return out;
    }
}
