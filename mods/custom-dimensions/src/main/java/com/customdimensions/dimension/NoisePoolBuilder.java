package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sorts the world's structure sets into noise groups, dropping anything that
 * cannot generate in this dimension's biomes and weighting what remains by
 * rarity and biome affinity.
 *
 * The biome filter is what makes zero-config work: a jungle {@code
 * multi_biome} dimension gets jungle temples and not igloos because the
 * structures' own biome predicates say so, with nothing named in the config.
 */
public final class NoisePoolBuilder {

    /** One group's structures, plus a note of what was dropped and why. */
    public record Pool(String group,
                       List<StructureSet.WeightedEntry> entries,
                       int biomeFiltered,
                       int excluded) {
    }

    /** Everything the builder produced, for the boot log and the census. */
    public record Result(Map<String, Pool> pools,
                         int setsConsidered,
                         int setsSkippedCustomPlacement,
                         Set<String> forcedExclusive) {
    }

    private NoisePoolBuilder() {
    }

    /**
     * The placement type ids noise placement absorbs, beyond the exact
     * vanilla class. Moog's advanced_random_spread's getStartChunk is
     * byte-identical vanilla maths; "advanced" adds only an optional
     * origin-clearance disc (superseded by our radial curves and
     * clearSpawnRadius) and an optional cross-set exclusion no shipped set
     * uses. YUNG's types and Supplementaries' galleons stay pass-throughs —
     * their cross-set exclusion zones are real behaviour — and
     * concentric_rings is not grid-compatible at all.
     *
     * MIRRORED as NOISE_MANAGED_PLACEMENT_TYPES in
     * scripts/seed/structure_placement.py (consumed by
     * score-dimensions.structure_group_lookup) — the two lists and
     * structure-groups.json move together, in the same release, or the
     * roller scores a different world than the mod generates (T20).
     */
    private static final Set<String> ABSORBED_PLACEMENT_TYPES = Set.of(
            "moogs_structures:advanced_random_spread");

    /**
     * Every noise-managed placement-type id, sorted — stamped into the
     * structure_pools.json dump so the roller can spot a dump made under a
     * different absorption list (a stale pre-conversion dump would score a
     * newly absorbed set 0.0% forever, the exact T23-adjacent bug class).
     */
    public static java.util.List<String> noiseManagedTypeIds() {
        java.util.List<String> out = new ArrayList<>(ABSORBED_PLACEMENT_TYPES);
        out.add("minecraft:random_spread");
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Whether a set's placement is dissolved into a noise group. Exact
     * vanilla random_spread always; listed subclasses by their REGISTERED
     * placement-type id (never by class name — no compile dependency on the
     * owning mod). Any other subclass (including our own
     * FixedStructurePlacement) and every non-random_spread type passes
     * through on its own placement.
     */
    static boolean noiseManaged(StructurePlacement placement) {
        if (placement.getClass() == RandomSpreadStructurePlacement.class) {
            return true;
        }
        if (!(placement instanceof RandomSpreadStructurePlacement)
                || placement instanceof FixedStructurePlacement) {
            return false;
        }
        Identifier type = net.minecraft.registry.Registries.STRUCTURE_PLACEMENT
                .getId(placement.getType());
        return type != null && ABSORBED_PLACEMENT_TYPES.contains(type.toString());
    }

    public static Result build(DimensionConfig def,
                               Iterable<RegistryEntry<StructureSet>> sets,
                               BiomeSource biomeSource,
                               NoiseGroupPlan plan) {
        DimensionConfig.Structures block = def.getStructures();
        return build(def, sets, biomeSource, plan,
                lowerSet(block == null ? null : block.exclude));
    }

    /**
     * As above with an explicit exclude union — the dimension's own
     * {@code structures.exclude} plus the global settings.json suppress
     * list, pre-lowercased (DimensionStructures builds it once and applies
     * the same set to the pass-through loop).
     */
    public static Result build(DimensionConfig def,
                               Iterable<RegistryEntry<StructureSet>> sets,
                               BiomeSource biomeSource,
                               NoiseGroupPlan plan,
                               Set<String> exclude) {
        DimensionConfig.Structures block = def.getStructures();
        Set<String> include = lowerSet(block == null ? null : block.include);
        Map<String, String> rarityOverrides = block == null || block.rarity == null
                ? Map.of() : block.rarity;
        Set<String> forcedExclusive = forcedExclusiveStructureIds(def);
        Set<Identifier> dimensionBiomes = biomeIds(biomeSource);

        Map<String, List<StructureSet.WeightedEntry>> byGroup = new LinkedHashMap<>();
        Map<String, int[]> counters = new LinkedHashMap<>();   // [biomeFiltered, excluded]
        int considered = 0;
        int customPlacement = 0;

        for (RegistryEntry<StructureSet> setEntry : sets) {
            String setId = setEntry.getKey().map(k -> k.getValue().toString()).orElse(null);
            if (setId == null) {
                continue;
            }
            considered++;

            // Exit shrines are infrastructure with their own derived spacing,
            // never adventure content — DimensionStructures handles them.
            if ("adventure:exit_shrines".equals(setId)) {
                continue;
            }

            StructureSet set = setEntry.value();
            StructurePlacement placement = set.placement();
            if (!noiseManaged(placement)) {
                customPlacement++;
                continue;
            }
            int spacing = ((RandomSpreadStructurePlacement) placement).getSpacing();

            String rarity = rarityOverrides.get(setId);
            var classification = StructureGroupRegistry.classify(setId, spacing);
            if (rarity == null) {
                rarity = classification.rarity();
            }
            String group = groupFor(setId, classification, rarity, rarityOverrides);
            if (group == null || !plan.groups().containsKey(group)) {
                continue;   // group not active for this dimension
            }

            int[] counter = counters.computeIfAbsent(group, k -> new int[2]);
            if (exclude.contains(setId.toLowerCase(Locale.ROOT))) {
                counter[1]++;
                continue;
            }

            boolean bypassBiomeFilter = include.contains(setId.toLowerCase(Locale.ROOT));
            double share = StructureGroupRegistry.rarityShare(rarity);

            for (StructureSet.WeightedEntry weighted : set.structures()) {
                RegistryEntry<Structure> structure = weighted.structure();
                String structureId = structure.getKey().map(k -> k.getValue().toString()).orElse(null);
                if (structureId != null && forcedExclusive.contains(structureId)) {
                    counter[1]++;
                    continue;   // placed by hand, and nowhere else
                }
                double affinity = biomeAffinity(structure, dimensionBiomes);
                if (affinity <= 0.0 && !bypassBiomeFilter) {
                    counter[0]++;
                    continue;   // cannot generate in any of this dim's biomes
                }
                // Structures that belong to MORE of this dimension's biomes
                // weigh more, so generation leans towards what fits.
                double affinityFactor = bypassBiomeFilter ? 1.0 : 0.5 + 0.5 * affinity;
                int weight = (int) Math.max(1, Math.round(
                        weighted.weight() * share * affinityFactor));
                byGroup.computeIfAbsent(group, k -> new ArrayList<>())
                        .add(new StructureSet.WeightedEntry(structure, weight));
            }
        }

        Map<String, Pool> pools = new LinkedHashMap<>();
        for (var e : byGroup.entrySet()) {
            int[] counter = counters.getOrDefault(e.getKey(), new int[2]);
            pools.put(e.getKey(), new Pool(e.getKey(), List.copyOf(e.getValue()),
                    counter[0], counter[1]));
        }
        return new Result(Map.copyOf(pools), considered, customPlacement, forcedExclusive);
    }

    /**
     * A rarity override can move a set between groups: the endgame group
     * requires a rare-or-rarer tier, so promoting a set into `rare` promotes
     * it into `endgame` if its name says it belongs there, and demoting one
     * out of `rare` drops it back to its theme's group.
     *
     * MIRRORED from group_for() in scripts/gen-structure-groups.py.
     */
    private static String groupFor(String setId, StructureGroupRegistry.Entry classification,
                                   String rarity, Map<String, String> rarityOverrides) {
        String group = classification.group();
        if (!rarityOverrides.containsKey(setId)) {
            return group;
        }
        boolean endgameTier = "rare".equals(rarity) || "endgame".equals(rarity);
        if ("endgame".equals(group) && !endgameTier) {
            // Demoted out of the endgame tier — fall back to its theme group.
            String fromTheme = StructureThemes.groupForTheme(classification.theme());
            return fromTheme != null ? fromTheme : "deco";
        }
        return group;
    }

    /**
     * Fraction of a structure's valid biomes that this dimension actually
     * has. 0.0 means the structure can never generate here.
     */
    static double biomeAffinity(RegistryEntry<Structure> structure, Set<Identifier> dimensionBiomes) {
        if (dimensionBiomes.isEmpty()) {
            return 1.0;   // unknown biome source: filter nothing
        }
        RegistryEntryList<Biome> valid;
        try {
            valid = structure.value().getValidBiomes();
        } catch (Exception e) {
            return 1.0;   // a broken structure is not ours to fail on
        }
        int total = 0;
        int matched = 0;
        for (RegistryEntry<Biome> biome : valid) {
            total++;
            Identifier id = biome.getKey().map(k -> k.getValue()).orElse(null);
            if (id != null && dimensionBiomes.contains(id)) {
                matched++;
            }
        }
        if (total == 0) {
            return 1.0;   // no predicate at all: generates anywhere
        }
        return matched / (double) total;
    }

    /** Biome ids the dimension's source can produce. Empty if undeterminable. */
    static Set<Identifier> biomeIds(BiomeSource biomeSource) {
        Set<Identifier> out = new HashSet<>();
        if (biomeSource == null) {
            return out;
        }
        try {
            for (RegistryEntry<Biome> biome : biomeSource.getBiomes()) {
                biome.getKey().ifPresent(k -> out.add(k.getValue()));
            }
        } catch (Exception e) {
            MultiverseServer.LOGGER.warn(
                    "Could not enumerate biomes for structure filtering — filter disabled", e);
            return Set.of();
        }
        return out;
    }

    /**
     * Structure ids placed by hand with {@code exclusive} left at its default
     * (true). Those are removed from the noise pool, so "put exactly this
     * here" also means "and nowhere else".
     */
    static Set<String> forcedExclusiveStructureIds(DimensionConfig def) {
        DimensionConfig.Structures block = def.getStructures();
        if (block == null || block.force == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (DimensionConfig.ForcedStructure forced : block.force) {
            if (forced != null && forced.structure != null && forced.isExclusive()) {
                out.add(forced.structure);
            }
        }
        return out;
    }

    /**
     * Lowercased, null-safe set. Shared with DimensionStructures' filters and
     * with lint, which must assemble the same exclude union the live build
     * uses or it reports a pool the world will not have.
     */
    public static Set<String> lowerSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String v : values) {
            if (v != null) {
                out.add(v.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
