package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.mixin.StructurePlacementAccessor;
import com.customdimensions.mixin.StructurePlacementCalculatorInvoker;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-dimension structure control ("structureDensity" in
 * multiverse_config.json: dense | normal | sparse | none), plus the automatic
 * peaceful overlay for hostileSpawning:false dimensions.
 *
 * Applied by rebuilding the world's StructurePlacementCalculator with a
 * transformed structure-set list (ServerChunkLoadingManagerMixin). Placement
 * copies are unregistered direct entries scoped to this one world — the
 * global registry objects are never mutated, so every other dimension keeps
 * the shared placements.
 *
 * Caveats honoured (customising-structures.md):
 * - Only exact minecraft:random_spread placements are rescaled. Custom
 *   placement types (YUNG's, Moog's) and concentric rings pass through
 *   unchanged — dropping whole sets is type-agnostic and still applies.
 * - Theme knowledge comes from the jar-baked structure_themes.json; sets
 *   without a theme are only affected by "none".
 */
public final class DimensionStructures {

    private DimensionStructures() {
    }

    /**
     * Returns null when the world needs no transformed calculator. The biome
     * source is passed explicitly — this runs inside the chunk manager's
     * constructor, before world.getChunkManager() is assigned.
     */
    public static StructurePlacementCalculator transformed(ServerWorld world, BiomeSource biomeSource,
            NoiseConfig noiseConfig, StructurePlacementCalculator original) {
        Identifier key = world.getRegistryKey().getValue();
        DimensionConfig def;
        if (MultiverseConfig.getInstance().isManagedNamespace(key.getNamespace())) {
            def = com.customdimensions.dimension.DimensionManager.getInstance()
                    .resolveDefinition(key.getPath());
        } else {
            // Base worlds (minecraft:overworld/the_nether/the_end,
            // paradise_lost:paradise_lost) resolve by EXACT dimension id.
            // They are absent from the managed-namespace set on purpose —
            // those namespaces hold other mods' dimensions and the lookup
            // above is by path.
            def = MultiverseConfig.getInstance().getBaseWorld(key.toString());
        }
        if (def == null) {
            return null;
        }
        // Terrain-adaptation overrides apply to EVERY managed world —
        // including ones whose calculator this method leaves untouched
        // (returning null below): the Beardifier runs in the noise phase and
        // covers pass-throughs and vanilla-grid sets alike.
        installTerrainAdaptation(world, def, original);
        installForcedStarts(world, def);
        String density = normalizedDensity(def);
        boolean peaceful = !def.isHostileSpawningEnabled();
        DimensionConfig.Structures structBlock = def.getStructures();
        java.util.Map<String, DimensionConfig.SpacingOverride> spacingOverrides =
                structBlock != null && structBlock.spacing != null
                        ? structBlock.spacing : java.util.Map.of();
        String mode = normalizedMode(def.getName(), structBlock);
        java.util.Set<String> modeList = structBlock != null && structBlock.list != null
                ? new java.util.HashSet<>(structBlock.list) : java.util.Set.of();
        java.util.List<DimensionConfig.ForcedStructure> forced =
                structBlock != null && structBlock.force != null
                        ? structBlock.force : java.util.List.of();

        // Noise is the DEFAULT path. It fully replaces the organic grid sets
        // for every group it covers, so the density/theme rescaling below is
        // only reached when noise is suppressed (structureDensity "none",
        // structures.mode "none", structures.noise false, a world type with
        // no groups, or every group resolving to none).
        NoiseGroupPlan.warnUnknownGroups(def);
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(def);
        // The global suppress list (settings.json suppress.structures, the
        // consumer overlay merged in) joins the dimension's own exclude —
        // one union applied at every filter point: the pool builder, the
        // pass-through loop, and the legacy path below.
        java.util.List<String> suppressed =
                MultiverseConfig.getInstance().getSuppressedStructureSets();
        warnUnknownSuppressedSets(world, suppressed);
        java.util.Set<String> exclude = new java.util.HashSet<>(NoisePoolBuilder.lowerSet(
                structBlock != null ? structBlock.exclude : null));
        exclude.addAll(NoisePoolBuilder.lowerSet(suppressed));
        if (!plan.isSuppressed()) {
            return transformedNoise(world, biomeSource, noiseConfig, original, def, plan,
                    spacingOverrides, forced, mode, modeList, exclude);
        }

        // "Type enables no groups" means NO organic structures — returning
        // null here kept the vanilla calculator intact and generated all
        // 367 sets on vanilla grids in void/superflat dimensions.
        boolean dropAll = plan.suppressesAllSets();

        if (!dropAll && "normal".equals(density) && !peaceful && spacingOverrides.isEmpty()
                && !def.hasExitShrines() && mode == null && forced.isEmpty()) {
            return null;
        }
        List<RegistryEntry<StructureSet>> transformed = new ArrayList<>();
        int dropped = 0;
        int rescaled = 0;
        for (RegistryEntry<StructureSet> entry : original.getStructureSets()) {
            if ("none".equals(density)) {
                dropped++;
                continue;
            }
            String setId = entry.getKey().map(k -> k.getValue().toString()).orElse(null);
            String theme = setId != null ? StructureThemes.themeOf(setId) : null;

            // Exit shrines ship with a near-zero frequency so they can never
            // generate in worlds that bypass this rebuild (base worlds).
            // Opted-in dims get the full-frequency copy; everyone else keeps
            // the effectively-off original. Exempt from every theme factor.
            if ("adventure:exit_shrines".equals(setId)) {
                if (def.hasExitShrines()) {
                    StructureSet set = entry.value();
                    // ships at frequency 0.001; x1000 -> min(1.0, ...) = full
                    StructurePlacement full = rescale(set.placement(), 1.0, 1000.0);
                    if (full != null) {
                        // Explicit structures.spacing wins; otherwise the
                        // spacing derives from the playable border — a
                        // 256-radius pocket wants 1-2 shrines, not a grid.
                        // MIRRORED in scripts/seed/fast_roller.py (tier 1);
                        // change both together or shrine scoring lies.
                        DimensionConfig.SpacingOverride ov = spacingOverrides.get(setId);
                        if (ov == null) {
                            ov = derivedShrineSpacing(def.getPlayerBorderRadius());
                        }
                        StructurePlacement spaced = withExplicitSpacing(def, setId, full, ov);
                        if (spaced != null) {
                            full = spaced;
                        }
                        transformed.add(RegistryEntry.of(new StructureSet(set.structures(), full)));
                        rescaled++;
                        continue;
                    }
                }
                transformed.add(entry);
                continue;
            }

            // The type enables no groups (void/superflat): every organic set
            // is dropped. Exit shrines were handled above; forced placements
            // append after the loop.
            if (dropAll) {
                dropped++;
                continue;
            }

            // Organic-set filter (structures.mode + the exclude union, which
            // carries the global suppress list): applied after the
            // exit-shrines opt-in (which is config-driven, not organic) and
            // before every density/theme path. Forced placements are
            // synthetic sets appended after the loop — mode never touches
            // them ("mode": "none" + force = ONLY the forced structures).
            if (!keepSet(setId, mode, modeList, exclude)) {
                dropped++;
                continue;
            }

            if (peaceful && "dungeon".equals(theme)) {
                dropped++;
                continue;
            }

            // Explicit per-set override (structures.spacing) wins over the
            // theme-based density factors — but never resurrects a set the
            // peaceful/none drops above already removed.
            DimensionConfig.SpacingOverride override = setId != null ? spacingOverrides.get(setId) : null;
            if (override != null) {
                StructureSet set = entry.value();
                StructurePlacement explicit = withExplicitSpacing(def, setId, set.placement(), override);
                if (explicit != null) {
                    transformed.add(RegistryEntry.of(new StructureSet(set.structures(), explicit)));
                    rescaled++;
                    continue;
                }
                // invalid values or custom placement type: warned inside,
                // fall through to the normal theme path
            }

            double spacingFactor = 1.0;
            double frequencyFactor = 1.0;
            if ("dense".equals(density)) {
                if ("dungeon".equals(theme) || "loot".equals(theme)) {
                    spacingFactor = 0.7; // ~2x density
                } else if ("landmark".equals(theme) || "maritime".equals(theme)) {
                    spacingFactor = 0.85;
                }
            } else if ("sparse".equals(density)) {
                if ("dungeon".equals(theme) || "loot".equals(theme) || "landmark".equals(theme)) {
                    frequencyFactor = 0.5;
                } else if ("settlement".equals(theme) || "maritime".equals(theme)) {
                    frequencyFactor = 0.7;
                }
            }
            if (peaceful && ("settlement".equals(theme) || "maritime".equals(theme)
                    || "landmark".equals(theme) || "loot".equals(theme))) {
                // Rare villages, ships and fun stuff — nothing to fight.
                frequencyFactor *= 0.3;
            }

            if (spacingFactor == 1.0 && frequencyFactor == 1.0) {
                transformed.add(entry);
                continue;
            }
            StructureSet set = entry.value();
            StructurePlacement scaled = rescale(set.placement(), spacingFactor, frequencyFactor);
            if (scaled == null) {
                transformed.add(entry); // custom placement type — caveat: pass through
                continue;
            }
            transformed.add(RegistryEntry.of(new StructureSet(set.structures(), scaled)));
            rescaled++;
        }

        int forcedCount = appendForcedPlacements(transformed, world, def, forced);

        MultiverseServer.LOGGER.info(
                "Dimension {} structure profile: density={}{}{}{}{} ({} sets kept, {} rescaled, {} dropped)",
                def.getName(), density, peaceful ? "+peaceful" : "",
                mode != null ? "+mode=" + mode : "",
                dropAll ? "+suppressed(" + plan.reason() + ")" : "",
                forcedCount > 0 ? "+" + forcedCount + " forced" : "",
                transformed.size(), rescaled, dropped);
        return StructurePlacementCalculatorInvoker.invokeNew(
                noiseConfig, biomeSource, original.getStructureSeed(), original.getStructureSeed(), transformed);
    }

    /**
     * The noise path: dissolve the organic grid sets into biome-filtered
     * groups and give each group one {@link NoiseStructurePlacement}.
     *
     * Sets that survive as themselves: exit shrines (infrastructure, own
     * derived spacing) and custom placement types (YUNG's, concentric rings —
     * dissolving them would silently discard rules we do not model). Every
     * other random_spread set is replaced by its group's placement, so a
     * structure never has two chances to generate.
     */
    private static StructurePlacementCalculator transformedNoise(
            ServerWorld world, BiomeSource biomeSource, NoiseConfig noiseConfig,
            StructurePlacementCalculator original, DimensionConfig def, NoiseGroupPlan plan,
            java.util.Map<String, DimensionConfig.SpacingOverride> spacingOverrides,
            java.util.List<DimensionConfig.ForcedStructure> forced,
            String mode, java.util.Set<String> modeList, java.util.Set<String> exclude) {

        long started = System.nanoTime();
        List<RegistryEntry<StructureSet>> transformed = new ArrayList<>();

        // Exit shrines keep their existing opt-in treatment — they are not
        // adventure content and belong to no group.
        int passthrough = 0;
        for (RegistryEntry<StructureSet> entry : original.getStructureSets()) {
            String setId = entry.getKey().map(k -> k.getValue().toString()).orElse(null);
            if (!"adventure:exit_shrines".equals(setId)) {
                continue;
            }
            RegistryEntry<StructureSet> shrines = exitShrineSet(def, entry, spacingOverrides);
            if (shrines != null) {
                transformed.add(shrines);
                passthrough++;
            }
        }

        // A wanted structure whose SET vanilla's own StructurePlacementCalculator
        // prefilter dropped is invisible to the pool builder — the set is not in
        // `original.getStructureSets()` at all, so `structures.include` cannot
        // reach it either. Re-admit those sets from the full registry, then let
        // the pool builder bypass the affinity filter for the wanted structures
        // inside them.
        java.util.Set<String> wanted = NoisePoolBuilder.wantedStructureIds(def);
        Iterable<RegistryEntry<StructureSet>> poolSets = withWantedSets(
                world, original.getStructureSets(), wanted, def.getName());

        NoisePoolBuilder.Result pools = NoisePoolBuilder.build(
                def, poolSets, biomeSource, plan, exclude, null, wanted);
        // Which structures a group can draw from is decided HERE, from the
        // dimension's biome source against each structure's own biome list, so
        // the seed roller cannot derive it. Recording it is what lets the roller
        // ask "is there a Village" rather than only "is there a settlement" —
        // see StructurePoolRecord for why 167 shuns were unsatisfiable without
        // it. Free: the pools are already built. The registry lookup lives here
        // rather than in the record so the record stays Bootstrap-free.
        java.util.Map<String, java.util.List<StructurePoolRecord.Entry>> poolIds =
                new java.util.LinkedHashMap<>();
        pools.pools().forEach((group, pool) -> {
            java.util.List<StructurePoolRecord.Entry> ids = new ArrayList<>();
            for (var weighted : pool.entries()) {
                weighted.structure().getKey().ifPresent(key -> ids.add(
                        new StructurePoolRecord.Entry(
                                key.getValue().toString(), weighted.weight())));
            }
            poolIds.put(group, ids);
        });
        StructurePoolRecord.record(def.getName(), poolIds);

        // Custom placement types pass through untouched: their rules are not
        // ours to reinterpret, and dropping them would silently delete every
        // YUNG's structure from every managed dimension. They never reach
        // NoisePoolBuilder, so the dimension's set-id filters (structures.mode
        // and structures.exclude) must be applied HERE or they silently never
        // touch pass-throughs.
        int passthroughFiltered = 0;
        for (RegistryEntry<StructureSet> entry : original.getStructureSets()) {
            String setId = entry.getKey().map(k -> k.getValue().toString()).orElse(null);
            if ("adventure:exit_shrines".equals(setId)) {
                continue;
            }
            if (!NoisePoolBuilder.noiseManaged(entry.value().placement())) {
                if (!keepSet(setId, mode, modeList, exclude)) {
                    passthroughFiltered++;
                    continue;
                }
                transformed.add(entry);
                passthrough++;
            }
        }

        long worldSeed = world.getSeed();
        int radiusChunks = def.getPlayerBorderRadius() / 16;
        long dimensionSalt = saltOf(def.getName());
        int groupsBuilt = 0;
        int totalPositions = 0;
        StringBuilder detail = new StringBuilder();
        String worldId = world.getRegistryKey().getValue().toString();

        // Selection registry: maps each WeightedEntry instance to its group's
        // pick parameters so NoiseStructureSelectionMixin can assign the exact
        // structure at generation time. Keyed by OBJECT IDENTITY.
        java.util.IdentityHashMap<StructureSet.WeightedEntry, StructurePick.GroupSelection>
                selectionRegistry = new java.util.IdentityHashMap<>();

        for (var groupEntry : plan.groups().entrySet()) {
            String group = groupEntry.getKey();
            NoiseGroupPlan.Group settings = groupEntry.getValue();
            NoisePoolBuilder.Pool pool = pools.pools().get(group);
            if (pool == null || pool.entries().isEmpty()) {
                // Normal, not an error: a jungle dimension has no maritime
                // structures whose biomes it contains.
                continue;
            }
            long noiseSeed = worldSeed ^ dimensionSalt ^ saltOf(group);
            NoiseStructurePlacement placement = new NoiseStructurePlacement(
                    group, noiseSeed, settings.profile(), settings.exclusion(),
                    settings.radial(), radiusChunks, 0, 0);

            // Build the sorted pool for this group's pick algorithm.
            java.util.List<StructurePick.PoolEntry> pickPool = new ArrayList<>();
            for (var weighted : pool.entries()) {
                weighted.structure().getKey().ifPresent(key -> pickPool.add(
                        new StructurePick.PoolEntry(
                                key.getValue().toString(), weighted.weight())));
            }
            java.util.List<StructurePick.PoolEntry> sorted = StructurePick.sortedPool(pickPool);
            StructurePick.GroupSelection sel = new StructurePick.GroupSelection(
                    group, noiseSeed, sorted, placement.index());

            // Register every WeightedEntry in this group's set so the mixin
            // can look them up by object identity.
            for (var weighted : pool.entries()) {
                selectionRegistry.put(weighted, sel);
            }

            transformed.add(RegistryEntry.of(
                    new StructureSet(pool.entries(), placement)));
            groupsBuilt++;
            totalPositions += placement.index().size();
            if (placement.index().size() == 0) {
                // The pool has structures but the field produced nowhere to
                // put them — a sparse profile plus a narrow radial curve in a
                // small world can miss entirely. Silent would look identical
                // to "this group has no structures here", which is normal.
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: group {} has {} eligible structures but the {} "
                        + "profile placed NONE in a {}-chunk radius — widen the radial "
                        + "curve or use a denser profile",
                        def.getName(), group, pool.entries().size(),
                        settings.profile().id(), radiusChunks);
            }
            detail.append(' ').append(group).append('=').append(settings.profile().id())
                    .append('/').append(placement.index().size());
            if (pool.biomeFiltered() > 0) {
                detail.append("(-").append(pool.biomeFiltered()).append("biome)");
            }
        }

        // Install the selection registry — replaced wholesale on every
        // calculator rebuild, cleared on ServerWorldEvents.UNLOAD.
        StructurePick.install(worldId, selectionRegistry);

        int forcedCount = appendForcedPlacements(transformed, world, def, forced);
        long millis = (System.nanoTime() - started) / 1_000_000;

        MultiverseServer.LOGGER.info(
                "Dimension {} structure profile: noise radius={}c groups={}/{} positions={}"
                + "{}{} ({} sets passed through, {} pass-through filtered, {} custom-placement, {}ms)",
                def.getName(), radiusChunks, groupsBuilt, plan.groups().size(), totalPositions,
                detail, forcedCount > 0 ? " +" + forcedCount + " forced" : "",
                passthrough, passthroughFiltered, pools.setsSkippedCustomPlacement(), millis);
        if (millis > 200) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: noise placement took {}ms to precompute (radius {} chunks). "
                    + "This runs once per world load, but a smaller borders.player would cut it.",
                    def.getName(), millis, radiusChunks);
        }

        return StructurePlacementCalculatorInvoker.invokeNew(
                noiseConfig, biomeSource, original.getStructureSeed(),
                original.getStructureSeed(), transformed);
    }

    /**
     * The calculator's set list plus any set from the FULL registry that holds
     * a wanted structure and is not already there.
     *
     * <p>Vanilla's {@code StructurePlacementCalculator.create} drops a set
     * whose structures' valid biomes miss the dimension's biome source. That is
     * correct for organic generation and wrong for a want, which is the author
     * saying "put one here anyway". Only sets carrying a wanted structure are
     * re-admitted, and only the wanted structures inside them bypass the
     * affinity filter — the rest of such a set is still filtered normally.
     *
     * <p>Returns the original list unchanged when nothing needs re-admitting,
     * which is every dimension whose wants already fit its biomes.
     */
    private static Iterable<RegistryEntry<StructureSet>> withWantedSets(
            ServerWorld world, Iterable<RegistryEntry<StructureSet>> present,
            java.util.Set<String> wanted, String dimensionName) {
        if (wanted.isEmpty()) {
            return present;
        }
        java.util.Set<String> haveIds = new java.util.HashSet<>();
        java.util.Set<String> haveSets = new java.util.HashSet<>();
        for (RegistryEntry<StructureSet> entry : present) {
            entry.getKey().ifPresent(k -> haveSets.add(k.getValue().toString()));
            for (StructureSet.WeightedEntry weighted : entry.value().structures()) {
                weighted.structure().getKey()
                        .ifPresent(k -> haveIds.add(k.getValue().toString()));
            }
        }
        java.util.List<String> missing = new ArrayList<>();
        for (String id : wanted) {
            if (!haveIds.contains(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return present;
        }
        var registry = world.getRegistryManager()
                .get(net.minecraft.registry.RegistryKeys.STRUCTURE_SET);
        List<RegistryEntry<StructureSet>> combined = new ArrayList<>();
        present.forEach(combined::add);
        java.util.List<String> readmitted = new ArrayList<>();
        for (var setEntry : registry.getEntrySet()) {
            String setId = setEntry.getKey().getValue().toString();
            if (haveSets.contains(setId)) {
                continue;
            }
            boolean carriesWanted = false;
            for (StructureSet.WeightedEntry weighted : setEntry.getValue().structures()) {
                String id = weighted.structure().getKey()
                        .map(k -> k.getValue().toString()).orElse(null);
                if (id != null && missing.contains(id)) {
                    carriesWanted = true;
                    break;
                }
            }
            if (carriesWanted) {
                combined.add(registry.getEntry(setEntry.getKey()).orElseThrow());
                readmitted.add(setId);
            }
        }
        if (!readmitted.isEmpty()) {
            MultiverseServer.LOGGER.info(
                    "Dimension {}: re-admitted {} structure set(s) for wanted structures "
                    + "the biome prefilter had dropped: {}",
                    dimensionName, readmitted.size(), readmitted);
        } else {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: wanted structure(s) {} are in no registered structure "
                    + "set — nothing can place them (check the id, or the mod that "
                    + "provides them)", dimensionName, missing);
        }
        return combined;
    }

    /**
     * Per-dimension and per-group noise salt. A plain String.hashCode would
     * give neighbouring names neighbouring salts (and it is only 32 bits), so
     * the noise fields of two similarly-named dimensions would correlate.
     */
    static long saltOf(String name) {
        if (name == null) {
            return 0L;
        }
        long h = 0xCBF29CE484222325L;   // FNV-1a 64
        for (int i = 0; i < name.length(); i++) {
            h ^= name.charAt(i);
            h *= 0x100000001B3L;
        }
        return StructureNoise.mix64(h);
    }

    /**
     * The exit-shrine set, opted in at full frequency with derived spacing,
     * or the effectively-off original. Extracted so the noise path and the
     * legacy density path share one definition.
     */
    private static RegistryEntry<StructureSet> exitShrineSet(
            DimensionConfig def, RegistryEntry<StructureSet> entry,
            java.util.Map<String, DimensionConfig.SpacingOverride> spacingOverrides) {
        if (!def.hasExitShrines()) {
            return entry;
        }
        StructureSet set = entry.value();
        StructurePlacement full = rescale(set.placement(), 1.0, 1000.0);
        if (full == null) {
            return entry;
        }
        DimensionConfig.SpacingOverride ov = spacingOverrides.get("adventure:exit_shrines");
        if (ov == null) {
            ov = derivedShrineSpacing(def.getPlayerBorderRadius());
        }
        StructurePlacement spaced = withExplicitSpacing(def, "adventure:exit_shrines", full, ov);
        return RegistryEntry.of(new StructureSet(
                set.structures(), spaced != null ? spaced : full));
    }

    /**
     * Fixed placements (structures.force): one synthetic single-structure set
     * per forced structure id, positioned by FixedStructurePlacement. Unknown
     * structure ids (e.g. from a removed mod) warn and skip — never a boot
     * break (optional-mods promise). Returns how many positions were added.
     *
     * Neither the structure's biome predicate nor other mods' start cancels
     * apply at a forced position — see ForcedStartOverride. This line says
     * what was configured; a second INFO line is logged when a forced
     * position actually generates.
     */
    private static int appendForcedPlacements(List<RegistryEntry<StructureSet>> transformed,
                                              ServerWorld world, DimensionConfig def,
                                              java.util.List<DimensionConfig.ForcedStructure> forced) {
        if (forced.isEmpty()) {
            return 0;
        }
        int forcedCount = 0;
        var structureRegistry = world.getRegistryManager()
                .get(net.minecraft.registry.RegistryKeys.STRUCTURE);
        java.util.Map<Identifier, java.util.List<net.minecraft.util.math.ChunkPos>> byStructure =
                new java.util.LinkedHashMap<>();
        for (DimensionConfig.ForcedStructure f : forced) {
            if (f == null || f.structure == null || f.x == null || f.z == null) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: structures.force entry missing structure/x/z — skipped",
                        def.getName());
                continue;
            }
            Identifier sid = Identifier.tryParse(f.structure);
            if (sid == null) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: structures.force id '{}' is not a valid identifier — skipped",
                        def.getName(), f.structure);
                continue;
            }
            int border = def.getPlayerBorderRadius();
            if (border > 0 && (Math.abs(f.x) > border || Math.abs(f.z) > border)) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: structures.force {} at ({}, {}) lies outside the playable "
                        + "border (radius {}) — it generates only if chunks ever generate there",
                        def.getName(), f.structure, f.x, f.z, border);
            }
            byStructure.computeIfAbsent(sid, k -> new java.util.ArrayList<>())
                    .add(new net.minecraft.util.math.ChunkPos(f.x >> 4, f.z >> 4));
        }
        for (var e : byStructure.entrySet()) {
            var entry = structureRegistry.getEntry(
                    net.minecraft.registry.RegistryKey.of(
                            net.minecraft.registry.RegistryKeys.STRUCTURE, e.getKey()));
            if (entry.isEmpty()) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: forced structure {} not in the registry (mod removed?) — skipped",
                        def.getName(), e.getKey());
                continue;
            }
            transformed.add(RegistryEntry.of(new StructureSet(
                    entry.get(), new FixedStructurePlacement(def.getName(), e.getValue()))));
            forcedCount += e.getValue().size();
            MultiverseServer.LOGGER.info(
                    "Dimension {}: forced {} at chunk(s) {}",
                    def.getName(), e.getKey(), e.getValue());
        }
        return forcedCount;
    }

    /**
     * Installs the world's forced-start registry entries —
     * {@code ChunkGeneratorForcedStartMixin} consults them at the head of
     * every start attempt and performs forced ones itself, ahead of the
     * structure's biome predicate and other mods' start cancels. Runs for
     * every managed world, empty configs included, so a dimension that drops
     * its forces also drops its registry entries.
     */
    private static void installForcedStarts(ServerWorld world, DimensionConfig def) {
        DimensionConfig.Structures block = def.getStructures();
        java.util.List<DimensionConfig.ForcedStructure> forced =
                block != null && block.force != null ? block.force : java.util.List.of();
        java.util.List<ForcedStartOverride.ForcedEntry> entries = new ArrayList<>();
        for (DimensionConfig.ForcedStructure f : forced) {
            if (f == null || f.structure == null || f.x == null || f.z == null) {
                continue;   // appendForcedPlacements owns the malformed-entry warning
            }
            Identifier sid = Identifier.tryParse(f.structure);
            if (sid == null) {
                continue;
            }
            entries.add(new ForcedStartOverride.ForcedEntry(sid.toString(),
                    net.minecraft.util.math.ChunkPos.toLong(f.x >> 4, f.z >> 4)));
        }
        ForcedStartOverride.install(world.getRegistryKey().getValue().toString(),
                def.getName(), ForcedStartOverride.byChunk(entries));
    }

    private static volatile boolean warnedSuppressList;

    /**
     * WARNs, once per boot, about suppress.structures ids that exist in no
     * registered structure set — a typo would otherwise silently suppress
     * nothing. Checked against the FULL structure-set registry, not the
     * world's calculator list (that one is biome-prefiltered per dimension
     * and would false-warn about sets the current dimension merely lacks).
     */
    private static void warnUnknownSuppressedSets(ServerWorld world,
                                                  java.util.List<String> suppressed) {
        if (warnedSuppressList || suppressed.isEmpty()) {
            return;
        }
        warnedSuppressList = true;
        var registry = world.getRegistryManager()
                .get(net.minecraft.registry.RegistryKeys.STRUCTURE_SET);
        for (String id : suppressed) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null || registry.get(parsed) == null) {
                MultiverseServer.LOGGER.warn(
                        "settings.json suppress.structures id '{}' matches no registered "
                        + "structure set — a typo suppresses nothing (see "
                        + "extractors/structures.json for valid set ids)", id);
            }
        }
    }

    /**
     * Whether an organic set survives the dimension's set-id filters:
     * structures.exclude, then structures.mode allow/reject over
     * structures.list. Shared by the legacy path's mode filter (empty
     * exclude — legacy semantics unchanged) and the noise path's
     * pass-through loop. The global suppress list (settings.json, planned)
     * plugs in here. Exclude entries are pre-lowercased
     * (NoisePoolBuilder.lowerSet); mode list entries match exactly.
     */
    static boolean keepSet(String setId, String mode, java.util.Set<String> modeList,
                           java.util.Set<String> exclude) {
        if (setId != null && exclude.contains(setId.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        if (mode == null) {
            return true;
        }
        return switch (mode) {
            case "allow" -> setId != null && modeList.contains(setId);
            case "reject" -> setId == null || !modeList.contains(setId);
            default -> false; // "none"
        };
    }

    /**
     * Builds and installs the dimension's resolved Beardifier map: for every
     * structure reachable through the world's structure sets, resolve
     * per-structure config -> group config -> theme default (fills registry
     * "none" only) -> registry value, and record only the entries that
     * DIFFER. Keyed by registry-singleton identity so the armed per-chunk
     * lookup is one IdentityHashMap get. See TerrainAdaptationOverride.
     */
    private static void installTerrainAdaptation(ServerWorld world, DimensionConfig def,
                                                 StructurePlacementCalculator original) {
        DimensionConfig.Structures block = def.getStructures();
        java.util.Map<String, String> config =
                block != null && block.terrainAdaptation != null
                        ? block.terrainAdaptation : java.util.Map.of();
        java.util.Map<String, String> themes = StructureGroupRegistry.terrainAdaptationDefaults();
        Identifier worldId = world.getRegistryKey().getValue();
        if (config.isEmpty() && themes.isEmpty()) {
            TerrainAdaptationOverride.install(worldId, java.util.Map.of());
            return;
        }
        java.util.IdentityHashMap<net.minecraft.world.gen.structure.Structure,
                net.minecraft.world.gen.StructureTerrainAdaptation> map =
                new java.util.IdentityHashMap<>();
        java.util.IdentityHashMap<net.minecraft.world.gen.structure.Structure,
                TerrainKernel> kernels = new java.util.IdentityHashMap<>();
        java.util.Set<net.minecraft.world.gen.structure.Structure> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (RegistryEntry<StructureSet> entry : original.getStructureSets()) {
            String setId = entry.getKey().map(k -> k.getValue().toString()).orElse(null);
            StructurePlacement placement = entry.value().placement();
            int spacing = placement instanceof RandomSpreadStructurePlacement random
                    ? random.getSpacing() : -1;
            String group = StructureGroupRegistry.classify(setId, spacing).group();
            for (StructureSet.WeightedEntry weighted : entry.value().structures()) {
                net.minecraft.world.gen.structure.Structure structure = weighted.structure().value();
                String structureId = weighted.structure().getKey()
                        .map(k -> k.getValue().toString()).orElse(null);
                seen.add(structure);
                resolveAdaptationInto(map, kernels, config, themes, structure,
                        structureId, group, def.getName());
            }
        }

        // Forced structures may have no set in this world's calculator — their
        // organic set can be biome-prefiltered away, or belong to another
        // family entirely. Resolve them from the FULL registries so beards and
        // kernels apply to forced placements like any other structure.
        java.util.List<DimensionConfig.ForcedStructure> forcedList =
                block != null && block.force != null ? block.force : java.util.List.of();
        if (!forcedList.isEmpty()) {
            var structureRegistry = world.getRegistryManager()
                    .get(net.minecraft.registry.RegistryKeys.STRUCTURE);
            var setRegistry = world.getRegistryManager()
                    .get(net.minecraft.registry.RegistryKeys.STRUCTURE_SET);
            for (DimensionConfig.ForcedStructure f : forcedList) {
                Identifier sid = f != null && f.structure != null
                        ? Identifier.tryParse(f.structure) : null;
                if (sid == null) {
                    continue;
                }
                net.minecraft.world.gen.structure.Structure structure =
                        structureRegistry.get(sid);
                if (structure == null || !seen.add(structure)) {
                    continue;
                }
                resolveAdaptationInto(map, kernels, config, themes, structure,
                        sid.toString(), forcedGroupOf(setRegistry, structure),
                        def.getName());
            }
        }
        TerrainAdaptationOverride.install(worldId, map, kernels);
        if (!map.isEmpty() || !kernels.isEmpty()) {
            MultiverseServer.LOGGER.info(
                    "Dimension {}: terrain adaptation overridden for {} structure(s)"
                    + "{}",
                    def.getName(), map.size(),
                    kernels.isEmpty() ? ""
                            : " (+" + kernels.size() + " custom kernel(s))");
        }
    }

    /**
     * One structure's adaptation resolution: per-structure config -> group
     * config -> theme default (fills registry "none" only) -> registry value.
     * Records only entries that DIFFER; a kernel name parses to NONE for
     * vanilla and carries its real shape in the kernel map.
     */
    private static void resolveAdaptationInto(
            java.util.IdentityHashMap<net.minecraft.world.gen.structure.Structure,
                    net.minecraft.world.gen.StructureTerrainAdaptation> map,
            java.util.IdentityHashMap<net.minecraft.world.gen.structure.Structure,
                    TerrainKernel> kernels,
            java.util.Map<String, String> config, java.util.Map<String, String> themes,
            net.minecraft.world.gen.structure.Structure structure, String structureId,
            String group, String dimName) {
        net.minecraft.world.gen.StructureTerrainAdaptation vanilla;
        try {
            vanilla = structure.getTerrainAdaptation();
        } catch (RuntimeException e) {
            return;   // a broken structure is not ours to fail on
        }
        String resolvedName = TerrainAdaptationOverride.resolveName(
                config, structureId, group, themes, vanilla.asString());
        if (resolvedName == null) {
            return;
        }
        var resolved = TerrainAdaptationOverride.parse(resolvedName,
                "dimension " + dimName + ", structure " + structureId);
        if (resolved != null && resolved != vanilla) {
            map.put(structure, resolved);
        }
        TerrainKernel kernel = TerrainKernel.parse(resolvedName);
        if (kernel != null) {
            kernels.put(structure, kernel);
        }
    }

    /**
     * The group a forced structure's own set classifies to, resolved from the
     * FULL structure-set registry — the world's calculator list is
     * biome-prefiltered and can lack the set entirely. Null when the
     * structure appears in no registered set (per-structure config and the
     * registry adaptation still apply).
     */
    private static String forcedGroupOf(
            net.minecraft.registry.Registry<StructureSet> setRegistry,
            net.minecraft.world.gen.structure.Structure structure) {
        for (var setEntry : setRegistry.getEntrySet()) {
            for (StructureSet.WeightedEntry weighted : setEntry.getValue().structures()) {
                if (weighted.structure().value() == structure) {
                    String setId = setEntry.getKey().getValue().toString();
                    StructurePlacement placement = setEntry.getValue().placement();
                    int spacing = placement instanceof RandomSpreadStructurePlacement random
                            ? random.getSpacing() : -1;
                    return StructureGroupRegistry.classify(setId, spacing).group();
                }
            }
        }
        return null;
    }

    /** Validated structures.mode: allow | reject | none, or null (off).
     *  Package-private for unit tests (same pattern as derivedShrineSpacing). */
    static String normalizedMode(String dimName, DimensionConfig.Structures block) {
        if (block == null || block.mode == null || block.mode.isEmpty()) {
            return null;
        }
        String mode = block.mode.toLowerCase();
        switch (mode) {
            case "allow":
            case "reject":
            case "none":
                return mode;
            default:
                MultiverseServer.LOGGER.warn(
                        "Unknown structures.mode '{}' on dimension {} — ignoring the filter",
                        block.mode, dimName);
                return null;
        }
    }

    private static String normalizedDensity(DimensionConfig def) {
        String density = def.getStructureDensity();
        if (density == null || density.isEmpty()) {
            return "normal";
        }
        String normalized = density.toLowerCase();
        switch (normalized) {
            case "dense":
            case "normal":
            case "sparse":
            case "none":
                return normalized;
            default:
                MultiverseServer.LOGGER.warn(
                        "Unknown structureDensity '{}' on dimension {} — using normal",
                        density, def.getName());
                return "normal";
        }
    }

    // Explicit spacing/separation for one set (structures.spacing override).
    /**
     * Automatic shrine spacing from the playable border: roughly
     * radius-in-chunks / 2, clamped 12..48. Pure — unit-tested, and
     * mirrored bit-for-bit in scripts/seed/fast_roller.py (roller parity).
     */
    static DimensionConfig.SpacingOverride derivedShrineSpacing(int playerBorderRadiusBlocks) {
        DimensionConfig.SpacingOverride out = new DimensionConfig.SpacingOverride();
        out.spacing = Math.max(12, Math.min(48, playerBorderRadiusBlocks / 32));
        out.separation = out.spacing / 2;
        return out;
    }

    // Same random_spread-only constraint as rescale; invariants enforced
    // (spacing >= 2, 0 <= separation < spacing — vanilla's codec is strict
    // about separation < spacing, violating it crashes placement).
    private static StructurePlacement withExplicitSpacing(DimensionConfig def, String setId,
            StructurePlacement placement, DimensionConfig.SpacingOverride override) {
        if (placement.getClass() != RandomSpreadStructurePlacement.class) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: structures.spacing for {} ignored — custom placement type {}",
                    def.getName(), setId, placement.getClass().getSimpleName());
            return null;
        }
        RandomSpreadStructurePlacement random = (RandomSpreadStructurePlacement) placement;
        StructurePlacementAccessor base = (StructurePlacementAccessor) placement;
        int spacing = override.spacing != null ? override.spacing : random.getSpacing();
        int separation = override.separation != null ? override.separation : random.getSeparation();
        if (spacing < 2 || spacing > 4096 || separation < 0 || separation >= spacing) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: structures.spacing for {} invalid (spacing: {}, separation: {} — "
                    + "need 2 <= spacing <= 4096, 0 <= separation < spacing) — using the theme-based path",
                    def.getName(), setId, spacing, separation);
            return null;
        }
        return new RandomSpreadStructurePlacement(
                base.getLocateOffsetField(),
                base.getFrequencyReductionMethodField(),
                base.getFrequencyField(),
                base.getSaltField(),
                base.getExclusionZoneField(),
                spacing,
                separation,
                random.getSpreadType());
    }

    // Only exact random_spread placements can be rescaled generically; any
    // subclass (custom placement type) returns null and passes through.
    private static StructurePlacement rescale(StructurePlacement placement,
            double spacingFactor, double frequencyFactor) {
        if (placement.getClass() != RandomSpreadStructurePlacement.class) {
            return null;
        }
        RandomSpreadStructurePlacement random = (RandomSpreadStructurePlacement) placement;
        StructurePlacementAccessor base = (StructurePlacementAccessor) placement;
        int spacing = Math.max(2, (int) Math.round(random.getSpacing() * spacingFactor));
        int separation = Math.min(spacing - 1,
                Math.max(0, (int) Math.round(random.getSeparation() * spacingFactor)));
        float frequency = (float) Math.min(1.0, base.getFrequencyField() * frequencyFactor);
        return new RandomSpreadStructurePlacement(
                base.getLocateOffsetField(),
                base.getFrequencyReductionMethodField(),
                frequency,
                base.getSaltField(),
                base.getExclusionZoneField(),
                spacing,
                separation,
                random.getSpreadType());
    }
}
