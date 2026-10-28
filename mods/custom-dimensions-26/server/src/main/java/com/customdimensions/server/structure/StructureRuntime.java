package com.customdimensions.server.structure;

import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.StructuresCompiler;
import com.customdimensions.core.dimension.NoiseFieldIndex;
import com.customdimensions.core.dimension.NoiseProfile;
import com.customdimensions.core.dimension.StructureGroupRegistry;
import com.customdimensions.core.dimension.StructureNoise;
import com.customdimensions.core.dimension.StructureThemes;
import com.customdimensions.core.structure.StructureSites;
import com.customdimensions.core.structure.StructureSpec;
import com.customdimensions.core.structure.ZoneStructureRules;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the pack's {@code structure_control/} files to each level's
 * structure state as its chunk map builds it: sets filtered by mode, exclude
 * and shuns, structures replaced, spacing overrides, density on vanilla grids
 * or noise-group placement, then forced starts and wants as one-chunk sets.
 * A forced or wanted structure skips the biome check in its own chunk only
 * (T53); an included set
 * skips it wherever its own grid places it.
 */
public final class StructureRuntime {

    /** A forced start or a want as placed: its chunk, and whether its biome is there. */
    public record Placed(String kind, String structure, int chunkX, int chunkZ, boolean matched) {}

    /** What one level's control did, for the self-test and the log. */
    public record Applied(String level, int kept, int dropped, List<String> droppedSets, int replaced,
                          Map<String, Integer> groupSites, List<Placed> placed, long millis) {}

    private static volatile Map<String, StructureSpec> specs = Map.of();
    private static volatile Map<String, ZoneStructureRules> zoneRules = Map.of();
    /** The zone query for a level id, or null; the zones work package provides it. */
    private static volatile java.util.function.Function<String, ZoneStructureRules.ZoneQuery> zoneQueries = level -> null;
    private static final Map<String, Applied> APPLIED = new ConcurrentHashMap<>();
    private static final Map<String, Map<Long, Set<Structure>>> BYPASS = new ConcurrentHashMap<>();
    private static final Map<String, Set<Structure>> BYPASS_EVERYWHERE = new ConcurrentHashMap<>();
    private static final Map<String, Map<Structure, TerrainAdjustment>> ADAPTATION = new ConcurrentHashMap<>();

    private StructureRuntime() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> specs = load(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            APPLIED.clear();
            BYPASS.clear();
            BYPASS_EVERYWHERE.clear();
            ADAPTATION.clear();
        });
    }

    public static Optional<StructureSpec> spec(String level) {
        return Optional.ofNullable(specs.get(level));
    }

    public static Optional<Applied> applied(String level) {
        return Optional.ofNullable(APPLIED.get(level));
    }

    /** Sets the zone query per level id; until then zone-scoped wants and shuns are inert. */
    public static void zoneQueries(java.util.function.Function<String, ZoneStructureRules.ZoneQuery> provider) {
        zoneQueries = provider;
    }

    /** Whether a zone of this level shuns {@code structure} at the chunk's centre. */
    public static boolean zoneShunned(ResourceKey<Level> level, Holder<Structure> structure, ChunkPos chunk) {
        String id = level.identifier().toString();
        ZoneStructureRules rules = zoneRules.get(id);
        if (rules == null) return false;
        ZoneStructureRules.ZoneQuery query = zoneQueries.apply(id);
        return query != null && rules.shunned(name -> matches(structure, name), chunk.getMiddleBlockX(), chunk.getMiddleBlockZ(), query);
    }

    private static boolean matches(Holder<Structure> structure, String idOrTag) {
        return idOrTag.startsWith("#")
                ? structure.is(TagKey.create(Registries.STRUCTURE, Identifier.parse(idOrTag.substring(1))))
                : structure.unwrapKey().map(k -> k.identifier().toString().equals(idOrTag)).orElse(false);
    }

    /** Whether {@code structure} may ignore its biomes at this chunk of this level. */
    public static boolean bypassesBiome(ResourceKey<Level> level, Structure structure, ChunkPos chunk) {
        String id = level.identifier().toString();
        Set<Structure> everywhere = BYPASS_EVERYWHERE.get(id);
        if (everywhere != null && everywhere.contains(structure)) {
            return true;
        }
        Map<Long, Set<Structure>> sites = BYPASS.get(id);
        if (sites == null) {
            return false;
        }
        Set<Structure> here = sites.get(chunk.pack());
        return here != null && here.contains(structure);
    }

    /** The structure's terrain adjustment in this level: the control's, else its own. */
    public static TerrainAdjustment adaptation(ResourceKey<Level> level, Structure structure) {
        Map<Structure, TerrainAdjustment> overrides = level == null ? null : ADAPTATION.get(level.identifier().toString());
        if (overrides != null) {
            TerrainAdjustment t = overrides.get(structure);
            if (t != null) {
                return t;
            }
        }
        return structure.terrainAdaptation();
    }

    static Map<String, StructureSpec> load(MinecraftServer server) {
        Map<String, StructureSpec> out = new HashMap<>();
        Map<String, ZoneStructureRules> zones = new HashMap<>();
        Map<Identifier, Resource> files = server.getResourceManager().listResources(StructuresCompiler.FOLDER,
                id -> id.getNamespace().equals(DatapackCompiler.RUNTIME_NAMESPACE) && id.getPath().endsWith(".json"));
        for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
            try (Reader reader = file.getValue().openAsReader()) {
                com.google.gson.JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                StructureSpec spec = StructureSpec.fromJson(json);
                out.put(spec.level(), spec);
                if (json.has("zones")) {
                    zones.put(spec.level(), ZoneStructureRules.fromJson(json.getAsJsonArray("zones")));
                }
            } catch (Exception e) {
                CustomDimensions.LOG.warn("structure control file {} is unreadable: {}", file.getKey(), e.toString());
            }
        }
        zoneRules = Map.copyOf(zones);
        return Map.copyOf(out);
    }

    /**
     * A structure state with a level's control applied, and the biome bypass
     * and adaptation that go with it; {@code applied} is null when the pack has
     * no control for the level.
     */
    public record Controlled(ChunkGeneratorStructureState state, Map<Long, Set<Structure>> bypass, Set<Structure> everywhere,
                             Map<Structure, TerrainAdjustment> adaptation, Applied applied, String summary) {
        /** Whether {@code structure} may ignore its biomes at {@code chunk} under this control. */
        public boolean bypasses(Structure structure, ChunkPos chunk) {
            if (everywhere.contains(structure)) return true;
            Set<Structure> here = bypass.get(chunk.pack());
            return here != null && here.contains(structure);
        }
    }

    /** The level's structure state with its control applied and recorded for the level; the state itself when the pack has none for it. */
    public static ChunkGeneratorStructureState apply(ResourceKey<Level> levelKey, net.minecraft.core.RegistryAccess registries,
                                                     ChunkGenerator generator, ChunkGeneratorStructureState original) {
        Controlled c = control(levelKey, registries, generator, original);
        if (c.applied() == null) {
            return original;
        }
        String level = c.applied().level();
        BYPASS.put(level, c.bypass());
        BYPASS_EVERYWHERE.put(level, c.everywhere());
        ADAPTATION.put(level, c.adaptation());
        APPLIED.put(level, c.applied());
        CustomDimensions.LOG.info("structure control applied to {}: {}", level, c.summary());
        return c.state();
    }

    /**
     * The level's control applied to {@code original}, recording nothing: the
     * roller reads a seed's starts through it without touching the live level's.
     */
    public static Controlled control(ResourceKey<Level> levelKey, net.minecraft.core.RegistryAccess registries,
                                     ChunkGenerator generator, ChunkGeneratorStructureState original) {
        String level = levelKey.identifier().toString();
        StructureSpec spec = specs.get(level);
        if (spec == null) {
            return new Controlled(original, Map.of(), Set.of(), Map.of(), null, "");
        }
        long started = System.nanoTime();
        Registry<Structure> structures = registries.lookupOrThrow(Registries.STRUCTURE);
        Registry<StructureSet> setRegistry = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        BiomeSource biomes = generator.getBiomeSource();
        BiomeResolver sampler = biomes.createUncachedResolver(original.randomState());
        int seaLevel = generator.getSeaLevel();

        Set<Structure> shunned = resolveAll(structures, spec.shuns(), level, "shuns");
        Set<Structure> exclusive = new HashSet<>();
        for (StructureSpec.Force f : spec.force()) {
            if (f.exclusive()) {
                structure(structures, f.structure()).ifPresent(h -> exclusive.add(h.value()));
            }
        }
        Map<Structure, Holder<Structure>> replace = new IdentityHashMap<>();
        spec.replace().forEach((from, to) -> {
            Optional<Holder.Reference<Structure>> target = structure(structures, to);
            Optional<Holder.Reference<Structure>> source = structure(structures, from);
            if (target.isPresent() && source.isPresent()) {
                replace.put(source.get().value(), target.get());
            } else {
                CustomDimensions.LOG.warn("{}: structures.replace {} -> {} names a structure the registry lacks", level, from, to);
            }
        });

        List<Holder<StructureSet>> input = new ArrayList<>(original.possibleStructureSets());
        Set<String> present = new HashSet<>();
        input.forEach(h -> h.unwrapKey().ifPresent(k -> present.add(k.identifier().toString())));
        Set<Structure> included = new HashSet<>();
        for (String id : spec.include()) {
            if (present.contains(id)) {
                continue;
            }
            Optional<Holder.Reference<StructureSet>> set = setRegistry.get(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse(id)));
            if (set.isPresent() && com.customdimensions.server.firewall.WorldgenFirewall.placed(set.get().key())) {
                input.add(set.get());
                set.get().value().structures().forEach(e -> included.add(e.structure().value()));
            } else {
                CustomDimensions.LOG.warn("{}: structures.include {} is not a placed structure set", level, id);
            }
        }

        boolean organic = !"none".equals(spec.density()) && !"none".equals(spec.mode());
        Set<String> list = Set.copyOf(spec.list());
        Set<String> exclude = Set.copyOf(spec.exclude());
        List<Holder<StructureSet>> out = new ArrayList<>();
        Map<String, List<StructureSet.StructureSelectionEntry>> pools = new LinkedHashMap<>();
        Map<Structure, String> groupOf = new IdentityHashMap<>();
        int dropped = 0;
        int replaced = 0;
        List<String> droppedIds = new ArrayList<>();
        for (Holder<StructureSet> holder : input) {
            String id = holder.unwrapKey().map(k -> k.identifier().toString()).orElse(null);
            StructureSet set = holder.value();
            if (!organic || !kept(id, spec.mode(), list, exclude)) {
                dropped++;
                droppedIds.add(String.valueOf(id));
                continue;
            }
            List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>();
            boolean changed = false;
            for (StructureSet.StructureSelectionEntry e : set.structures()) {
                Structure s = e.structure().value();
                if (shunned.contains(s) || exclusive.contains(s)) {
                    changed = true;
                    continue;
                }
                Holder<Structure> swap = replace.get(s);
                if (swap != null) {
                    entries.add(new StructureSet.StructureSelectionEntry(swap, e.weight()));
                    changed = true;
                    replaced++;
                } else {
                    entries.add(e);
                }
            }
            if (entries.isEmpty()) {
                dropped++;
                droppedIds.add(String.valueOf(id));
                continue;
            }
            int spacing = set.placement() instanceof RandomSpreadStructurePlacement r ? r.spacing() : -1;
            StructureGroupRegistry.Entry cls = StructureGroupRegistry.classify(id, spacing);
            for (StructureSet.StructureSelectionEntry e : entries) {
                groupOf.putIfAbsent(e.structure().value(), cls.group());
            }
            StructureSpec.Spacing override = id == null ? null : spec.spacing().get(id);
            if (override != null) {
                if (set.placement() instanceof RandomSpreadStructurePlacement r) {
                    out.add(Holder.direct(new StructureSet(entries, StructureReflection.respaced(r, override.spacing(),
                            override.separation(), StructureReflection.frequency(r)))));
                } else {
                    CustomDimensions.LOG.warn("{}: structures.spacing {} is not a random-spread set; kept as it is", level, id);
                    out.add(changed ? Holder.direct(new StructureSet(entries, set.placement())) : holder);
                }
                continue;
            }
            boolean pooled = spec.noise() && set.placement().getClass() == RandomSpreadStructurePlacement.class
                    && !included.contains(entries.getFirst().structure().value());
            if (pooled) {
                StructureSpec.Group group = spec.groups().get(cls.group());
                if (group == null) {
                    out.add(changed ? Holder.direct(new StructureSet(entries, set.placement())) : holder);
                    continue;
                }
                String rarity = spec.rarity().getOrDefault(id, cls.rarity());
                double share = StructureGroupRegistry.rarityShare(rarity);
                List<StructureSet.StructureSelectionEntry> pool = pools.computeIfAbsent(cls.group(), g -> new ArrayList<>());
                for (StructureSet.StructureSelectionEntry e : entries) {
                    if (hasBiomeIn(e.structure().value(), biomes)) {
                        pool.add(new StructureSet.StructureSelectionEntry(e.structure(),
                                Math.max(1, (int) Math.round(e.weight() * share * 10))));
                    }
                }
                continue;
            }
            if (!spec.noise() && set.placement() instanceof RandomSpreadStructurePlacement r) {
                float[] f = densityFactors(spec.density(), StructureThemes.themeOf(id));
                if (f[0] != 1f || f[1] != 1f) {
                    int sp = Math.max(2, Math.round(r.spacing() * f[0]));
                    int sep = Math.min(sp - 1, Math.max(0, Math.round(r.separation() * f[0])));
                    out.add(Holder.direct(new StructureSet(entries, StructureReflection.respaced(r, sp, sep,
                            Math.min(1f, StructureReflection.frequency(r) * f[1])))));
                    continue;
                }
            }
            out.add(changed ? Holder.direct(new StructureSet(entries, set.placement())) : holder);
        }

        long pooledAt = System.nanoTime();
        Map<String, Integer> groupSites = new LinkedHashMap<>();
        List<Map.Entry<String, List<StructureSet.StructureSelectionEntry>>> filled = pools.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty()).toList();
        List<NoiseFieldIndex> indexes = filled.parallelStream().map(pool -> index(original.getLevelSeed(), level, pool.getKey(),
                spec.groups().get(pool.getKey()), spec,
                (int) pool.getValue().stream().map(e -> e.structure().value()).distinct().count())).toList();
        for (int i = 0; i < filled.size(); i++) {
            String group = filled.get(i).getKey();
            groupSites.put(group, indexes.get(i).size());
            out.add(Holder.direct(new StructureSet(filled.get(i).getValue(), new StructurePlacements.Noise(group, indexes.get(i)))));
        }

        long indexedAt = System.nanoTime();
        List<Placed> placed = new ArrayList<>();
        Map<Long, Set<Structure>> bypass = new HashMap<>();
        for (StructureSpec.Force f : spec.force()) {
            Optional<Holder.Reference<Structure>> s = structure(structures, f.structure());
            if (s.isEmpty()) {
                CustomDimensions.LOG.warn("{}: forced structure {} is not in the registry", level, f.structure());
                continue;
            }
            StructureSites.Site site = StructureSites.choose(StructureSites.seed(original.getLevelSeed(), "force:" + f.structure()),
                    f.x(), f.z(), 0, f.radius(), (cx, cz) -> biomeAt(s.get().value(), biomes, sampler, cx, cz, seaLevel));
            fixed(out, bypass, placed, "force", f.structure(), s.get(), site);
        }
        for (StructureSpec.Want w : spec.wants()) {
            List<Holder<Structure>> candidates = new ArrayList<>(members(structures, w.structure()));
            candidates.removeIf(h -> shunned.contains(h.value()));
            if (candidates.isEmpty()) {
                CustomDimensions.LOG.warn("{}: wanted structure {} is not in the registry", level, w.structure());
                continue;
            }
            Holder<Structure> pick = candidates.stream().filter(h -> hasBiomeIn(h.value(), biomes)).findFirst().orElse(candidates.getFirst());
            ZoneStructureRules rules = zoneRules.getOrDefault(level, ZoneStructureRules.NONE);
            ZoneStructureRules.ZoneQuery query = zoneQueries.apply(level);
            StructureSites.Site site = StructureSites.choose(StructureSites.seed(original.getLevelSeed(), "want:" + w.name()),
                    spec.spawnChunkX() * 16 + 8, spec.spawnChunkZ() * 16 + 8, w.min(), w.max(),
                    (cx, cz) -> (query == null || rules.wantAccepts(name -> matches(pick, name), cx * 16 + 8, cz * 16 + 8, query))
                            && biomeAt(pick.value(), biomes, sampler, cx, cz, seaLevel));
            String id = pick.unwrapKey().map(k -> k.identifier().toString()).orElse(w.structure());
            fixed(out, bypass, placed, "want", id, pick, site);
        }

        Map<Structure, TerrainAdjustment> adaptation = new IdentityHashMap<>();
        spec.terrainAdaptation().forEach((target, kind) -> {
            TerrainAdjustment t = TerrainAdjustment.valueOf(kind.toUpperCase(Locale.ROOT));
            if (target.contains(":")) {
                members(structures, target).forEach(h -> adaptation.put(h.value(), t));
            } else {
                groupOf.forEach((s, g) -> {
                    if (g.equals(target)) {
                        adaptation.putIfAbsent(s, t);
                    }
                });
            }
        });
        long millis = (System.nanoTime() - started) / 1_000_000;
        Applied applied = new Applied(level, out.size(), dropped, List.copyOf(droppedIds), replaced, groupSites, List.copyOf(placed), millis);
        String summary = String.format(Locale.ROOT, "density %s, %d set(s) kept, %d dropped %s, %d replaced, groups %s, %d fixed start(s), "
                        + "%d adaptation override(s), %d ms (sets %d ms, noise sites %d ms)", spec.density(), out.size(), dropped, droppedIds,
                replaced, groupSites, placed.size(), adaptation.size(), millis, (pooledAt - started) / 1_000_000, (indexedAt - pooledAt) / 1_000_000);
        return new Controlled(StructureReflection.withSets(original, out), Map.copyOf(bypass), Set.copyOf(included), adaptation, applied, summary);
    }

    private static void fixed(List<Holder<StructureSet>> out, Map<Long, Set<Structure>> bypass, List<Placed> placed,
                              String kind, String id, Holder<Structure> structure, StructureSites.Site site) {
        out.add(Holder.direct(new StructureSet(structure, new StructurePlacements.Fixed(site.chunk().x(), site.chunk().z()))));
        bypass.computeIfAbsent(ChunkPos.pack(site.chunk().x(), site.chunk().z()), k -> new HashSet<>()).add(structure.value());
        placed.add(new Placed(kind, id, site.chunk().x(), site.chunk().z(), site.matched()));
    }

    /** The group's index, its exclusion widened until the sites stay within the pool's repetition budget. */
    static NoiseFieldIndex index(long levelSeed, String level, String groupName, StructureSpec.Group group,
                                 StructureSpec spec, int distinct) {
        long seed = levelSeed ^ StructureNoise.saltOf(level) ^ StructureNoise.saltOf(groupName);
        NoiseProfile profile = NoiseProfile.fromString(group.profile());
        NoiseFieldIndex index = new NoiseFieldIndex(seed, profile, group.exclusion(), group.radial(), spec.radiusChunks(),
                spec.spawnChunkX(), spec.spawnChunkZ(), group.clearSpawnChunks());
        int target = distinct * profile.repetitionBudget();
        int exclusion = group.exclusion();
        for (int pass = 0; target > 0 && pass < 4 && index.size() > target; pass++) {
            int widened = (int) Math.ceil(exclusion * Math.sqrt(index.size() / (double) target));
            if (widened <= exclusion) {
                break;
            }
            exclusion = widened;
            index = new NoiseFieldIndex(seed, profile, exclusion, group.radial(), spec.radiusChunks(),
                    spec.spawnChunkX(), spec.spawnChunkZ(), group.clearSpawnChunks());
        }
        return index;
    }

    /** Spacing and frequency factors by theme for density on vanilla grids. */
    static float[] densityFactors(String density, String theme) {
        float spacing = 1f;
        float frequency = 1f;
        if ("dense".equals(density)) {
            if ("dungeon".equals(theme) || "loot".equals(theme)) {
                spacing = 0.7f;
            } else if ("landmark".equals(theme) || "maritime".equals(theme)) {
                spacing = 0.85f;
            }
        } else if ("sparse".equals(density)) {
            if ("dungeon".equals(theme) || "loot".equals(theme) || "landmark".equals(theme)) {
                frequency = 0.5f;
            } else if ("settlement".equals(theme) || "maritime".equals(theme)) {
                frequency = 0.7f;
            }
        }
        return new float[] {spacing, frequency};
    }

    static boolean kept(String id, String mode, Set<String> list, Set<String> exclude) {
        if (id != null && exclude.contains(id)) {
            return false;
        }
        if ("allow".equals(mode)) {
            return id != null && list.contains(id);
        }
        if ("reject".equals(mode)) {
            return id == null || !list.contains(id);
        }
        return true;
    }

    private static boolean hasBiomeIn(Structure structure, BiomeSource biomes) {
        HolderSet<net.minecraft.world.level.biome.Biome> allowed = structure.biomes();
        return biomes.possibleBiomes().stream().anyMatch(allowed::contains);
    }

    /** The structure's biome at the chunk's centre, near sea level. */
    private static boolean biomeAt(Structure structure, BiomeSource biomes, BiomeResolver sampler, int cx, int cz, int seaLevel) {
        int qx = QuartPos.fromBlock(cx * 16 + 8);
        int qz = QuartPos.fromBlock(cz * 16 + 8);
        for (int dy : new int[] {0, 24, -24}) {
            if (structure.biomes().contains(sampler.getNoiseBiome(qx, QuartPos.fromBlock(seaLevel + dy), qz))) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Holder.Reference<Structure>> structure(Registry<Structure> registry, String id) {
        return registry.get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id)));
    }

    private static List<Holder<Structure>> members(Registry<Structure> registry, String idOrTag) {
        List<Holder<Structure>> out = new ArrayList<>();
        if (idOrTag.startsWith("#")) {
            registry.get(TagKey.create(Registries.STRUCTURE, Identifier.parse(idOrTag.substring(1))))
                    .ifPresent(set -> set.forEach(out::add));
        } else {
            structure(registry, idOrTag).ifPresent(out::add);
        }
        return out;
    }

    private static Set<Structure> resolveAll(Registry<Structure> registry, List<String> ids, String level, String field) {
        Set<Structure> out = new HashSet<>();
        for (String id : ids) {
            List<Holder<Structure>> m = members(registry, id);
            if (m.isEmpty()) {
                CustomDimensions.LOG.warn("{}: structures.{} {} matches no structure", level, field, id);
            }
            m.forEach(h -> out.add(h.value()));
        }
        return out;
    }
}
