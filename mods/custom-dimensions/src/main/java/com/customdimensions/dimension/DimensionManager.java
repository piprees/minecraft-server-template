package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.mixin.MinecraftServerAccessor;
import com.customdimensions.mixin.MultiNoiseBiomeSourceAccessor;
import com.customdimensions.mixin.SimpleRegistryAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.CheckerboardBiomeSource;
import net.minecraft.world.biome.source.FixedBiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.surfacebuilder.MaterialRules;
import net.minecraft.world.gen.WorldPresets;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
    // Dimensions whose ServerWorld must be (re)created at the next safe point.
    // Worlds are created lazily and dropped by the idle unloader, so a portal
    // can target a dimension with no live world. Creating it from inside
    // ServerWorld.tick would mutate the server's worlds map mid-iteration
    // (ConcurrentModificationException) — requests queue here and are drained
    // from END_SERVER_TICK instead.
    private final Set<String> pendingWorldLoads = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Dimensions whose live ServerWorld must be torn down at the next safe
    // point (deleted via /dimension delete). Same END_SERVER_TICK drain as
    // pendingWorldLoads, same reason. Without this, a deleted dimension's
    // world sat in the server's worlds map until restart — the idle unloader
    // skips any world with no config entry.
    private final Set<String> pendingWorldUnloads = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Definitions registered at runtime via /customdim create for dimensions
    // that have NO config entry. The seed/density/peaceful mixins consult
    // this after the config so command-created candidates get their real
    // seed (without it they silently clone the main world).
    private final Map<String, DimensionConfig> runtimeDefinitions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private boolean bootReconciled = false;

    public static DimensionManager getInstance() {
        return INSTANCE;
    }

    public void onServerStart(MinecraftServer server) {
        this.server = server;
        this.bootReconciled = false;
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
            // Per-dim isolation: one broken config must not abort registration
            // of every dimension after it.
            DimensionFingerprints.init(this.server);
            for (DimensionConfig def : MultiverseConfig.getInstance().getCustomDimensions()) {
                RegistryKey<DimensionOptions> key = RegistryKey.of(RegistryKeys.DIMENSION, def.getDimensionIdentifier());
                if (dimRegistry.contains(key)) {
                    // The persisted generator (level.dat) wins for existing
                    // dimensions — warn on drift, never delete or regenerate.
                    DimensionFingerprints.checkExisting(def, this.server);
                    continue;
                }
                try {
                    DimensionOptions options = this.createDimensionOptions(def);
                    dimRegistry.add(key, options, RegistryEntryInfo.DEFAULT);
                    DimensionFingerprints.record(def);
                    MultiverseServer.LOGGER.info("Registered dimension: {}", key);
                } catch (Exception e) {
                    MultiverseServer.LOGGER.error("Failed to register dimension {}", key, e);
                }
            }
            DimensionFingerprints.warnOrphans(MultiverseConfig.getInstance().getDimensionNames());
        } finally {
            if (wasFrozen) {
                accessor.setFrozen(true);
            }
        }
    }

    // Optional per-dimension ChunkGeneratorSettings override ("noiseSettings"
    // in multiverse_config.json, e.g. "adventure:wide"). Resolved against the
    // dynamic worldgen/noise_settings registry; the adventure:* presets ship
    // inside this mod's jar datapack. Additive: unset or unknown ids keep the
    // dimension's current generator settings — an unknown id must never turn
    // a boot into a crash loop, so it logs and falls back instead.
    private RegistryEntry<ChunkGeneratorSettings> resolveNoiseSettingsOverride(DimensionConfig def) {
        String id = def.getNoiseSettings();
        if (id == null || id.isEmpty()) {
            return null;
        }
        Identifier ident = Identifier.tryParse(id.toLowerCase());
        DynamicRegistryManager.Immutable regManager = this.server.getCombinedDynamicRegistries().getCombinedRegistryManager();
        Registry<ChunkGeneratorSettings> registry = regManager.get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
        Optional<? extends RegistryEntry<ChunkGeneratorSettings>> entry = ident == null
                ? Optional.empty()
                : registry.getEntry(RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, ident));
        if (entry.isEmpty()) {
            MultiverseServer.LOGGER.warn(
                    "noiseSettings '{}' for dimension {} not found in the noise_settings registry — using the type's default generator",
                    id, def.getName());
            return null;
        }
        MultiverseServer.LOGGER.info("Dimension {} uses noise settings {}", def.getName(), id);
        return entry.get();
    }

    // Custom dimension type from the "environment" block (v4 Phase 4): the
    // dimension's type entry is its registered {ns}:{slug}_type when an
    // environment is configured, else the base type it would clone anyway.
    private RegistryEntry<net.minecraft.world.dimension.DimensionType> typeEntryFor(
            DimensionConfig def, RegistryEntry<net.minecraft.world.dimension.DimensionType> base) {
        return DimensionTypeBuilder.typeEntryFor(this.server, def, base);
    }

    // Suffixes for the runtime-built ChunkGeneratorSettings variants this
    // mod registers under {namespace}:{slug}{suffix} — distinct per call
    // site, because a dimension can need both (settingsOverrides, then
    // surface composition on top of it) and the second registration must
    // build its OWN entry rather than finding the first one's id already
    // taken and handing back its now-stale, pre-composition value.
    private static final String SETTINGS_OVERRIDES_SUFFIX = "_settings_overrides";
    private static final String SURFACE_COMPOSED_SUFFIX = "_surface_composed";

    // Pure: the registry id a dimension's runtime-built settings variant
    // gets. Same shape as DimensionTypeBuilder's "{slug}_type".
    static Identifier generatorSettingsId(DimensionConfig def, String suffix) {
        return Identifier.of(def.getNamespace(), def.getName() + suffix);
    }

    // Registers a runtime-built ChunkGeneratorSettings as a REFERENCE entry
    // and hands that back — never RegistryEntry.of(value), which wraps a
    // DIRECT entry that vanilla's RegistryElementCodec inlines wholesale
    // (noise router and surface rule included) into level.dat on every save
    // instead of writing an id. Idempotent like DimensionTypeBuilder.
    // typeEntryFor: an id already registered wins, so repeated calls for the
    // same dimension (the seed roller's headless facts runs measure one
    // dimension many times over a roll) reuse one entry instead of never
    // being read back out.
    private RegistryEntry<ChunkGeneratorSettings> registerGeneratorSettings(Identifier id, ChunkGeneratorSettings value) {
        DynamicRegistryManager.Immutable regManager = this.server.getCombinedDynamicRegistries().getCombinedRegistryManager();
        Registry<ChunkGeneratorSettings> registry = regManager.get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
        RegistryKey<ChunkGeneratorSettings> key = RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, id);
        Optional<? extends RegistryEntry<ChunkGeneratorSettings>> existing = registry.getEntry(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        MutableRegistry<ChunkGeneratorSettings> mutable = (MutableRegistry<ChunkGeneratorSettings>) registry;
        SimpleRegistryAccessor accessor = (SimpleRegistryAccessor) mutable;
        boolean wasFrozen = accessor.isFrozen();
        if (wasFrozen) {
            accessor.setFrozen(false);
        }
        try {
            RegistryEntry<ChunkGeneratorSettings> entry = mutable.add(key, value, RegistryEntryInfo.DEFAULT);
            MultiverseServer.LOGGER.info("Registered generator settings: {}", id);
            return entry;
        } finally {
            if (wasFrozen) {
                accessor.setFrozen(true);
            }
        }
    }

    // Swap a noise generator's ChunkGeneratorSettings while keeping its biome
    // source. No-op for flat/void generators (noiseSettings has no meaning
    // there) and when no override is set.
    private static ChunkGenerator withSettings(ChunkGenerator generator, RegistryEntry<ChunkGeneratorSettings> settings) {
        if (settings != null && generator instanceof NoiseChunkGenerator noiseGen) {
            return new NoiseChunkGenerator(noiseGen.getBiomeSource(), settings);
        }
        return generator;
    }

    // Build a multi-noise source for an arbitrary biome list, in four tiers.
    // Every entry with explicit "parameters" gets a hypercube, so a biome
    // named several times holds several cells. A biome the base source already
    // places keeps its own cells. A biome the base source does not place,
    // whose own mod declared cells for this family, gets those cells ([T19]).
    // Everything left is dealt the remaining parameter regions round-robin, so
    // it genuinely appears in the layout instead of being silently dropped — a
    // list with no native matches at all (e.g. the_crimson_nexus,
    // the_souldrift) still produces its requested biomes rather than falling
    // back to plains.
    private BiomeSource buildMixedSource(MultiNoiseBiomeSource base, Registry<Biome> biomeRegistry,
                                         String biomeList, String dimName,
                                         List<DimensionConfig.BiomeBand> bands,
                                         Map<Identifier, List<MultiNoiseUtil.NoiseHypercube>> declaredCells) {
        Set<Identifier> allowedIds = Arrays.stream(biomeList.split(","))
                .map(String::trim).map(Identifier::tryParse).filter(id -> id != null)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        // Global suppress list strips listed biomes up front, so foreign
        // round-robin slots go to the surviving biomes instead of being
        // filtered out of the finished source.
        Set<Identifier> suppressedBiomes = BiomeSuppression.suppressedIds();
        if (!suppressedBiomes.isEmpty() && allowedIds.removeAll(suppressedBiomes)) {
            MultiverseServer.LOGGER.info(
                    "Dimension {}: suppress.biomes removed listed biome(s); {} of the requested list remain",
                    dimName, allowedIds.size());
        }

        // Explicit per-biome parameters (Tier 3): every listed band with a
        // valid "parameters" object places its own hypercube, and its biome is
        // withdrawn from the native/round-robin machinery entirely — its
        // natural regions (if any) join the pool for foreign biomes.
        Placed<MultiNoiseUtil.NoiseHypercube> explicit = explicitBands(bands, allowedIds,
                band -> hypercubeFrom(band.parameters(), dimName, band.id()));

        MultiNoiseUtil.Entries<RegistryEntry<Biome>> entries =
                ((MultiNoiseBiomeSourceAccessor) base).invokeGetBiomeEntries();
        List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> nativeEntries = new ArrayList<>();
        List<MultiNoiseUtil.NoiseHypercube> pool = new ArrayList<>();
        Set<Identifier> nativeIds = new HashSet<>();
        for (Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>> pair : entries.getEntries()) {
            Identifier id = pair.getSecond().getKey().map(RegistryKey::getValue).orElse(null);
            if (id != null && allowedIds.contains(id) && !explicit.ids().contains(id)) {
                nativeEntries.add(pair);
                nativeIds.add(id);
            } else {
                pool.add(pair.getFirst());
            }
        }

        List<RegistryEntry<Biome>> unplaced = new ArrayList<>();
        for (Identifier id : allowedIds) {
            if (nativeIds.contains(id) || explicit.ids().contains(id)) {
                continue;
            }
            Optional<RegistryEntry.Reference<Biome>> entry =
                    biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, id));
            if (entry.isPresent()) {
                unplaced.add(entry.get());
            } else {
                MultiverseServer.LOGGER.warn("Dimension {}: biome {} not in the registry — skipped", dimName, id);
            }
        }

        List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> result = new ArrayList<>();
        int explicitCells = 0;
        for (Pair<MultiNoiseUtil.NoiseHypercube, Identifier> cell : explicit.cells()) {
            Optional<RegistryEntry.Reference<Biome>> biomeEntry =
                    biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, cell.getSecond()));
            if (biomeEntry.isPresent()) {
                result.add(Pair.of(cell.getFirst(), biomeEntry.get()));
                explicitCells++;
            } else {
                MultiverseServer.LOGGER.warn("Dimension {}: biome {} (with parameters) not in the registry — skipped",
                        dimName, cell.getSecond());
            }
        }
        result.addAll(nativeEntries);
        Dealt<RegistryEntry<Biome>, MultiNoiseUtil.NoiseHypercube> dealt =
                dealRemaining(unplaced, biome -> declaredCellsOf(declaredCells, biome), pool);
        result.addAll(dealt.natural());
        result.addAll(dealt.filler());
        if (result.isEmpty()) {
            MultiverseServer.LOGGER.warn("Dimension {}: no usable biomes in '{}' — keeping the base source", dimName, biomeList);
            return base;
        }
        // Banded entries are counted from the config, not from the placements:
        // both other counts here are per-biome and agree with each other while
        // an entry is being lost, so the file's own number is what makes the
        // loss legible.
        MultiverseServer.LOGGER.info(
                "Dimension {}: biome source built ({} explicit of {} banded, {} native, "
                + "{} natural over {} cell(s), {} mixed-in of {} requested)",
                dimName, explicitCells, bands == null ? 0 : bands.size(), nativeEntries.size(),
                unplaced.size() - dealt.foreign().size(), dealt.natural().size(),
                dealt.foreign().size(), allowedIds.size());
        return MultiNoiseBiomeSource.create(new MultiNoiseUtil.Entries<>(result));
    }

    /**
     * The base source with the config's asks appended, for the types whose
     * biomes arrive naturally. Null when the list is empty — the base
     * generator is then used whole — or when the generator carries no
     * multi-noise source to append to.
     */
    private BiomeSource resolveAdditiveSource(DimensionConfig def, Registry<Biome> biomeRegistry,
                                              ChunkGenerator generator) {
        String biomeList = def.getBiome();
        if (biomeList == null || biomeList.isEmpty()) {
            return null;
        }
        MultiNoiseBiomeSource base = multiNoiseOf(generator);
        if (base == null) {
            MultiverseServer.LOGGER.error(
                    "Dimension {}: biome list IGNORED — no multi-noise source to append to (generator {})",
                    def.getName(),
                    generator == null ? "absent" : generator.getBiomeSource().getClass().getName());
            return null;
        }
        return buildAdditiveSource(base, biomeRegistry, biomeList, def.getName(),
                def.getBiomeBands(), declaredCellsFor(base, biomeRegistry));
    }

    /**
     * Every base entry, plus a cell for each asked biome the base does not
     * already place. This is the opposite of {@link #buildMixedSource}, which
     * keeps only what the list names: here the list is an ask on top of a
     * world that populates itself, so nothing the base places is removed.
     *
     * <p>An ask takes its cell from its own band, or from what its mod
     * declared. One with neither is named in a WARN and does not generate —
     * seating it would mean displacing a base biome, and a fallback that
     * fills from another pool is forbidden.
     *
     * <p>An appended cell competes in vanilla's nearest-point lookup on equal
     * terms with the base's own. Two consequences worth knowing: a range
     * contains its endpoints, so an ask overlapping a base entry ties at
     * distance zero and the tie-break decides rather than the author; and the
     * lookup charges nothing for an axis a hypercube leaves unconstrained, so
     * a loosely-banded ask is a far larger target than a tightly-banded one.
     */
    private BiomeSource buildAdditiveSource(MultiNoiseBiomeSource base, Registry<Biome> biomeRegistry,
                                            String biomeList, String dimName,
                                            List<DimensionConfig.BiomeBand> bands,
                                            Map<Identifier, List<MultiNoiseUtil.NoiseHypercube>> declaredCells) {
        Set<Identifier> askedIds = Arrays.stream(biomeList.split(","))
                .map(String::trim).map(Identifier::tryParse).filter(id -> id != null)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        askedIds.removeAll(BiomeSuppression.suppressedIds());

        Placed<MultiNoiseUtil.NoiseHypercube> explicit = explicitBands(bands, askedIds,
                band -> hypercubeFrom(band.parameters(), dimName, band.id()));

        MultiNoiseUtil.Entries<RegistryEntry<Biome>> entries =
                ((MultiNoiseBiomeSourceAccessor) base).invokeGetBiomeEntries();
        List<Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>>> result =
                new ArrayList<>(entries.getEntries());
        Set<Identifier> basePlaced = new HashSet<>();
        for (Pair<MultiNoiseUtil.NoiseHypercube, RegistryEntry<Biome>> pair : entries.getEntries()) {
            pair.getSecond().getKey().map(RegistryKey::getValue).ifPresent(basePlaced::add);
        }

        int banded = 0;
        for (Pair<MultiNoiseUtil.NoiseHypercube, Identifier> cell : explicit.cells()) {
            Optional<RegistryEntry.Reference<Biome>> entry =
                    biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, cell.getSecond()));
            if (entry.isPresent()) {
                result.add(Pair.of(cell.getFirst(), entry.get()));
                banded++;
            } else {
                MultiverseServer.LOGGER.warn("Dimension {}: biome {} (with parameters) not in the registry — skipped",
                        dimName, cell.getSecond());
            }
        }

        Appended asks = classifyAsks(askedIds, explicit.ids(), basePlaced, declaredCells.keySet());
        int declared = 0;
        for (Identifier id : asks.declared()) {
            Optional<RegistryEntry.Reference<Biome>> entry =
                    biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, id));
            if (entry.isEmpty()) {
                MultiverseServer.LOGGER.warn("Dimension {}: biome {} not in the registry — skipped", dimName, id);
                continue;
            }
            for (MultiNoiseUtil.NoiseHypercube cell : declaredCells.getOrDefault(id, List.of())) {
                result.add(Pair.of(cell, entry.get()));
                declared++;
            }
        }
        List<Identifier> unplaceable = asks.unplaceable();
        if (!unplaceable.isEmpty()) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: {} asked biome(s) will not generate — nothing declares a placement and no "
                    + "band gives one: {}. Add a band with \"parameters\", or remove them from the list.",
                    dimName, unplaceable.size(), unplaceable);
        }
        MultiverseServer.LOGGER.info(
                "Dimension {}: biome source appended ({} base kept, {} banded, {} declared, "
                + "{} already arriving, {} unplaceable of {} asked)",
                dimName, entries.getEntries().size(), banded, declared, asks.arriving().size(),
                unplaceable.size(), askedIds.size());
        return banded + declared == 0 ? base
                : MultiNoiseBiomeSource.create(new MultiNoiseUtil.Entries<>(result));
    }

    /**
     * Where each asked biome gets its ground when the base source is kept
     * whole. A band wins first because it is the author overriding; the base
     * next, because a biome it already places needs nothing; then the
     * placement the biome's own mod declared. What is left has no cell and
     * does not generate.
     */
    record Appended(List<Identifier> arriving, List<Identifier> declared, List<Identifier> unplaceable) {
    }

    /** Classifies asks against what is banded, already placed, and declared. */
    static Appended classifyAsks(Collection<Identifier> asked, Set<Identifier> bandedIds,
                                 Set<Identifier> basePlaced, Set<Identifier> declaredIds) {
        List<Identifier> arriving = new ArrayList<>();
        List<Identifier> declared = new ArrayList<>();
        List<Identifier> unplaceable = new ArrayList<>();
        for (Identifier id : asked) {
            if (bandedIds.contains(id)) {
                continue;
            }
            if (basePlaced.contains(id)) {
                arriving.add(id);
            } else if (declaredIds.contains(id)) {
                declared.add(id);
            } else {
                unplaceable.add(id);
            }
        }
        return new Appended(arriving, declared, unplaceable);
    }

    /**
     * The cells a config's explicit bands place, and the biome ids those bands
     * withdraw from native and round-robin placement.
     *
     * <p>{@code cells} carries one entry per usable band, in file order, so a
     * biome named by several bands holds several cells — vanilla's parameter
     * table gives {@code minecraft:plains} a hypercube in each climate region
     * it belongs to the same way. {@code ids} is the withdrawal set, which is
     * why it is a set and {@code cells} is not.
     */
    record Placed<C>(List<Pair<C, Identifier>> cells, Set<Identifier> ids) {
    }

    /**
     * Places every band a dimension declares. A band is dropped when its id
     * does not parse, when it names a biome this dimension does not list, or
     * when {@code toCell} rejects its parameters; anything else places a cell
     * and withdraws its biome.
     *
     * <p>Generic over the cell type so the rules are testable: this suite
     * cannot bootstrap Minecraft's registries.
     */
    static <C> Placed<C> explicitBands(List<DimensionConfig.BiomeBand> bands,
                                       Set<Identifier> allowedIds,
                                       Function<DimensionConfig.BiomeBand, C> toCell) {
        List<Pair<C, Identifier>> cells = new ArrayList<>();
        Set<Identifier> ids = new java.util.LinkedHashSet<>();
        if (bands == null) {
            return new Placed<>(cells, ids);
        }
        for (DimensionConfig.BiomeBand band : bands) {
            Identifier id = Identifier.tryParse(band.id());
            if (id == null || !allowedIds.contains(id)) {
                continue;
            }
            C cell = toCell.apply(band);
            if (cell != null) {
                cells.add(Pair.of(cell, id));
                ids.add(id);
            }
        }
        return new Placed<>(cells, ids);
    }

    /** A biome's declared cells, or empty when its mod declared none. */
    private static List<MultiNoiseUtil.NoiseHypercube> declaredCellsOf(
            Map<Identifier, List<MultiNoiseUtil.NoiseHypercube>> declaredCells,
            RegistryEntry<Biome> biome) {
        Identifier id = biome.getKey().map(RegistryKey::getValue).orElse(null);
        return id == null ? List.of() : declaredCells.getOrDefault(id, List.of());
    }

    /**
     * The outcome of placing the biomes the base source does not place:
     * {@code natural} carries each biome's own declared cells, {@code filler}
     * the leftover parameter regions dealt round-robin, and {@code foreign}
     * the biomes that took them.
     */
    record Dealt<B, C>(List<Pair<C, B>> natural, List<Pair<C, B>> filler, List<B> foreign) {
    }

    /**
     * Places every biome the base source does not place. A biome whose own mod
     * declared climate cells for this family takes those cells; the rest are
     * dealt the leftover parameter regions round-robin, which is arbitrary
     * placement and the reason one such biome can hold most of a dimension
     * ([T19]).
     *
     * <p>The leftover pool is dropped whole when nothing is left foreign, so a
     * dimension whose every biome is placed carries only the cells it asked
     * for.
     *
     * <p>Generic over the biome and cell types so the rules are testable: this
     * suite cannot bootstrap Minecraft's registries.
     */
    static <B, C> Dealt<B, C> dealRemaining(List<B> remaining,
                                            Function<B, List<C>> declaredCells,
                                            List<C> pool) {
        List<Pair<C, B>> natural = new ArrayList<>();
        List<B> foreign = new ArrayList<>();
        for (B biome : remaining) {
            List<C> cells = declaredCells.apply(biome);
            if (cells == null || cells.isEmpty()) {
                foreign.add(biome);
                continue;
            }
            for (C cell : cells) {
                natural.add(Pair.of(cell, biome));
            }
        }
        List<Pair<C, B>> filler = new ArrayList<>();
        if (!foreign.isEmpty()) {
            for (int i = 0; i < pool.size(); i++) {
                filler.add(Pair.of(pool.get(i), foreign.get(i % foreign.size())));
            }
        }
        return new Dealt<>(natural, filler, foreign);
    }

    // One NoiseHypercube from a raw "parameters" object. Each axis is a
    // number (point) or [min, max]; unset axes span the whole [-2, 2] space
    // (the biome claims everything the interval doesn't constrain).
    // "offset" is a plain float >= 0. Any invalid axis -> warn + null (the
    // biome falls back to plain-listed behaviour, never a crash).
    private static MultiNoiseUtil.NoiseHypercube hypercubeFrom(
            com.google.gson.JsonObject params, String dimName, String biomeId) {
        MultiNoiseUtil.ParameterRange[] ranges = new MultiNoiseUtil.ParameterRange[6];
        String[] axes = {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"};
        for (int i = 0; i < axes.length; i++) {
            MultiNoiseUtil.ParameterRange range = parameterRange(params.get(axes[i]));
            if (range == null) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: biome {} parameters.{} invalid (need a number or [min, max] within -2..2) — ignoring the parameters block",
                        dimName, biomeId, axes[i]);
                return null;
            }
            ranges[i] = range;
        }
        float offset = 0.0f;
        com.google.gson.JsonElement off = params.get("offset");
        if (off != null && off.isJsonPrimitive() && off.getAsJsonPrimitive().isNumber()) {
            float v = off.getAsFloat();
            if (v >= 0.0f && v <= 1.0f) {
                offset = v;
            } else {
                MultiverseServer.LOGGER.warn("Dimension {}: biome {} parameters.offset {} outside 0..1 — using 0",
                        dimName, biomeId, v);
            }
        }
        return MultiNoiseUtil.createNoiseHypercube(
                ranges[0], ranges[1], ranges[2], ranges[3], ranges[4], ranges[5], offset);
    }

    // A parameter axis: absent -> full span; number -> point; [min, max] ->
    // interval. Null on malformed input or values outside vanilla's -2..2.
    private static MultiNoiseUtil.ParameterRange parameterRange(com.google.gson.JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return MultiNoiseUtil.ParameterRange.of(-2.0f, 2.0f);
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            float v = e.getAsFloat();
            return v < -2.0f || v > 2.0f ? null : MultiNoiseUtil.ParameterRange.of(v);
        }
        if (e.isJsonArray() && e.getAsJsonArray().size() == 2) {
            com.google.gson.JsonElement lo = e.getAsJsonArray().get(0);
            com.google.gson.JsonElement hi = e.getAsJsonArray().get(1);
            if (lo.isJsonPrimitive() && lo.getAsJsonPrimitive().isNumber()
                    && hi.isJsonPrimitive() && hi.getAsJsonPrimitive().isNumber()) {
                float min = lo.getAsFloat();
                float max = hi.getAsFloat();
                if (min <= max && min >= -2.0f && max <= 2.0f) {
                    return MultiNoiseUtil.ParameterRange.of(min, max);
                }
            }
        }
        return null;
    }

    /** Bounds the unwrap loop so a cyclic or pathological source cannot spin. */
    static final int MAX_UNWRAP_DEPTH = 8;

    /**
     * The multi-noise source a generator composes from, past any wrapper.
     *
     * <p>Two wrappers sit over a source here, in either order:
     * {@link PatchedBiomeSource} for a dimension's {@code biomePatches}, and
     * Lithostitched's injector for a base world any mod's datapack has injected
     * biomes into. Neither publishes parameter entries, so
     * composing from one is impossible and refusing drops the dimension's whole
     * biome list. The source underneath is what this mod builds from; anything a
     * wrapper adds reaches a managed dimension by being named in its
     * {@code biomes} list, like any other biome.
     *
     * <p>Unwrapping fails open: a source it cannot see through comes back as
     * itself and ends the loop.
     *
     * <p>Null when no multi-noise source is reachable, which the caller reports.
     */
    public static MultiNoiseBiomeSource multiNoiseOf(ChunkGenerator generator) {
        if (generator == null) {
            return null;
        }
        BiomeSource source = unwrapToMultiNoise(generator.getBiomeSource());
        return source instanceof MultiNoiseBiomeSource multiNoise ? multiNoise : null;
    }

    /**
     * The unwrapping step of {@link #multiNoiseOf}, to a fixed point.
     *
     * <p>Returns the source unchanged once it is a {@link MultiNoiseBiomeSource}, once
     * neither unwrapper can see through it, or once {@link #MAX_UNWRAP_DEPTH} is spent.
     */
    static BiomeSource unwrapToMultiNoise(BiomeSource source) {
        return unwrapToFixedPoint(source,
                s -> s instanceof MultiNoiseBiomeSource,
                DimensionManager::unwrapOneLayer);
    }

    /**
     * One wrapper off a biome source: this mod's patch layer, or a foreign one an
     * unwrapper can see through. A source nothing can unwrap comes back as itself,
     * which every caller reads as the end of the walk.
     */
    static BiomeSource unwrapOneLayer(BiomeSource source) {
        return source instanceof PatchedBiomeSource patched
                ? patched.delegate()
                : com.customdimensions.compat.LithostitchedCompat.unwrap(source);
    }

    /**
     * Applies {@code step} until {@code done} holds, until a step returns its own input,
     * or until {@link #MAX_UNWRAP_DEPTH} steps are spent — whichever comes first.
     *
     * <p>Separate from the biome-source types so the termination rules are unit-testable:
     * {@code BiomeSource} initialises {@code Registries}, which cannot be bootstrapped in
     * this suite.
     */
    static <T> T unwrapToFixedPoint(T start, java.util.function.Predicate<T> done,
                                    java.util.function.UnaryOperator<T> step) {
        T current = start;
        for (int i = 0; i < MAX_UNWRAP_DEPTH && !done.test(current); i++) {
            T next = step.apply(current);
            if (next == current) {
                break;
            }
            current = next;
        }
        return current;
    }

    // Resolve the biome source for a dimension with a biome list: prefer the
    // dimension's own family source as the base (natural placements), fall
    // back to the overworld's. Null biome list -> null (caller keeps base).
    private BiomeSource resolveListedSource(DimensionConfig def, Registry<Biome> biomeRegistry,
                                            ChunkGenerator baseGenerator, ChunkGenerator overworldGenerator) {
        String biomeList = def.getBiome();
        if (biomeList == null || biomeList.isEmpty()) {
            return null;
        }
        MultiNoiseBiomeSource base = multiNoiseOf(baseGenerator);
        if (base != null) {
            return buildMixedSource(base, biomeRegistry, biomeList, def.getName(),
                    def.getBiomeBands(), declaredCellsFor(base, biomeRegistry));
        }
        MultiNoiseBiomeSource owBase = multiNoiseOf(overworldGenerator);
        if (owBase != null) {
            return buildMixedSource(owBase, biomeRegistry, biomeList, def.getName(),
                    def.getBiomeBands(), declaredCellsFor(owBase, biomeRegistry));
        }
        // Silent here meant a whole dimension quietly generating the base
        // world's biomes instead of its own — name the class, because the mod
        // that replaced it is the only thing that can be removed to fix it.
        MultiverseServer.LOGGER.error(
                "Dimension {}: biome list IGNORED — no multi-noise source to build from "
                + "(own generator {}, overworld generator {}). A mod has replaced the base "
                + "biome source; this dimension will generate the base world's biomes.",
                def.getName(),
                baseGenerator == null ? "absent" : baseGenerator.getBiomeSource().getClass().getName(),
                overworldGenerator == null ? "absent" : overworldGenerator.getBiomeSource().getClass().getName());
        return null;
    }

    /**
     * The cells other mods declared for the family {@code base} belongs to,
     * empty for any other source.
     *
     * <p>TerraBlender registers regions per family (OVERWORLD, NETHER — there
     * is no END type) and shapes their cells for that family's climate router.
     * Handing overworld cells to an End or paradise_lost source would place
     * biomes where that router never reaches, which is worse than the
     * round-robin it replaces — so the family is decided by identity against
     * the overworld's and the nether's own sources, not by dimension type.
     */
    private Map<Identifier, List<MultiNoiseUtil.NoiseHypercube>> declaredCellsFor(
            MultiNoiseBiomeSource base, Registry<Biome> biomeRegistry) {
        MutableRegistry<DimensionOptions> dimRegistry = this.getDimensionRegistry();
        if (base == null || dimRegistry == null) {
            return Map.of();
        }
        if (base == multiNoiseSourceOf(dimRegistry.get(DimensionOptions.OVERWORLD))) {
            return com.customdimensions.compat.TerraBlenderCompat.cellsByBiome(biomeRegistry, true);
        }
        if (base == multiNoiseSourceOf(dimRegistry.get(DimensionOptions.NETHER))) {
            return com.customdimensions.compat.TerraBlenderCompat.cellsByBiome(biomeRegistry, false);
        }
        return Map.of();
    }

    private static MultiNoiseBiomeSource multiNoiseSourceOf(DimensionOptions options) {
        return options == null ? null : multiNoiseOf(options.chunkGenerator());
    }

    // "flatBiome" for superflat dims: unknown or unset falls back to plains
    // (warn on unknown — same never-crash policy as noiseSettings).
    private RegistryEntry<Biome> resolveFlatBiome(DimensionConfig def, Registry<Biome> biomeRegistry) {
        String id = def.getFlatBiome();
        if (id != null && !id.isBlank()) {
            Identifier ident = Identifier.tryParse(id.trim().toLowerCase());
            Optional<RegistryEntry.Reference<Biome>> entry = ident == null ? Optional.empty()
                    : biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, ident));
            if (entry.isPresent()) {
                return entry.get();
            }
            MultiverseServer.LOGGER.warn("Dimension {}: flatBiome '{}' not in the registry — using plains", def.getName(), id);
        }
        return biomeRegistry.getEntry(biomeRegistry.get(BiomeKeys.PLAINS));
    }

    private static List<FlatChunkGeneratorLayer> defaultFlatLayers() {
        return List.of(
                new FlatChunkGeneratorLayer(1, Blocks.BEDROCK),
                new FlatChunkGeneratorLayer(2, Blocks.DIRT),
                new FlatChunkGeneratorLayer(1, Blocks.GRASS_BLOCK));
    }

    // Custom "layers" for superflat dims. Vanilla layer semantics: bottom-up,
    // height = thickness (0..4064, the vanilla codec bound; 0 is legal and
    // generates nothing). ANY invalid entry -> warn + the full default stack:
    // all-or-nothing so a typo can't half-build a world silently.
    private static List<FlatChunkGeneratorLayer> resolveFlatLayers(DimensionConfig def) {
        List<DimensionConfig.FlatLayer> configured = def.getLayers();
        if (configured == null || configured.isEmpty()) {
            return defaultFlatLayers();
        }
        List<FlatChunkGeneratorLayer> layers = new ArrayList<>();
        for (DimensionConfig.FlatLayer layer : configured) {
            Identifier id = layer.block == null ? null : Identifier.tryParse(layer.block.trim().toLowerCase());
            Optional<Block> block = id == null ? Optional.empty() : Registries.BLOCK.getOrEmpty(id);
            int height = layer.height == null ? -1 : layer.height;
            if (block.isEmpty() || height < 0 || height > 4064) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: invalid superflat layer (block: {}, height: {}) — using the default bedrock/dirt/grass stack",
                        def.getName(), layer.block, layer.height);
                return defaultFlatLayers();
            }
            layers.add(new FlatChunkGeneratorLayer(height, block.get()));
        }
        return layers;
    }

    private DimensionOptions createDimensionOptions(DimensionConfig def) {
        MutableRegistry<DimensionOptions> dimRegistry = this.getDimensionRegistry();
        DynamicRegistryManager.Immutable regManager = this.server.getCombinedDynamicRegistries().getCombinedRegistryManager();
        Registry<Biome> biomeRegistry = regManager.get(RegistryKeys.BIOME);
        String type = def.getType();
        // Registration runs at beforeCreateWorlds, when getOverworld() is still
        // null — fall back to the save's generator seed for seed-less configs.
        long worldSeed;
        if (def.getSeed() != null) {
            worldSeed = def.getSeed();
        } else {
            ServerWorld overworld = this.server.getOverworld();
            worldSeed = overworld != null ? overworld.getSeed()
                    : this.server.getSaveProperties().getGeneratorOptions().getSeed();
        }
        RegistryEntry<ChunkGeneratorSettings> settingsOverride = this.resolveNoiseSettingsOverride(def);
        if (settingsOverride != null && ("void".equals(type) || "superflat".equals(type))) {
            MultiverseServer.LOGGER.warn(
                    "noiseSettings on dimension {} is ignored: type '{}' uses a flat generator", def.getName(), type);
        }

        DimensionOptions overworldOpts = dimRegistry.get(DimensionOptions.OVERWORLD);
        if (overworldOpts == null) {
            throw new IllegalStateException("Cannot create dimension options: overworld not found");
        }

        DimensionOptions built = switch (type) {
            case "void" -> {
                // A void with a biome list keeps a REAL biome layout even
                // though no terrain generates — mob spawning, fog and
                // ambience still read the biome. This must be a NOISE
                // generator: a flat generator samples the multi-noise
                // source with zero climate noise, collapsing the layout to
                // one biome everywhere and ignoring the seed. adventure:void
                // ships in the jar datapack — overworld climate router,
                // final_density -1.
                BiomeSource voidSource = this.resolveListedSource(def, biomeRegistry,
                        null, overworldOpts.chunkGenerator());
                Registry<ChunkGeneratorSettings> nsRegistry = regManager.get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
                Optional<? extends RegistryEntry<ChunkGeneratorSettings>> voidSettings =
                        nsRegistry.getEntry(RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS,
                                Identifier.of("adventure", "void")));
                if (voidSource != null && voidSettings.isPresent()) {
                    NoiseChunkGenerator voidGen = new NoiseChunkGenerator(voidSource, voidSettings.get());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(voidGen, worldSeed));
                }
                // Fallback (no biome list, or preset missing from the jar):
                // the old flat THE_VOID generator.
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: void fallback to flat generator (biome list: {}, adventure:void present: {})",
                        def.getName(), def.getBiome() != null, voidSettings.isPresent());
                RegistryEntry<Biome> voidBiome = biomeRegistry.getEntry(biomeRegistry.get(BiomeKeys.THE_VOID));
                FlatChunkGeneratorConfig config = new FlatChunkGeneratorConfig(Optional.empty(), voidBiome, List.of())
                    .with(List.of(), Optional.empty(), voidBiome);
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(new FlatChunkGenerator(config), worldSeed));
            }
            case "superflat" -> {
                // Custom "layers" + "flatBiome" (Tier 2): vanilla superflat
                // semantics — layers bottom-up, height = thickness. Invalid
                // config falls back whole (never half-builds a stack).
                RegistryEntry<Biome> flatBiome = this.resolveFlatBiome(def, biomeRegistry);
                List<FlatChunkGeneratorLayer> layers = resolveFlatLayers(def);
                FlatChunkGeneratorConfig config = new FlatChunkGeneratorConfig(Optional.empty(), flatBiome, List.of())
                    .with(layers, Optional.empty(), flatBiome);
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(new FlatChunkGenerator(config), worldSeed));
            }
            case "checkerboard" -> {
                // Deterministic biome grid (vanilla checkerboard source): the
                // listed biomes tile in a fixed pattern INDEPENDENT of seed
                // (index = floorMod((qx >> scale+2) + (qz >> scale+2), n) in
                // quart coords — mirrored by the roller's checkerboard
                // sampler; keep the two in sync). Terrain shape still follows
                // the dimension seed through the overworld noise settings.
                List<RegistryEntry<Biome>> checkerEntries = new ArrayList<>();
                String biomeList = def.getBiome();
                if (biomeList != null) {
                    for (String raw : biomeList.split(",")) {
                        Identifier id = Identifier.tryParse(raw.trim());
                        Optional<RegistryEntry.Reference<Biome>> entry = id == null ? Optional.empty()
                                : biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, id));
                        if (entry.isPresent()) {
                            checkerEntries.add(entry.get());
                        } else {
                            MultiverseServer.LOGGER.warn("Dimension {}: checkerboard biome {} not in the registry — skipped",
                                    def.getName(), raw.trim());
                        }
                    }
                }
                if (checkerEntries.isEmpty()) {
                    MultiverseServer.LOGGER.warn(
                            "Dimension {}: checkerboard needs a non-empty 'biomes' list — falling back to the overworld generator",
                            def.getName());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
                }
                int scale = 2;
                Integer configuredScale = def.getCheckerboardScale();
                if (configuredScale != null) {
                    if (configuredScale >= 0 && configuredScale <= 62) {
                        scale = configuredScale;
                    } else {
                        MultiverseServer.LOGGER.warn("Dimension {}: checkerboardScale {} outside 0-62 — using the default 2",
                                def.getName(), configuredScale);
                    }
                }
                BiomeSource checkerboard = new CheckerboardBiomeSource(RegistryEntryList.of(checkerEntries), scale);
                if (overworldOpts.chunkGenerator() instanceof NoiseChunkGenerator noiseGen) {
                    NoiseChunkGenerator checkerGen = new NoiseChunkGenerator(checkerboard, noiseGen.getSettings());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(checkerGen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
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
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(newGen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "overworld" -> {
                // Biomes arrive from the overworld's own source; the list is
                // an ask on top of them, never a replacement for them.
                if (overworldOpts.chunkGenerator() instanceof NoiseChunkGenerator noiseGen) {
                    BiomeSource appended = this.resolveAdditiveSource(def, biomeRegistry,
                            overworldOpts.chunkGenerator());
                    if (appended != null) {
                        NoiseChunkGenerator newGen = new NoiseChunkGenerator(appended, noiseGen.getSettings());
                        yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(newGen, settingsOverride), worldSeed));
                    }
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "multi_biome" -> {
                if (overworldOpts.chunkGenerator() instanceof NoiseChunkGenerator noiseGen) {
                    BiomeSource mixed = this.resolveListedSource(def, biomeRegistry,
                            overworldOpts.chunkGenerator(), overworldOpts.chunkGenerator());
                    if (mixed == null) {
                        mixed = noiseGen.getBiomeSource();
                    }
                    NoiseChunkGenerator newGen = new NoiseChunkGenerator(mixed, noiseGen.getSettings());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(newGen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "cave" -> {
                // Fully underground world: vanilla still ships the
                // minecraft:caves generator settings (bedrock roof at the top,
                // no sky access, sea/lava level 32). Biome list mixes as
                // usual; an explicit noiseSettings still wins.
                BiomeSource caveSource = this.resolveListedSource(def, biomeRegistry,
                        overworldOpts.chunkGenerator(), overworldOpts.chunkGenerator());
                if (caveSource == null) {
                    caveSource = overworldOpts.chunkGenerator().getBiomeSource();
                }
                if (settingsOverride != null) {
                    NoiseChunkGenerator caveGen = new NoiseChunkGenerator(caveSource, settingsOverride);
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(caveGen, worldSeed));
                }
                Registry<ChunkGeneratorSettings> nsRegistry = regManager.get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
                Optional<? extends RegistryEntry<ChunkGeneratorSettings>> caveSettings =
                        nsRegistry.getEntry(RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS,
                                Identifier.of("minecraft", "caves")));
                if (caveSettings.isPresent()) {
                    NoiseChunkGenerator caveGen = new NoiseChunkGenerator(caveSource, caveSettings.get());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(caveGen, worldSeed));
                }
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: minecraft:caves noise settings not found — falling back to overworld generator", def.getName());
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "nether" -> {
                DimensionOptions source = dimRegistry.get(DimensionOptions.NETHER);
                if (source != null) {
                    ChunkGenerator gen = source.chunkGenerator();
                    // A biome list on a nether dim mixes ANY biome into the
                    // nether's layout (overworld greenery under the roof, end
                    // crystal fields — cross-family is deliberate).
                    BiomeSource mixed = this.resolveListedSource(def, biomeRegistry, gen, overworldOpts.chunkGenerator());
                    if (mixed != null && gen instanceof NoiseChunkGenerator noiseGen) {
                        gen = new NoiseChunkGenerator(mixed, noiseGen.getSettings());
                    }
                    yield new DimensionOptions(this.typeEntryFor(def, source.dimensionTypeEntry()), withSeed(withSettings(gen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "end" -> {
                DimensionOptions source = dimRegistry.get(DimensionOptions.END);
                if (source != null) {
                    ChunkGenerator gen = source.chunkGenerator();
                    BiomeSource mixed = this.resolveListedSource(def, biomeRegistry, gen, overworldOpts.chunkGenerator());
                    if (mixed != null && gen instanceof NoiseChunkGenerator noiseGen) {
                        gen = new NoiseChunkGenerator(mixed, noiseGen.getSettings());
                    }
                    yield new DimensionOptions(this.typeEntryFor(def, source.dimensionTypeEntry()), withSeed(withSettings(gen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "sky_islands" -> {
                // End terrain shape (floating islands); biome list mixes from
                // the full registry (overworld base for natural placements).
                DimensionOptions endOpts = dimRegistry.get(DimensionOptions.END);
                if (endOpts != null && endOpts.chunkGenerator() instanceof NoiseChunkGenerator endGen) {
                    BiomeSource biomeSource = this.resolveListedSource(def, biomeRegistry,
                            overworldOpts.chunkGenerator(), overworldOpts.chunkGenerator());
                    if (biomeSource == null) {
                        biomeSource = overworldOpts.chunkGenerator().getBiomeSource();
                    }
                    NoiseChunkGenerator skyGen = new NoiseChunkGenerator(biomeSource, endGen.getSettings());
                    yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(skyGen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "nether_islands" -> {
                // End terrain shape (floating islands) with the nether's
                // dimension type; biome list mixes from the full registry.
                DimensionOptions endOpts = dimRegistry.get(DimensionOptions.END);
                DimensionOptions netherOpts = dimRegistry.get(DimensionOptions.NETHER);
                if (endOpts != null && netherOpts != null && endOpts.chunkGenerator() instanceof NoiseChunkGenerator endGen) {
                    BiomeSource biomeSource = this.resolveListedSource(def, biomeRegistry,
                            netherOpts.chunkGenerator(), overworldOpts.chunkGenerator());
                    if (biomeSource == null) {
                        biomeSource = netherOpts.chunkGenerator().getBiomeSource();
                    }
                    NoiseChunkGenerator netherSkyGen = new NoiseChunkGenerator(biomeSource, endGen.getSettings());
                    yield new DimensionOptions(this.typeEntryFor(def, netherOpts.dimensionTypeEntry()), withSeed(withSettings(netherSkyGen, settingsOverride), worldSeed));
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "amplified" -> {
                Registry<WorldPreset> presetRegistry = regManager.get(RegistryKeys.WORLD_PRESET);
                WorldPreset preset = presetRegistry.get(WorldPresets.AMPLIFIED);
                if (preset != null) {
                    Optional<DimensionOptions> presetOpts = preset.getOverworld();
                    if (presetOpts.isPresent()) {
                        ChunkGenerator presetGen = presetOpts.get().chunkGenerator();
                        BiomeSource appended = this.resolveAdditiveSource(def, biomeRegistry, presetGen);
                        if (appended != null && presetGen instanceof NoiseChunkGenerator presetNoise) {
                            presetGen = new NoiseChunkGenerator(appended, presetNoise.getSettings());
                        }
                        yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(presetGen, settingsOverride), worldSeed));
                    }
                }
                MultiverseServer.LOGGER.warn("Amplified preset not found, falling back to overworld");
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            case "large_biomes" -> {
                Registry<WorldPreset> presetRegistry = regManager.get(RegistryKeys.WORLD_PRESET);
                WorldPreset preset = presetRegistry.get(WorldPresets.LARGE_BIOMES);
                if (preset != null) {
                    Optional<DimensionOptions> presetOpts = preset.getOverworld();
                    if (presetOpts.isPresent()) {
                        ChunkGenerator presetGen = presetOpts.get().chunkGenerator();
                        BiomeSource appended = this.resolveAdditiveSource(def, biomeRegistry, presetGen);
                        if (appended != null && presetGen instanceof NoiseChunkGenerator presetNoise) {
                            presetGen = new NoiseChunkGenerator(appended, presetNoise.getSettings());
                        }
                        yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(presetGen, settingsOverride), worldSeed));
                    }
                }
                MultiverseServer.LOGGER.warn("Large biomes preset not found, falling back to overworld");
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
            default -> {
                // A type containing ':' clones ANY registered dimension —
                // modded datapack dimensions included (paradise_lost:
                // paradise_lost is a vanilla noise generator with its own
                // noise settings, so per-dimension seeds and biome mixing
                // work exactly like nether/end clones).
                if (type != null && type.contains(":")) {
                    Identifier srcId = Identifier.tryParse(type);
                    DimensionOptions source = srcId == null ? null
                            : dimRegistry.get(RegistryKey.of(RegistryKeys.DIMENSION, srcId));
                    if (source != null) {
                        ChunkGenerator gen = source.chunkGenerator();
                        BiomeSource mixed = this.resolveListedSource(def, biomeRegistry, gen, overworldOpts.chunkGenerator());
                        if (mixed != null && gen instanceof NoiseChunkGenerator noiseGen) {
                            gen = new NoiseChunkGenerator(mixed, noiseGen.getSettings());
                        }
                        yield new DimensionOptions(this.typeEntryFor(def, source.dimensionTypeEntry()), withSeed(withSettings(gen, settingsOverride), worldSeed));
                    }
                    MultiverseServer.LOGGER.warn("Dimension {}: clone source '{}' not registered — falling back to overworld", def.getName(), type);
                }
                yield new DimensionOptions(this.typeEntryFor(def, overworldOpts.dimensionTypeEntry()), withSeed(withSettings(overworldOpts.chunkGenerator(), settingsOverride), worldSeed));
            }
        };
        // Suppression runs BEFORE biomePatches: an author's explicit patch
        // may still stamp a suppressed biome — specific beats general.
        BiomeSuppression.warnUnknownSuppressedBiomes(biomeRegistry);
        // Surface composition runs OUTERMOST, after biomePatches: a patch can
        // stamp a biome the list never named, and a biome that reaches the
        // finished source is one this dimension has to be able to dress.
        return applySurfaceComposition(def, applyBiomePatches(def,
                BiomeSuppression.filterOptions(applySettingsOverrides(def, built), def.getName()),
                biomeRegistry));
    }

    /**
     * Lets a biome from another world wear its own skin here.
     *
     * <p>A biome carries no terrain shape — depth and scale left {@code Biome}
     * in 1.18 — so a nether biome in an overworld dimension already generates
     * with overworld terrain at overworld heights. What does not travel with
     * it is the SURFACE: surface rules belong to the generator, and an
     * overworld generator's rule names only overworld biomes, so a
     * transplanted one takes the fall-through and comes out as grass with
     * nether features standing on it.
     *
     * <p>Foreign is decided per biome against the family whose surface rule
     * this generator is actually carrying, which is
     * {@link BiomeFamilies#surfaceHostFamily} rather than the structure host:
     * the island types borrow the End's whole settings record, and an explicit
     * {@code noiseSettings} replaces it outright. A dimension whose generator
     * belongs to no family is left alone: without a host there is no way to
     * say what is foreign, and guessing would re-skin biomes nobody asked
     * about.
     */
    private DimensionOptions applySurfaceComposition(DimensionConfig def, DimensionOptions built) {
        String hostFamily = BiomeFamilies.surfaceHostFamily(def.getType(), def.getNoiseSettings());
        if (hostFamily == null
                || !(built.chunkGenerator() instanceof NoiseChunkGenerator noiseGen)) {
            return built;
        }
        Map<String, List<RegistryKey<Biome>>> foreign = new java.util.LinkedHashMap<>();
        for (RegistryEntry<Biome> biome : noiseGen.getBiomeSource().getBiomes()) {
            String family = BiomeFamilies.familyOf(biome);
            if (family == null || family.equals(hostFamily)) {
                continue;
            }
            RegistryKey<Biome> key = biome.getKey().orElse(null);
            if (key != null) {
                foreign.computeIfAbsent(family, f -> new ArrayList<>()).add(key);
            }
        }
        ChunkGeneratorSettings base = noiseGen.getSettings().value();
        SurfaceComposition.Result composed = SurfaceComposition.compose(
                base.surfaceRule(), foreign, this::homeSurfaceRule);
        if (!composed.composed()) {
            return built;
        }
        ChunkGeneratorSettings dressed = new ChunkGeneratorSettings(
                base.generationShapeConfig(), base.defaultBlock(), base.defaultFluid(),
                base.noiseRouter(), composed.rule(), base.spawnTarget(), base.seaLevel(),
                base.mobGenerationDisabled(), base.aquifers(), base.oreVeins(),
                base.usesLegacyRandom());
        MultiverseServer.LOGGER.info(
                "Dimension {}: surface composed for biomes from other worlds ({}) — host family {}",
                def.getName(), composed.describe(), hostFamily);
        RegistryEntry<ChunkGeneratorSettings> dressedEntry = this.registerGeneratorSettings(
                generatorSettingsId(def, SURFACE_COMPOSED_SUFFIX), dressed);
        return new DimensionOptions(built.dimensionTypeEntry(),
                new NoiseChunkGenerator(noiseGen.getBiomeSource(), dressedEntry));
    }

    /**
     * A family's own surface rule, from the LIVE settings entry.
     *
     * <p>Live rather than vanilla's original is the whole economy of this:
     * mods that add biomes to a family patch that family's settings
     * themselves — Incendium overrides {@code minecraft:nether}, Nullscape
     * overrides {@code minecraft:end} — so borrowing the rule inherits their
     * surface work and a new mod needs no change here. Null when the settings
     * are absent, which leaves those biomes on the host's fall-through
     * exactly as they are today.
     */
    private MaterialRules.MaterialRule homeSurfaceRule(String family) {
        Identifier id = BiomeFamilies.homeSettings(family);
        if (id == null || this.server == null) {
            return null;
        }
        ChunkGeneratorSettings settings = this.server.getCombinedDynamicRegistries()
                .getCombinedRegistryManager().get(RegistryKeys.CHUNK_GENERATOR_SETTINGS)
                .get(RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, id));
        return settings == null ? null : settings.surfaceRule();
    }

    // "biomePatches" (precision placement): wrap the built generator's biome
    // source so fixed circular patches answer before the generated layout.
    // Runs LAST — after withSeed (the delegate keeps its seeded state) and
    // after settingsOverrides — and composes with every noise-generator
    // type. Flat/void generators warn + no-op (their layout is fixed anyway).
    // Invalid patches are skipped individually (warn); zero valid patches
    // leaves the source untouched.
    private DimensionOptions applyBiomePatches(DimensionConfig def, DimensionOptions built,
                                               Registry<Biome> biomeRegistry) {
        List<DimensionConfig.BiomePatch> configured = def.getBiomePatches();
        if (configured == null || configured.isEmpty()) {
            return built;
        }
        if (!(built.chunkGenerator() instanceof NoiseChunkGenerator noiseGen)) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: biomePatches ignored — type '{}' does not use a noise generator",
                    def.getName(), def.getType());
            return built;
        }
        List<PatchedBiomeSource.Patch> patches = new ArrayList<>();
        for (DimensionConfig.BiomePatch p : configured) {
            Identifier id = p.biome == null ? null : Identifier.tryParse(p.biome.trim().toLowerCase());
            Optional<RegistryEntry.Reference<Biome>> entry = id == null ? Optional.empty()
                    : biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, id));
            String scope = p.scope != null && p.scope.trim().equalsIgnoreCase("global") ? "global" : "clip";
            String replace = p.replace == null || p.replace.isBlank() ? null : p.replace.trim().toLowerCase();
            // Global with an explicit target needs no circle; everything else
            // (stamps, clipped swaps, global selectors) requires one.
            boolean needsCircle = !("global".equals(scope) && replace != null && !"*".equals(replace));
            boolean circleValid = p.x != null && p.z != null
                    && p.radius != null && p.radius >= 1 && p.radius <= 65536;
            int blend = p.blend == null ? PatchedBiomeSource.DEFAULT_BLEND
                    : Math.max(0, Math.min(64, p.blend));
            if (p.blend != null && (p.blend < 0 || p.blend > 64)) {
                MultiverseServer.LOGGER.warn("Dimension {}: biomePatch blend {} outside 0-64 — clamped to {}",
                        def.getName(), p.blend, blend);
            }
            if (entry.isEmpty() || (needsCircle && !circleValid)) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: invalid biomePatch (biome: {}, x: {}, z: {}, radius: {}, scope: {} — "
                        + "need a registered biome, and a 1 <= radius <= 65536 circle unless scope is "
                        + "global with an explicit replace) — patch skipped",
                        def.getName(), p.biome, p.x, p.z, p.radius, scope);
                continue;
            }
            String shape = p.shape != null && p.shape.trim().equalsIgnoreCase("square") ? "square" : "circle";
            patches.add(new PatchedBiomeSource.Patch(entry.get(),
                    p.x != null ? p.x : 0, p.z != null ? p.z : 0,
                    p.radius != null ? p.radius : 1,
                    java.util.Optional.ofNullable(replace), blend, scope, shape));
        }
        if (patches.isEmpty()) {
            return built;
        }
        PatchedBiomeSource patched = new PatchedBiomeSource(noiseGen.getBiomeSource(), patches);
        MultiverseServer.LOGGER.info("Dimension {}: biome source wrapped with {} patch(es) ({})",
                def.getName(), patches.size(), def.getBiomePatchesFingerprint());
        return new DimensionOptions(built.dimensionTypeEntry(),
                new NoiseChunkGenerator(patched, noiseGen.getSettings()));
    }

    // Whitelisted ChunkGeneratorSettings field swaps ("settingsOverrides",
    // Tier 3): clone the built generator's settings record with seaLevel /
    // defaultBlock / defaultFluid / disableMobGeneration replaced. Runs
    // AFTER the type switch so it composes with noiseSettings presets and
    // every noise-generator type. Per-field warn + keep-base on invalid
    // values; flat/void generators warn + no-op (nothing to override).
    private DimensionOptions applySettingsOverrides(DimensionConfig def, DimensionOptions built) {
        DimensionConfig.SettingsOverrides so = def.getSettingsOverrides();
        if (so == null) {
            return built;
        }
        if (!(built.chunkGenerator() instanceof NoiseChunkGenerator noiseGen)) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: settingsOverrides ignored — type '{}' does not use a noise generator",
                    def.getName(), def.getType());
            return built;
        }
        ChunkGeneratorSettings base = noiseGen.getSettings().value();

        int seaLevel = base.seaLevel();
        if (so.seaLevel != null) {
            // Vanilla codec has no explicit bound; keep it inside the widest
            // legal build range so a typo cannot place the sea outside the world.
            if (so.seaLevel >= -2032 && so.seaLevel <= 2031) {
                seaLevel = so.seaLevel;
            } else {
                MultiverseServer.LOGGER.warn("Dimension {}: settingsOverrides.seaLevel {} outside -2032..2031 — keeping {}",
                        def.getName(), so.seaLevel, seaLevel);
            }
        }
        net.minecraft.block.BlockState defaultBlock = resolveOverrideBlock(
                def, "defaultBlock", so.defaultBlock, base.defaultBlock());
        net.minecraft.block.BlockState defaultFluid = resolveOverrideBlock(
                def, "defaultFluid", so.defaultFluid, base.defaultFluid());
        boolean disableMobGen = so.disableMobGeneration != null
                ? so.disableMobGeneration : base.mobGenerationDisabled();

        net.minecraft.world.gen.noise.NoiseRouter router =
                Boolean.FALSE.equals(so.endIsland)
                        ? withoutEndIsland(base.noiseRouter(), def.getName())
                        : base.noiseRouter();

        ChunkGeneratorSettings swapped = new ChunkGeneratorSettings(
                base.generationShapeConfig(), defaultBlock, defaultFluid,
                router, base.surfaceRule(), base.spawnTarget(),
                seaLevel, disableMobGen, base.aquifers(), base.oreVeins(),
                base.usesLegacyRandom());
        MultiverseServer.LOGGER.info("Dimension {}: settingsOverrides applied ({})",
                def.getName(), def.getSettingsOverridesFingerprint());
        RegistryEntry<ChunkGeneratorSettings> swappedEntry = this.registerGeneratorSettings(
                generatorSettingsId(def, SETTINGS_OVERRIDES_SUFFIX), swapped);
        NoiseChunkGenerator swappedGen = new NoiseChunkGenerator(
                noiseGen.getBiomeSource(), swappedEntry);
        return new DimensionOptions(built.dimensionTypeEntry(), swappedGen);
    }

    /**
     * The island term's replacement, as an offset and amplitude on a plain
     * noise.
     *
     * <p>{@code minecraft:end_islands} measures -0.84375 across the open plane
     * and +0.5625 at world origin, where the type special-cases the centre
     * cell. A CONSTANT cannot stand in for it: the End's void ring is the same
     * field reading its floor, so a constant leaves the ring with no island in
     * it — measured at 0 of 24 columns inside 700 blocks. A noise of the same
     * amplitude scatters islands across the whole plane instead, which is what
     * "this dimension never had a centre island" looks like.
     */
    private static final double END_ISLANDS_OFFSET = 0.0;
    private static final double END_ISLANDS_AMPLITUDE = 1.2;
    private static final double END_ISLANDS_XZ_SCALE = 0.5;

    /**
     * The registered codec for {@code minecraft:end_islands}. Yarn declares the
     * type itself protected, so identity on its codec is how a node is
     * recognised — and it stays correct under remapping, which a class-name
     * test would not. Read per call, not into a static: a registry lookup at
     * class-init fails wherever the game is not bootstrapped.
     */
    private static com.mojang.serialization.MapCodec<? extends
            net.minecraft.world.gen.densityfunction.DensityFunction> endIslandsCodec() {
        return Registries.DENSITY_FUNCTION_TYPE.get(Identifier.ofVanilla("end_islands"));
    }

    /**
     * Whether a node is the End island term.
     *
     * <p>A registry-entry wrapper throws {@code UnsupportedOperationException}
     * rather than answering for the function it holds; the visitor recurses
     * through it either way, so refusing to answer is a no, not a failure.
     */
    private static boolean isEndIslands(
            net.minecraft.world.gen.densityfunction.DensityFunction function) {
        try {
            return function.getCodecHolder().codec() == endIslandsCodec();
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * The router with every End island term replaced by the open plane.
     *
     * <p>Rewrites whatever graph the dimension resolved to, so a pack whose End
     * is overhauled keeps its own terrain and loses only the origin bump.
     * A generator carrying no island term is returned unchanged, which is every
     * non-end family.
     */
    private net.minecraft.world.gen.noise.NoiseRouter withoutEndIsland(
            net.minecraft.world.gen.noise.NoiseRouter router, String dimName) {
        final net.minecraft.world.gen.densityfunction.DensityFunction replacement =
                scatteredIslands();
        if (replacement == null) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: cannot remove the End origin island — the noise it would "
                    + "be replaced by is not in the registry", dimName);
            return router;
        }
        final int[] replaced = {0};
        net.minecraft.world.gen.densityfunction.DensityFunction.DensityFunctionVisitor visitor =
                new net.minecraft.world.gen.densityfunction.DensityFunction.DensityFunctionVisitor() {
            @Override
            public net.minecraft.world.gen.densityfunction.DensityFunction apply(
                    net.minecraft.world.gen.densityfunction.DensityFunction function) {
                if (isEndIslands(function)) {
                    replaced[0]++;
                    return replacement;
                }
                return function;
            }

            @Override
            public net.minecraft.world.gen.densityfunction.DensityFunction.Noise apply(
                    net.minecraft.world.gen.densityfunction.DensityFunction.Noise noise) {
                return noise;
            }
        };
        net.minecraft.world.gen.noise.NoiseRouter out = router.apply(visitor);
        if (replaced[0] == 0) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: settingsOverrides.endIsland is false but this generator "
                    + "carries no End island term — nothing to remove", dimName);
            return router;
        }
        MultiverseServer.LOGGER.info(
                "Dimension {}: End origin island removed ({} island term(s) -> scattered noise, "
                + "offset {} amplitude {})",
                dimName, replaced[0], END_ISLANDS_OFFSET, END_ISLANDS_AMPLITUDE);
        return out;
    }

    /**
     * An origin-free stand-in for the End island term: a plain noise scaled to
     * the range that term was measured at. Null when the noise is unavailable.
     */
    private net.minecraft.world.gen.densityfunction.DensityFunction scatteredIslands() {
        if (this.server == null) {
            return null;
        }
        var noiseRegistry = this.server.getRegistryManager().get(RegistryKeys.NOISE_PARAMETERS);
        var params = noiseRegistry.getEntry(
                net.minecraft.world.gen.noise.NoiseParametersKeys.CONTINENTALNESS);
        if (params.isEmpty()) {
            return null;
        }
        return net.minecraft.world.gen.densityfunction.DensityFunctionTypes.add(
                net.minecraft.world.gen.densityfunction.DensityFunctionTypes
                        .constant(END_ISLANDS_OFFSET),
                net.minecraft.world.gen.densityfunction.DensityFunctionTypes.mul(
                        net.minecraft.world.gen.densityfunction.DensityFunctionTypes
                                .constant(END_ISLANDS_AMPLITUDE),
                        net.minecraft.world.gen.densityfunction.DensityFunctionTypes.noise(
                                params.get(), END_ISLANDS_XZ_SCALE, 0.0)));
    }

    private static net.minecraft.block.BlockState resolveOverrideBlock(
            DimensionConfig def, String field, String id, net.minecraft.block.BlockState base) {
        if (id == null || id.isBlank()) {
            return base;
        }
        Identifier ident = Identifier.tryParse(id.trim().toLowerCase());
        Optional<Block> block = ident == null ? Optional.empty() : Registries.BLOCK.getOrEmpty(ident);
        if (block.isEmpty()) {
            MultiverseServer.LOGGER.warn("Dimension {}: settingsOverrides.{} '{}' not in the block registry — keeping the base block",
                    def.getName(), field, id);
            return base;
        }
        return block.get().getDefaultState();
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
        } catch (NoSuchMethodException e) {
            // Expected: most 1.21.1 biome sources no longer expose withSeed.
            // The caller keeps the unseeded generator, which is the design.
            return null;
        } catch (ReflectiveOperationException e) {
            // The method exists and failed. The dimension then generates on
            // the wrong seed with nothing else pointing at why.
            MultiverseServer.LOGGER.warn(
                    "withSeed failed on {} — this dimension generates unseeded",
                    seedable.getClass().getName(), e);
            return null;
        }
    }

    /**
     * The generator and dimension type a config would produce, built without
     * creating (or touching) a ServerWorld. Exists so the seed roller can
     * sample a dimension it has no world for, via the same
     * {@code createDimensionOptions} world creation uses.
     */
    public DimensionOptions buildOptionsHeadless(DimensionConfig def) {
        return this.createDimensionOptions(def);
    }

    /**
     * A ServerWorld that exists only in the server's worlds map, never in the
     * DIMENSION registry.
     *
     * <p>Vanilla encodes that registry into {@code level.dat} on every save,
     * so a world kept out of it leaves nothing behind to scrub — closing it
     * and deleting its region directory is the entire cleanup. Used by the
     * seed try-out, whose worlds are disposable by definition.
     *
     * <p>Must be called from a safe point ({@code END_SERVER_TICK}), never
     * from a request thread or a world tick: it mutates the worlds map and
     * fires {@code ServerWorldEvents.LOAD}, off which Distant Horizons and
     * c2me build their per-level state.
     */
    public ServerWorld createEphemeralWorld(Identifier worldId, DimensionOptions options, long seed) {
        if (this.server == null || options == null) {
            return null;
        }
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, worldId);
        MinecraftServerAccessor serverAccessor = (MinecraftServerAccessor) this.server;
        Map<RegistryKey<World>, ServerWorld> worlds = serverAccessor.getWorlds();
        ServerWorld existing = worlds.get(worldKey);
        if (existing != null) {
            return existing;
        }

        ServerWorld overworld = this.server.getOverworld();
        SaveProperties saveProperties = serverAccessor.getSaveProperties();
        ServerWorldProperties worldProperties = (ServerWorldProperties)
                new UnmodifiableLevelProperties(saveProperties, saveProperties.getMainWorldProperties());

        ServerWorld newWorld = new ServerWorld(
                this.server, serverAccessor.getWorkerExecutor(), serverAccessor.getSession(),
                worldProperties, worldKey, options, NO_OP_WORLD_GEN_PROGRESS,
                false, seed, List.of(), false, overworld.getRandomSequences());

        worlds.put(worldKey, newWorld);
        lastPlayerPresence.put(worldKey, (long) this.server.getTicks());
        ServerWorldEvents.LOAD.invoker().onWorldLoad(this.server, newWorld);
        MultiverseServer.LOGGER.info("Created ephemeral world: {} (seed {})", worldId, seed);
        return newWorld;
    }

    /**
     * Evacuates and closes an ephemeral world. Players go to the overworld
     * spawn first — a player inside a closed world is a guaranteed
     * disconnect, the same reason {@link #processPendingWorldUnloads} does it.
     */
    public boolean closeEphemeralWorld(MinecraftServer server, Identifier worldId) {
        if (server == null) {
            return false;
        }
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, worldId);
        if (PROTECTED_DIMENSIONS.contains(key)) {
            return false;
        }
        Map<RegistryKey<World>, ServerWorld> worlds = ((MinecraftServerAccessor) server).getWorlds();
        ServerWorld world = worlds.get(key);
        if (world == null) {
            return false;
        }
        ServerWorld overworld = server.getOverworld();
        net.minecraft.util.math.BlockPos spawn = overworld.getSpawnPos();
        for (net.minecraft.server.network.ServerPlayerEntity player : new ArrayList<>(world.getPlayers())) {
            player.teleport(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    Set.of(), player.getYaw(), player.getPitch());
        }
        return this.closeWorld(server, key);
    }

    public ServerWorld getOrCreateDimension(String dimName) {
        if (this.server == null) {
            return null;
        }
        DimensionConfig def = MultiverseConfig.getInstance().getCustomDimension(dimName);
        if (def == null) {
            // Command-created dimensions have no config entry — their options
            // are already in the registry (registerDimension), load directly.
            return getOrCreateDimensionDirect(dimName);
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
            // A silent null here makes a failed `customdim load` look queued
            // forever: configured dims only get options from
            // registerDimensions(), which SEED_ROLL_MODE skips at boot, and a
            // config file added after boot was never read at all.
            MultiverseServer.LOGGER.warn(
                    "No DimensionOptions registered for configured dimension {} — "
                    + "SEED_ROLL_MODE skips boot registration (use /customdim create "
                    + "there), and config files added after boot are not read",
                    def.getDimensionIdentifier());
            return null;
        }
        options = ConfiguredBiomeSource.restore(options, def);

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
        lastPlayerPresence.put(worldKey, (long) this.server.getTicks());
        // Fabric contract for dynamic world registration: mods that add a
        // ServerWorld outside createWorlds MUST fire LOAD, or every mod that
        // builds a per-level map from this event (Distant Horizons, c2me)
        // never learns the world exists.
        ServerWorldEvents.LOAD.invoker().onWorldLoad(this.server, newWorld);
        MultiverseServer.LOGGER.info("Created runtime world: {}", worldKey.getValue());
        return newWorld;
    }

    public void registerDimension(DimensionConfig def) {
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
            // Namespace first, then path: another mod's dimension whose PATH
            // happens to match one of our names must never be closed by us.
            if (!MultiverseConfig.getInstance().isManagedNamespace(key.getValue().getNamespace())) {
                continue;
            }
            if (MultiverseConfig.getInstance().getCustomDimension(key.getValue().getPath()) == null) {
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
            if (this.closeWorld(server, key)) {
                MultiverseServer.LOGGER.info("Unloading idle dimension: {} (no players for {} min)", key.getValue(), idleMinutes);
            }
        }
    }

    // Shared teardown for idle unload and delete: save, fire UNLOAD (before
    // close, so listeners can release handles while the world is usable —
    // matches Fabric's own shutdown ordering), close, drop from the map.
    private boolean closeWorld(MinecraftServer server, RegistryKey<World> key) {
        Map<RegistryKey<World>, ServerWorld> worlds = ((MinecraftServerAccessor) server).getWorlds();
        ServerWorld world = worlds.get(key);
        if (world == null) {
            return false;
        }
        try {
            world.save(null, false, false);
            ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, world);
            world.close();
            worlds.remove(key);
            lastPlayerPresence.remove(key);
            return true;
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Failed to save dimension before unload: {}", key.getValue(), e);
            return false;
        }
    }

    public void requestWorldUnload(String name) {
        this.pendingWorldUnloads.add(name);
    }

    public void processPendingWorldUnloads() {
        if (this.pendingWorldUnloads.isEmpty() || this.server == null) {
            return;
        }
        for (String name : new ArrayList<>(this.pendingWorldUnloads)) {
            this.pendingWorldUnloads.remove(name);
            RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, this.identifierFor(name));
            if (PROTECTED_DIMENSIONS.contains(key)) {
                continue;
            }
            Map<RegistryKey<World>, ServerWorld> worlds = ((MinecraftServerAccessor) this.server).getWorlds();
            ServerWorld world = worlds.get(key);
            if (world == null) {
                continue;
            }
            // Evacuate before teardown — a player inside a closed world is a
            // guaranteed desync/disconnect.
            ServerWorld overworld = this.server.getOverworld();
            net.minecraft.util.math.BlockPos spawn = overworld.getSpawnPos();
            for (net.minecraft.server.network.ServerPlayerEntity player : new ArrayList<>(world.getPlayers())) {
                player.teleport(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(), player.getYaw(), player.getPitch());
            }
            if (this.closeWorld(this.server, key)) {
                MultiverseServer.LOGGER.info("Unloaded deleted dimension: {} (world files remain on disk; registry entry clears on next restart)", key.getValue());
            }
        }
    }

    public void requestWorldLoad(String name) {
        // Reserved dimensions queue here too — CreateWorldsMixin defers them
        // exactly like a custom dimension, so the guard must check
        // getReservedDimensionBySlug() as well as getCustomDimension(), or a
        // reserved-dimension load request is silently dropped despite
        // reporting success.
        if (MultiverseConfig.getInstance().getCustomDimension(name) != null
                || MultiverseConfig.getInstance().getReservedDimensionBySlug(name) != null) {
            this.pendingWorldLoads.add(name);
        }
    }

    // Command path: queue a load for a dimension that has no config entry
    // (its options were registered directly by /customdim create).
    public void requestWorldLoadDirect(String name) {
        this.pendingWorldLoads.add(name);
    }

    public ServerWorld getOrCreateDimensionDirect(String dimName) {
        if (this.server == null) {
            return null;
        }
        Identifier dimId = this.identifierFor(dimName);
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, dimId);
        MinecraftServerAccessor serverAccessor = (MinecraftServerAccessor) this.server;
        Map<RegistryKey<World>, ServerWorld> worlds = serverAccessor.getWorlds();

        ServerWorld existing = worlds.get(worldKey);
        if (existing != null) {
            return existing;
        }

        MutableRegistry<DimensionOptions> dimRegistry = this.getDimensionRegistry();
        RegistryKey<DimensionOptions> dimOptionsKey = RegistryKey.of(RegistryKeys.DIMENSION, dimId);
        DimensionOptions options = dimRegistry.get(dimOptionsKey);
        if (options == null) {
            MultiverseServer.LOGGER.error("No dimension options registered for {}", dimId);
            return null;
        }

        options = ConfiguredBiomeSource.restore(options, this.resolveDefinition(dimName));

        ServerWorld overworld = this.server.getOverworld();
        SaveProperties saveProperties = serverAccessor.getSaveProperties();
        ServerWorldProperties worldProperties = (ServerWorldProperties) new UnmodifiableLevelProperties(saveProperties, saveProperties.getMainWorldProperties());
        DimensionConfig runtimeDef = this.runtimeDefinitions.get(dimName);
        // A reserved dimension's seed comes from its own config file, exactly
        // as ServerWorldSeedMixin serves it — the constructor seed builds the
        // NoiseConfig, so handing it the overworld's would generate the
        // wrong nether while getSeed() reported the right one.
        Long reservedSeed = MultiverseConfig.getInstance().getWorldSeedOverride(dimId.toString());
        long worldSeed = runtimeDef != null && runtimeDef.getSeed() != null
                ? runtimeDef.getSeed()
                : (reservedSeed != null ? reservedSeed : overworld.getSeed());

        ServerWorld newWorld = new ServerWorld(
                this.server, serverAccessor.getWorkerExecutor(), serverAccessor.getSession(),
                worldProperties, worldKey, options, NO_OP_WORLD_GEN_PROGRESS,
                false, worldSeed, List.of(), false, overworld.getRandomSequences());

        worlds.put(worldKey, newWorld);
        lastPlayerPresence.put(worldKey, (long) this.server.getTicks());
        ServerWorldEvents.LOAD.invoker().onWorldLoad(this.server, newWorld);
        MultiverseServer.LOGGER.info("Created runtime world (direct): {}", worldKey.getValue());
        return newWorld;
    }

    /**
     * Creating a ServerWorld is a main-thread job costing several hundred
     * milliseconds: it mutates the server's worlds map and fires
     * {@code ServerWorldEvents.LOAD}, off the back of which Distant Horizons
     * and c2me build their per-level state. Draining every queued world in
     * one go would make a player walking towards a cluster of portals pay
     * for all of them on one tick and rubber-band; one per tick spreads the
     * same work over N ticks instead.
     */
    private static final int WORLD_LOADS_PER_TICK = 1;

    public void processPendingWorldLoads() {
        if (this.pendingWorldLoads.isEmpty()) {
            return;
        }
        int created = 0;
        for (String name : new ArrayList<>(this.pendingWorldLoads)) {
            if (created >= WORLD_LOADS_PER_TICK) {
                break;
            }
            this.pendingWorldLoads.remove(name);
            this.getOrCreateDimension(name);
            created++;
        }
        if (!this.pendingWorldLoads.isEmpty()) {
            // Counts, not events: a queue that never drains looks identical
            // to one that drains instantly without this line.
            MultiverseServer.LOGGER.debug(
                    "dimension load queue: created {}, {} still pending",
                    created, this.pendingWorldLoads.size());
        }
    }

    public void bootCreateDimensions() {
        for (DimensionConfig def : MultiverseConfig.getInstance().getCustomDimensions()) {
            this.requestWorldLoad(def.getName());
        }
    }

    public void reconcileOrphansOnce() {
        if (this.bootReconciled || this.server == null) {
            return;
        }
        this.bootReconciled = true;
        Map<RegistryKey<World>, ServerWorld> worlds = ((MinecraftServerAccessor) this.server).getWorlds();
        List<String> configNames = MultiverseConfig.getInstance().getDimensionNames();
        for (RegistryKey<World> key : worlds.keySet()) {
            if (PROTECTED_DIMENSIONS.contains(key)) {
                continue;
            }
            if (!MultiverseConfig.getInstance().isManagedNamespace(key.getValue().getNamespace())) {
                continue;
            }
            String path = key.getValue().getPath();
            if (!configNames.contains(path)) {
                MultiverseServer.LOGGER.info("Orphan dimension detected: {} — queuing unload", key.getValue());
                this.requestWorldUnload(path);
            }
        }
    }

    public boolean dimensionExists(String name) {
        return MultiverseConfig.getInstance().getCustomDimension(name) != null;
    }

    // Config first, then runtime (command-created) definitions.
    public DimensionConfig resolveDefinition(String name) {
        DimensionConfig def = MultiverseConfig.getInstance().getCustomDimension(name);
        return def != null ? def : this.runtimeDefinitions.get(name);
    }

    public void rememberRuntimeDefinition(DimensionConfig def) {
        this.runtimeDefinitions.put(def.getName(), def);
    }

    // Identifier for a dimension slug: the config's own namespace when a
    // definition exists (consumer-added dims may live under BRAND_SLUG),
    // otherwise the platform namespace.
    // Public so command handlers can resolve a slug the same way the loader
    // does — a second copy of this namespace fallback would drift.
    public Identifier identifierFor(String name) {
        DimensionConfig def = this.resolveDefinition(name);
        if (def != null) {
            return def.getDimensionIdentifier();
        }
        // Reserved dimensions keep their vanilla ids. Without this,
        // "the_nether" resolved to {namespace}:the_nether and the lazy-load
        // path could never reach minecraft:the_nether — which matters because
        // CreateWorldsMixin defers EVERY non-overworld world, reserved
        // dimensions included, so nothing else was ever going to create them.
        DimensionConfig world = MultiverseConfig.getInstance().getReservedDimensionBySlug(name);
        if (world != null) {
            return world.getDimensionIdentifier();
        }
        return Identifier.of(MultiverseConfig.getInstance().getNamespace(), name);
    }

    public void forgetRuntimeDefinition(String name) {
        this.runtimeDefinitions.remove(name);
    }

    public MinecraftServer getServer() {
        return this.server;
    }
}
