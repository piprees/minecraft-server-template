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
 * skips it wherever its own grid places it. A forced start with a {@code y}
 * finds its ground at that height ({@link PinnedGround}).
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
    private static final Map<String, Map<Long, Map<Structure, Integer>>> GROUND = new ConcurrentHashMap<>();
    private static final Map<String, Map<Structure, String>> GROUPS = new ConcurrentHashMap<>();
    private static final Map<String, Map<Structure, com.customdimensions.core.structure.SiteFilter>> FILTERS = new ConcurrentHashMap<>();
    private static final Map<String, Map<Structure, Set<Long>>> CAPS = new ConcurrentHashMap<>();
    private static final Set<String> FILTER_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Map<Structure, String>> FOOTINGS = new ConcurrentHashMap<>();
    private static final Map<String, Map<Structure, List<com.customdimensions.core.structure.SetPieceSetting.Approach>>> APPROACHES = new ConcurrentHashMap<>();
    private static final Map<String, List<int[]>> TARGETS = new ConcurrentHashMap<>();
    private static final Map<String, int[]> SPAWNS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Integer>> LIVED = new ConcurrentHashMap<>();
    /** Starts refused at generation because their box floor fell outside their filter's height window, by level, structure and chunk. */
    private static final Set<String> BOX_REFUSED = ConcurrentHashMap.newKeySet();
    /** How far below a filter's minimum height a start's box floor may lie: foundations sit under the surface. */
    public static final int FLOOR_SLACK = 4;
    /** {@code structures.approaches} per level: structure id or #tag to its approaches. */
    private static volatile Map<String, Map<String, List<com.customdimensions.core.structure.SetPieceSetting.Approach>>> approachRules = Map.of();
    /** {@code structures.sites} per level: structure id or #tag to its filter. */
    private static volatile Map<String, Map<String, com.customdimensions.core.structure.SiteFilter>> siteFilters = Map.of();
    /** The set pieces each level's control file names. */
    private static volatile Map<String, List<SetPieceEntry>> setPieceEntries = Map.of();

    /** A set piece as its level's control file names it: its structure, display name, seat, size, and its set and group when it joins one. */
    public record SetPieceEntry(String structure, String name, String seat, int size, String set, String group, String rarity,
                                Integer repetition) {
        static SetPieceEntry fromJson(com.google.gson.JsonObject o) {
            return new SetPieceEntry(o.get("structure").getAsString(), o.get("name").getAsString(), o.get("seat").getAsString(),
                    o.get("size").getAsInt(), o.has("set") ? o.get("set").getAsString() : null,
                    o.has("group") ? o.get("group").getAsString() : null, o.has("rarity") ? o.get("rarity").getAsString() : null,
                    o.has("repetition") ? o.get("repetition").getAsInt() : null);
        }
    }

    private StructureRuntime() {
    }

    /** The set pieces a level's control names. */
    public static List<SetPieceEntry> setPieces(String level) {
        return setPieceEntries.getOrDefault(level, List.of());
    }

    /** Whether a structure set is one of the pack's set pieces ({@code <ns>:set_piece/...}). */
    static boolean isSetPieceSet(String id) {
        return id != null && id.substring(id.indexOf(':') + 1).startsWith(com.customdimensions.compiler.datapack.SetPiecesCompiler.FOLDER + "/");
    }

    /**
     * Whether a start of {@code structure} at {@code chunk} is refused by its
     * level's site filter or a set piece's repetition ceiling. A forced start's
     * or a want's own chunk was chosen with the filter and is never refused here.
     */
    public static String refusal(ResourceKey<Level> level, Structure structure, ChunkPos chunk, ChunkGenerator generator,
                                 net.minecraft.world.level.levelgen.RandomState random) {
        String id = level.identifier().toString();
        if (bypassesBiome(level, structure, chunk)) return null;
        Map<Structure, Set<Long>> caps = CAPS.get(id);
        Set<Long> allowed = caps == null ? null : caps.get(structure);
        if (allowed != null && !allowed.contains(chunk.pack())) return "its repetition ceiling is taken by the group's nearer sites";
        Map<Structure, com.customdimensions.core.structure.SiteFilter> filters = FILTERS.get(id);
        com.customdimensions.core.structure.SiteFilter f = filters == null ? null : filters.get(structure);
        if (f == null) return null;
        if (BOX_REFUSED.contains(id + " " + System.identityHashCode(structure) + " " + chunk.pack())) {
            return "its box floor fell outside its height window when it generated";
        }
        GeneratorTerrain terrain = GeneratorTerrain.of(generator, random, generator.getBiomeSource().createUncachedResolver(random));
        return f.refusal(terrain, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ());
    }

    /**
     * The floor a filtered start stands on under a roof: the height its site
     * filter measured at the chunk's centre, so a start that seats itself on
     * the ground or a heightmap stands on the floor rather than the roof. Null
     * without a filter, or where the column has no roof.
     */
    public static Integer roofedGround(ResourceKey<Level> level, Structure structure, ChunkPos chunk, ChunkGenerator generator,
                                       net.minecraft.world.level.levelgen.RandomState random) {
        Map<Structure, com.customdimensions.core.structure.SiteFilter> filters = FILTERS.get(level.identifier().toString());
        if (filters == null || !filters.containsKey(structure)) return null;
        GeneratorTerrain terrain = GeneratorTerrain.of(generator, random, generator.getBiomeSource().createUncachedResolver(random));
        int x = chunk.getMiddleBlockX();
        int z = chunk.getMiddleBlockZ();
        return terrain.roofed(x, z) ? terrain.roofedFloor(x, z) : null;
    }

    /**
     * Why a generated start is refused by its filter's height window: its box
     * floor lies above the maximum or more than {@value #FLOOR_SLACK} blocks
     * below the minimum (a forced start pinned to a {@code y} is never
     * refused here). The refusal is remembered, so the fast check answers
     * the same for that chunk afterwards.
     */
    public static String boxRefusal(ResourceKey<Level> level, Structure structure, ChunkPos chunk,
                                    net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        String id = level.identifier().toString();
        Map<Structure, com.customdimensions.core.structure.SiteFilter> filters = FILTERS.get(id);
        com.customdimensions.core.structure.SiteFilter f = filters == null ? null : filters.get(structure);
        if (f == null || f.minHeight() == null && f.maxHeight() == null) return null;
        String why = null;
        if (f.maxHeight() != null && box.minY() > f.maxHeight()) why = "its box floor " + box.minY() + " is above " + f.maxHeight();
        if (f.minHeight() != null && box.minY() < f.minHeight() - FLOOR_SLACK) why = "its box floor " + box.minY() + " is below " + f.minHeight();
        if (why != null) BOX_REFUSED.add(id + " " + System.identityHashCode(structure) + " " + chunk.pack());
        return why;
    }

    /** The group sites a set piece's repetition ceiling leaves it in this level, or null when it has none. */
    public static Set<Long> cap(String level, Structure structure) {
        Map<Structure, Set<Long>> caps = CAPS.get(level);
        return caps == null ? null : caps.get(structure);
    }

    /** Whether this is the first refusal by site of {@code structure} in {@code level}, so the log names each once. */
    public static boolean firstSiteRefusal(String level, String structure) {
        return FILTER_LOGGED.add(level + " " + structure);
    }

    /** Whether {@code structure} may stand in {@code biome}: a set piece naming no biomes stands in every one. */
    public static boolean biomeFits(Structure structure, Holder<net.minecraft.world.level.biome.Biome> biome) {
        return structure instanceof SetPieceStructure s && s.anyBiome() || structure.biomes().contains(biome);
    }

    /** The footing adaptation ({@code footing} or {@code terrace}) of {@code structure} in this level, or null. */
    public static String footing(ResourceKey<Level> level, Structure structure) {
        Map<Structure, String> m = FOOTINGS.get(level.identifier().toString());
        return m == null ? null : m.get(structure);
    }

    /** The approaches {@code structures.approaches} gives {@code structure} in this level. */
    public static List<com.customdimensions.core.structure.SetPieceSetting.Approach> approaches(ResourceKey<Level> level, Structure structure) {
        Map<Structure, List<com.customdimensions.core.structure.SetPieceSetting.Approach>> m = APPROACHES.get(level.identifier().toString());
        return m == null ? List.of() : m.getOrDefault(structure, List.of());
    }

    /** The level's spawn as its structure control placed round it, block x and z. */
    public static int[] spawn(ResourceKey<Level> level) {
        return SPAWNS.getOrDefault(level.identifier().toString(), new int[] {0, 0});
    }

    /** The nearest forced, wanted or lived-in site to ({@code x}, {@code z}) outside {@code box}, or null. */
    public static int[] nearestTarget(ResourceKey<Level> level, int x, int z, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        int[] best = null;
        double d = Double.MAX_VALUE;
        for (int[] t : TARGETS.getOrDefault(level.identifier().toString(), List.of())) {
            if (t[0] >= box.minX() - 16 && t[0] <= box.maxX() + 16 && t[1] >= box.minZ() - 16 && t[1] <= box.maxZ() + 16) continue;
            double e = Math.hypot(t[0] - x, t[1] - z);
            if (e < d) {
                d = e;
                best = t;
            }
        }
        return best;
    }

    /** Sites the lived-in layer drew per set piece in this level. */
    public static Map<String, Integer> lived(String level) {
        return LIVED.getOrDefault(level, Map.of());
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> specs = load(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            APPLIED.clear();
            BYPASS.clear();
            BYPASS_EVERYWHERE.clear();
            ADAPTATION.clear();
            GROUND.clear();
            GROUPS.clear();
            FILTERS.clear();
            CAPS.clear();
            FILTER_LOGGED.clear();
            FOOTINGS.clear();
            APPROACHES.clear();
            TARGETS.clear();
            SPAWNS.clear();
            LIVED.clear();
            BOX_REFUSED.clear();
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

    /** The height a forced start of {@code structure} finds its ground at in this chunk of this level, or null. */
    public static Integer forcedGround(ResourceKey<Level> level, Structure structure, ChunkPos chunk) {
        Map<Long, Map<Structure, Integer>> sites = GROUND.get(level.identifier().toString());
        Map<Structure, Integer> here = sites == null ? null : sites.get(chunk.pack());
        return here == null ? null : here.get(structure);
    }

    /** The noise group {@code structure} places in under this level's control, or null. */
    public static String groupOf(ResourceKey<Level> level, Structure structure) {
        Map<Structure, String> groups = GROUPS.get(level.identifier().toString());
        return groups == null ? null : groups.get(structure);
    }

    /** {@code generator}, or one that answers every ground query at {@code y} when a forced start gives one. */
    public static ChunkGenerator ground(ChunkGenerator generator, Integer y) {
        return y == null ? generator : new PinnedGround(generator, y);
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
        Map<String, Map<String, com.customdimensions.core.structure.SiteFilter>> sites = new HashMap<>();
        Map<String, List<SetPieceEntry>> pieces = new HashMap<>();
        Map<String, Map<String, List<com.customdimensions.core.structure.SetPieceSetting.Approach>>> approaches = new HashMap<>();
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
                if (json.has("sites")) {
                    Map<String, com.customdimensions.core.structure.SiteFilter> m = new LinkedHashMap<>();
                    json.getAsJsonObject("sites").entrySet().forEach(e -> m.put(e.getKey(),
                            com.customdimensions.core.structure.SiteFilter.fromJson(e.getValue().getAsJsonObject())));
                    sites.put(spec.level(), Map.copyOf(m));
                }
                if (json.has("setPieces")) {
                    List<SetPieceEntry> l = new ArrayList<>();
                    json.getAsJsonArray("setPieces").forEach(e -> l.add(SetPieceEntry.fromJson(e.getAsJsonObject())));
                    pieces.put(spec.level(), List.copyOf(l));
                }
                if (json.has("approaches")) {
                    Map<String, List<com.customdimensions.core.structure.SetPieceSetting.Approach>> m = new LinkedHashMap<>();
                    json.getAsJsonObject("approaches").entrySet().forEach(e -> m.put(e.getKey(),
                            com.customdimensions.core.structure.SetPieceSetting.Approach.readAll(e.getKey(), e.getValue())));
                    approaches.put(spec.level(), Map.copyOf(m));
                }
            } catch (Exception e) {
                CustomDimensions.LOG.warn("structure control file {} is unreadable: {}", file.getKey(), e.toString());
            }
        }
        zoneRules = Map.copyOf(zones);
        siteFilters = Map.copyOf(sites);
        setPieceEntries = Map.copyOf(pieces);
        approachRules = Map.copyOf(approaches);
        return Map.copyOf(out);
    }

    /**
     * A structure state with a level's control applied, and the biome bypass
     * and adaptation that go with it; {@code applied} is null when the pack has
     * no control for the level.
     */
    public record Controlled(ChunkGeneratorStructureState state, Map<Long, Set<Structure>> bypass, Set<Structure> everywhere,
                             Map<Structure, TerrainAdjustment> adaptation, Map<Long, Map<Structure, Integer>> ground,
                             Map<Structure, String> groups, Applied applied, String summary,
                             Map<Structure, com.customdimensions.core.structure.SiteFilter> filters, Map<Structure, Set<Long>> caps) {
        /** No control: the state as it is. */
        public static Controlled none(ChunkGeneratorStructureState state) {
            return new Controlled(state, Map.of(), Set.of(), Map.of(), Map.of(), Map.of(), null, "", Map.of(), Map.of());
        }

        /** Whether {@code structure} may ignore its biomes at {@code chunk} under this control. */
        public boolean bypasses(Structure structure, ChunkPos chunk) {
            if (everywhere.contains(structure)) return true;
            Set<Structure> here = bypass.get(chunk.pack());
            return here != null && here.contains(structure);
        }

        /** The height a forced start of {@code structure} finds its ground at in {@code chunk}, or null. */
        public Integer groundAt(Structure structure, ChunkPos chunk) {
            Map<Structure, Integer> here = ground.get(chunk.pack());
            return here == null ? null : here.get(structure);
        }
    }

    /** A place the level's plan names: a forced start, a want, or the spawn; {@code x}, {@code z} in blocks. */
    public record Planned(String kind, String structure, int x, int z, boolean registered) {}

    /**
     * What the level's structure plan places, whether or not the structure
     * starts there: each forced start at its chosen chunk's centre (at its
     * definition's x, z when the registry lacks the structure), each want at
     * its chunk's centre, and the spawn. Empty when the pack has no control
     * for the level.
     */
    public static List<Planned> planned(ResourceKey<Level> levelKey, Controlled c) {
        StructureSpec spec = levelKey == null ? null : specs.get(levelKey.identifier().toString());
        if (spec == null) return List.of();
        return planned(spec, c == null || c.applied() == null ? List.of() : c.applied().placed());
    }

    /** As above, from the spec and what its control placed. */
    public static List<Planned> planned(StructureSpec spec, List<Placed> placed) {
        List<Planned> out = new ArrayList<>();
        List<Placed> left = new ArrayList<>(placed);
        for (StructureSpec.Force f : spec.force()) {
            Placed p = left.stream().filter(q -> q.kind().equals("force") && q.structure().equals(f.structure())).findFirst().orElse(null);
            if (p != null) {
                left.remove(p);
                out.add(new Planned("force", f.structure(), p.chunkX() * 16 + 8, p.chunkZ() * 16 + 8, true));
            } else {
                out.add(new Planned("force", f.structure(), f.x(), f.z(), false));
            }
        }
        for (Placed p : left) {
            if (p.kind().equals("want")) out.add(new Planned("want", p.structure(), p.chunkX() * 16 + 8, p.chunkZ() * 16 + 8, true));
        }
        out.add(new Planned("spawn", "spawn", spec.spawnChunkX() * 16 + 8, spec.spawnChunkZ() * 16 + 8, true));
        return out;
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
        GROUND.put(level, c.ground());
        GROUPS.put(level, c.groups());
        FILTERS.put(level, c.filters());
        CAPS.put(level, c.caps());
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
            return Controlled.none(original);
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

        Map<String, SetPieceEntry> ourSets = new HashMap<>();
        for (SetPieceEntry p : setPieceEntries.getOrDefault(level, List.of())) {
            if (p.set() != null) ourSets.put(p.set(), p);
        }
        input.removeIf(h -> h.unwrapKey().map(k -> k.identifier().toString()).filter(id -> isSetPieceSet(id) && !ourSets.containsKey(id))
                .isPresent());
        for (String id : ourSets.keySet()) {
            if (present.contains(id)) continue;
            setRegistry.get(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse(id))).ifPresentOrElse(input::add,
                    () -> CustomDimensions.LOG.warn("{}: set piece set {} is not in the registry", level, id));
        }
        GeneratorTerrain terrain = GeneratorTerrain.of(generator, original.randomState(), sampler);
        Map<Structure, com.customdimensions.core.structure.SiteFilter> filters = new IdentityHashMap<>();
        siteFilters.getOrDefault(level, Map.of()).forEach((target, f) -> members(structures, target).forEach(h -> filters.put(h.value(), f)));
        for (SetPieceEntry p : setPieceEntries.getOrDefault(level, List.of())) {
            structure(structures, p.structure()).ifPresent(h -> {
                if (h.value() instanceof SetPieceStructure piece && !piece.spec().site().isEmpty()) {
                    filters.putIfAbsent(h.value(), piece.spec().site());
                }
            });
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
            SetPieceEntry piece = id == null ? null : ourSets.get(id);
            if (piece != null) {
                cls = new StructureGroupRegistry.Entry(id, cls.theme(), piece.group(), piece.rarity() != null ? piece.rarity() : "common",
                        cls.source());
                if (!spec.noise() || !spec.groups().containsKey(piece.group())) {
                    CustomDimensions.LOG.warn("{}: set piece {} names group {}, which places nothing here; it is placed by force or wants only",
                            level, piece.structure(), piece.group());
                    dropped++;
                    droppedIds.add(id);
                    continue;
                }
            }
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
        Map<Structure, Set<Long>> caps = new IdentityHashMap<>();
        Map<String, Integer> groupSites = new LinkedHashMap<>();
        List<Map.Entry<String, List<StructureSet.StructureSelectionEntry>>> filled = pools.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty()).toList();
        List<NoiseFieldIndex> indexes = filled.parallelStream().map(pool -> index(original.getLevelSeed(), level, pool.getKey(),
                spec.groups().get(pool.getKey()), spec,
                (int) pool.getValue().stream().map(e -> e.structure().value()).distinct().count())).toList();
        for (int i = 0; i < filled.size(); i++) {
            String group = filled.get(i).getKey();
            NoiseFieldIndex index = indexes.get(i);
            List<Structure> pooled = filled.get(i).getValue().stream().map(e -> e.structure().value()).distinct().toList();
            java.util.function.BiPredicate<Integer, Integer> sited = pooled.stream().noneMatch(filters::containsKey) ? null
                    : (cx, cz) -> pooled.stream().anyMatch(s -> biomeAt(s, sampler, cx, cz, seaLevel) && passes(filters.get(s), terrain, cx, cz));
            List<com.customdimensions.core.ChunkPos> sites = settled(index, original.getLevelSeed(), group,
                    here -> pooled.stream().anyMatch(s -> biomeFits(s, here)), sampler, seaLevel, sited);
            for (SetPieceEntry p : ourSets.values()) {
                if (p.repetition() == null || !group.equals(p.group())) continue;
                structure(structures, p.structure()).filter(h -> pooled.contains(h.value())).ifPresent(h -> {
                    List<com.customdimensions.core.ChunkPos> order = new ArrayList<>(sites);
                    order.sort(java.util.Comparator.comparingDouble(c -> StructureSites.distance(c, spec.spawnChunkX() * 16 + 8,
                            spec.spawnChunkZ() * 16 + 8)));
                    Set<Long> allowed = new HashSet<>();
                    for (com.customdimensions.core.ChunkPos c : order) {
                        if (allowed.size() >= p.repetition()) break;
                        if (biomeAt(h.value(), sampler, c.x(), c.z(), seaLevel) && passes(filters.get(h.value()), terrain, c.x(), c.z())) {
                            allowed.add(ChunkPos.pack(c.x(), c.z()));
                        }
                    }
                    caps.put(h.value(), Set.copyOf(allowed));
                });
            }
            groupSites.put(group, sites.size());
            out.add(Holder.direct(new StructureSet(filled.get(i).getValue(), new StructurePlacements.Noise(group, index, sites))));
        }

        long indexedAt = System.nanoTime();
        List<Placed> placed = new ArrayList<>();
        Map<Long, Set<Structure>> bypass = new HashMap<>();
        Map<Long, Map<Structure, Integer>> ground = new HashMap<>();
        for (StructureSpec.Force f : spec.force()) {
            Optional<Holder.Reference<Structure>> s = structure(structures, f.structure());
            if (s.isEmpty()) {
                CustomDimensions.LOG.warn("{}: forced structure {} is not in the registry", level, f.structure());
                continue;
            }
            StructureSites.Site site = StructureSites.choosePlaced(StructureSites.seed(original.getLevelSeed(), "force:" + f.structure()),
                    f.x(), f.z(), 0, f.radius(), (cx, cz) -> startBiomeAt(s.get().value(), generator, original.randomState(), sampler,
                            cx, cz, seaLevel) && passes(filters.get(s.get().value()), terrain, cx, cz));
            fixed(out, bypass, placed, "force", f.structure(), s.get(), site);
            if (f.y() != null) {
                ground.computeIfAbsent(ChunkPos.pack(site.chunk().x(), site.chunk().z()), k -> new IdentityHashMap<>())
                        .put(s.get().value(), f.y());
            }
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
            StructureSites.Site site = StructureSites.choosePlaced(StructureSites.seed(original.getLevelSeed(), "want:" + w.name()),
                    spec.spawnChunkX() * 16 + 8, spec.spawnChunkZ() * 16 + 8, w.min(), w.max(),
                    (cx, cz) -> (query == null || rules.wantAccepts(name -> matches(pick, name), cx * 16 + 8, cz * 16 + 8, query))
                            && startBiomeAt(pick.value(), generator, original.randomState(), sampler, cx, cz, seaLevel)
                            && passes(filters.get(pick.value()), terrain, cx, cz));
            String id = pick.unwrapKey().map(k -> k.identifier().toString()).orElse(w.structure());
            fixed(out, bypass, placed, "want", id, pick, site);
        }

        int spawnX = spec.spawnChunkX() * 16 + 8;
        int spawnZ = spec.spawnChunkZ() * 16 + 8;
        List<int[]> targets = new ArrayList<>();
        for (Placed p : placed) targets.add(new int[] {p.chunkX() * 16 + 8, p.chunkZ() * 16 + 8});
        List<int[]> segments = com.customdimensions.core.structure.SetPieceSetting.Lived.segments(spawnX, spawnZ, targets);
        Map<String, Integer> livedSites = new LinkedHashMap<>();
        for (SetPieceEntry p : setPieceEntries.getOrDefault(level, List.of())) {
            Optional<Holder.Reference<Structure>> h = structure(structures, p.structure());
            if (h.isEmpty() || !(h.get().value() instanceof SetPieceStructure piece) || piece.spec().setting().lived() == null) continue;
            Holder.Reference<Structure> ref = h.get();
            List<int[]> chunks = piece.spec().setting().lived().sites(StructureSites.seed(original.getLevelSeed(), "lived:" + p.structure()),
                    spawnX, spawnZ, spec.radiusChunks() * 16, terrain, segments,
                    (cx, cz) -> startBiomeAt(ref.value(), generator, original.randomState(), sampler, cx, cz, seaLevel)
                            && passes(filters.get(ref.value()), terrain, cx, cz));
            out.add(Holder.direct(new StructureSet(List.of(new StructureSet.StructureSelectionEntry(ref, 1)),
                    new LivedPlacement(p.structure(), chunks))));
            livedSites.put(p.structure(), chunks.size());
            for (int[] c : chunks) targets.add(new int[] {c[0] * 16 + 8, c[1] * 16 + 8});
        }
        if (!livedSites.isEmpty()) CustomDimensions.LOG.info("{}: lived-in layer sites {}", level, livedSites);
        LIVED.put(level, Map.copyOf(livedSites));
        TARGETS.put(level, List.copyOf(targets));
        SPAWNS.put(level, new int[] {spawnX, spawnZ});

        Map<Structure, TerrainAdjustment> adaptation = new IdentityHashMap<>();
        Map<Structure, String> footings = new IdentityHashMap<>();
        for (SetPieceEntry p : setPieceEntries.getOrDefault(level, List.of())) {
            structure(structures, p.structure()).ifPresent(h -> {
                if (h.value() instanceof SetPieceStructure piece
                        && StructureSpec.FOOTING_ADAPTATIONS.contains(piece.spec().terrainAdaptation())) {
                    footings.put(h.value(), piece.spec().terrainAdaptation());
                }
            });
        }
        spec.terrainAdaptation().forEach((target, kind) -> {
            boolean ours = StructureSpec.FOOTING_ADAPTATIONS.contains(kind);
            TerrainAdjustment t = ours ? TerrainAdjustment.NONE : TerrainAdjustment.valueOf(kind.toUpperCase(Locale.ROOT));
            if (target.contains(":")) {
                members(structures, target).forEach(h -> {
                    adaptation.put(h.value(), t);
                    if (ours) footings.put(h.value(), kind);
                    else footings.remove(h.value());
                });
            } else {
                groupOf.forEach((s, g) -> {
                    if (g.equals(target)) {
                        adaptation.putIfAbsent(s, t);
                        if (ours) footings.putIfAbsent(s, kind);
                    }
                });
            }
        });
        FOOTINGS.put(level, footings);
        Map<Structure, List<com.customdimensions.core.structure.SetPieceSetting.Approach>> approachesHere = new IdentityHashMap<>();
        approachRules.getOrDefault(level, Map.of()).forEach((target, list) -> members(structures, target)
                .forEach(h -> approachesHere.put(h.value(), list)));
        APPROACHES.put(level, approachesHere);
        long millis = (System.nanoTime() - started) / 1_000_000;
        Applied applied = new Applied(level, out.size(), dropped, List.copyOf(droppedIds), replaced, groupSites, List.copyOf(placed), millis);
        String summary = String.format(Locale.ROOT, "density %s, %d set(s) kept, %d dropped %s, %d replaced, groups %s, %d fixed start(s), "
                        + "%d adaptation override(s), %d ms (sets %d ms, noise sites %d ms)", spec.density(), out.size(), dropped, droppedIds,
                replaced, groupSites, placed.size(), adaptation.size(), millis, (pooledAt - started) / 1_000_000, (indexedAt - pooledAt) / 1_000_000);
        return new Controlled(StructureReflection.withSets(original, out), Map.copyOf(bypass), Set.copyOf(included), adaptation,
                Map.copyOf(ground), groupOf, applied, summary, filters, caps);
    }

    /** Whether the chunk's centre passes {@code filter}; no filter passes. */
    static boolean passes(com.customdimensions.core.structure.SiteFilter filter, GeneratorTerrain terrain, int cx, int cz) {
        return filter == null || filter.accepts(terrain, cx * 16 + 8, cz * 16 + 8);
    }

    private static void fixed(List<Holder<StructureSet>> out, Map<Long, Set<Structure>> bypass, List<Placed> placed,
                              String kind, String id, Holder<Structure> structure, StructureSites.Site site) {
        out.add(Holder.direct(new StructureSet(structure, new StructurePlacements.Fixed(site.chunk().x(), site.chunk().z()))));
        bypass.computeIfAbsent(ChunkPos.pack(site.chunk().x(), site.chunk().z()), k -> new HashSet<>()).add(structure.value());
        placed.add(new Placed(kind, id, site.chunk().x(), site.chunk().z(), site.matched()));
        if (!site.matched()) {
            CustomDimensions.LOG.warn("{} {}: none of up to {} tested chunks has its biome; it starts at chunk {}, {} with the biome check "
                    + "bypassed (T53)", kind, id, StructureSites.PLACED_TESTS, site.chunk().x(), site.chunk().z());
        }
    }

    /** The group's index, its exclusion widened until the sites stay within the group's repetition ceiling per pooled structure. */
    static NoiseFieldIndex index(long levelSeed, String level, String groupName, StructureSpec.Group group,
                                 StructureSpec spec, int distinct) {
        long seed = levelSeed ^ StructureNoise.saltOf(level) ^ StructureNoise.saltOf(groupName);
        NoiseProfile profile = NoiseProfile.fromString(group.profile());
        return NoiseFieldIndex.withinBudget(seed, profile, group.exclusion(), group.radial(), spec.radiusChunks(),
                spec.spawnChunkX(), spec.spawnChunkZ(), group.clearSpawnChunks(), distinct * group.repetition(), 4);
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
        if (structure instanceof SetPieceStructure piece && piece.anyBiome()) return true;
        HolderSet<net.minecraft.world.level.biome.Biome> allowed = structure.biomes();
        return biomes.possibleBiomes().stream().anyMatch(allowed::contains);
    }

    /** A group's sites settled within half its exclusion onto a chunk where {@code fits} holds for a biome near sea level. */
    static List<com.customdimensions.core.ChunkPos> settled(NoiseFieldIndex index, long levelSeed, String group,
                                                          java.util.function.Predicate<Holder<net.minecraft.world.level.biome.Biome>> fits,
                                                          BiomeResolver sampler, int seaLevel) {
        return settled(index, levelSeed, group, fits, sampler, seaLevel, null);
    }

    /** As above; {@code sited}, when given, answers instead: a pooled structure's biome and its site filter both hold there. */
    static List<com.customdimensions.core.ChunkPos> settled(NoiseFieldIndex index, long levelSeed, String group,
                                                          java.util.function.Predicate<Holder<net.minecraft.world.level.biome.Biome>> fits,
                                                          BiomeResolver sampler, int seaLevel,
                                                          java.util.function.BiPredicate<Integer, Integer> sited) {
        return StructureSites.settle(index.positions(), StructureSites.seed(levelSeed, "settle:" + group), index.exclusion() * 8,
                sited != null ? sited : (cx, cz) -> anyBiomeAt(fits, sampler, cx, cz, seaLevel));
    }

    /** Whether {@code fits} holds for the biome at the chunk's centre, near sea level: three biome reads. */
    private static boolean anyBiomeAt(java.util.function.Predicate<Holder<net.minecraft.world.level.biome.Biome>> fits,
                                      BiomeResolver sampler, int cx, int cz, int seaLevel) {
        int qx = QuartPos.fromBlock(cx * 16 + 8);
        int qz = QuartPos.fromBlock(cz * 16 + 8);
        for (int dy : new int[] {0, 24, -24}) {
            if (fits.test(sampler.getNoiseBiome(qx, QuartPos.fromBlock(seaLevel + dy), qz))) return true;
        }
        return false;
    }

    /** The structure's biome at the chunk's centre, near sea level. */
    /**
     * Whether a forced start or a want of {@code structure} would stand in one
     * of its biomes at this chunk: its biome near sea level ({@link #biomeAt}),
     * and, for a structure above ground, at the surface of the chunk's centre
     * too, where its own biome check reads it.
     */
    static boolean startBiomeAt(Structure structure, ChunkGenerator generator, net.minecraft.world.level.levelgen.RandomState random,
                                BiomeResolver sampler, int cx, int cz, int seaLevel) {
        if (!biomeAt(structure, sampler, cx, cz, seaLevel)) return false;
        if (UndergroundGround.underground(structure)) return true;
        int x = cx * 16 + 8;
        int z = cz * 16 + 8;
        net.minecraft.world.level.LevelHeightAccessor height = net.minecraft.world.level.LevelHeightAccessor.create(generator.getMinY(),
                generator.getGenDepth());
        int y = generator.getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, height, random);
        return biomeFits(structure, sampler.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)));
    }

    static boolean biomeAt(Structure structure, BiomeResolver sampler, int cx, int cz, int seaLevel) {
        int qx = QuartPos.fromBlock(cx * 16 + 8);
        int qz = QuartPos.fromBlock(cz * 16 + 8);
        for (int dy : new int[] {0, 24, -24}) {
            if (biomeFits(structure, sampler.getNoiseBiome(qx, QuartPos.fromBlock(seaLevel + dy), qz))) {
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
