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

    /**
     * Everything the builder produced, for the boot log and the census.
     *
     * <p>{@code wanted} and {@code shunned} are the resolved id sets this build
     * weighted with. They ride on the result because a weight is a rounded int
     * that cannot be read backwards: a diagnostic asking why a structure has
     * the weight it has, and every {@code StructurePick.PoolEntry} carrying the
     * flags, needs the same answer this build used rather than a second lookup.
     */
    public record Result(Map<String, Pool> pools,
                         int setsConsidered,
                         int setsSkippedCustomPlacement,
                         Set<String> forcedExclusive,
                         Set<String> wanted,
                         Set<String> shunned) {
    }

    private NoisePoolBuilder() {
    }

    /**
     * The placement type ids noise placement absorbs, beyond the exact
     * vanilla class. Every one is a RandomSpreadStructurePlacement subclass
     * whose getStartChunk is vanilla maths; what each adds on top is a
     * cross-set exclusion zone, which our per-group exclusion radius covers
     * at group scope instead of tag scope.
     *
     * Absorbing is what puts a set under structureDensity, group profiles,
     * radial curves, rarity tiers and the difficulty shifts, and into the
     * scorecard. A pass-through has none of that — only an on/off switch.
     *
     * Two stay out. betterstrongholds:stronghold is ring placement, and the
     * rings are how a player finds the End. concentric_rings is not
     * grid-compatible at all.
     */
    private static final Set<String> ABSORBED_PLACEMENT_TYPES = Set.of(
            "moogs_structures:advanced_random_spread",
            "yungsapi:enhanced_random_spread",
            "betterjungletemples:jungle_temple",
            "betterdeserttemples:desert_temple");

    /**
     * Whether a set's placement is dissolved into a noise group. Exact
     * vanilla random_spread always; listed subclasses by their REGISTERED
     * placement-type id (never by class name — no compile dependency on the
     * owning mod). Any other subclass (including our own
     * FixedStructurePlacement) and every non-random_spread type passes
     * through on its own placement.
     */
    /**
     * Group name for a set that keeps its own grid instead of joining a pool.
     * A pool splits one fixed site budget between its members, so a set meant
     * to be everywhere cannot be expressed in one at any weight (T55).
     * Curated, never derived from spacing — spacing is an input we rescale.
     */
    public static final String UBIQUITOUS_GROUP = "ubiquitous";

    /** What a want multiplies a pool weight by, and what a shun divides it by. */
    public static final double WANT_WEIGHT_FACTOR = 1.2;
    public static final double SHUN_WEIGHT_DIVISOR = 1.5;

    /**
     * Units of pool weight one weight is carried as. A want is 6/5 of a weight
     * and a shun 2/3 of one; at 15 units both are whole numbers, so the factors
     * above apply at weight 1 exactly as they do at weight 160. Rounding a 1.2
     * into an integer weight instead would discard the want on every weight-1
     * structure, which is the rare and endgame tiers — the ones wants name.
     */
    public static final int WEIGHT_RESOLUTION = 15;

    /** {@link #WEIGHT_RESOLUTION} through each factor. Both are exact. */
    private static final int WANTED_UNITS = 18;
    private static final int SHUNNED_UNITS = 10;

    public static boolean ubiquitous(String setId) {
        if (setId == null) {
            return false;
        }
        StructureThemes.Classification c = StructureThemes.classificationOf(setId);
        return c != null && UBIQUITOUS_GROUP.equals(c.group());
    }

    public static boolean noiseManaged(String setId, StructurePlacement placement) {
        return !ubiquitous(setId) && noiseManaged(placement);
    }

    public static boolean noiseManaged(StructurePlacement placement) {
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
        return build(def, sets, biomeSource, plan, exclude, null);
    }

    /**
     * As above with the include list supplied rather than read from config.
     *
     * <p>Only lint passes one: it builds the pool twice, with and without
     * {@code structures.include}, to tell a want that genuinely fits the
     * dimension's biomes from one that is only in the pool because the filter
     * was bypassed. Null means "use the config's", which is every other caller.
     */
    public static Result build(DimensionConfig def,
                               Iterable<RegistryEntry<StructureSet>> sets,
                               BiomeSource biomeSource,
                               NoiseGroupPlan plan,
                               Set<String> exclude,
                               List<String> includeOverride) {
        return build(def, sets, biomeSource, plan, exclude, includeOverride,
                wantedStructureIds(def));
    }

    /**
     * The structure ids this dimension's config asks for, resolved through the
     * alias table. These bypass the biome-affinity filter: a want is an
     * instruction, not a wish.
     */
    public static Set<String> wantedStructureIds(DimensionConfig def) {
        Set<String> out = new HashSet<>();
        for (String name : StructureWants.resolve(def).names()) {
            String id = StructureAliases.resolve(name);
            if (id != null && !id.startsWith("#")) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * The structure ids this dimension's config discourages, resolved through
     * the alias table. The mirror of {@link #wantedStructureIds}: it lowers a
     * pool weight instead of raising one, and both drop a name that resolves
     * to a {@code #tag}, which is a set of structures rather than one.
     */
    public static Set<String> shunnedStructureIds(DimensionConfig def) {
        Set<String> out = new HashSet<>();
        for (String name : StructureWants.shunNames(def)) {
            String id = StructureAliases.resolve(name);
            if (id != null && !id.startsWith("#")) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * A weight in pool units, with the author's want or shun applied.
     *
     * <p>Every weight is scaled by the same {@link #WEIGHT_RESOLUTION}, so no
     * unfavoured structure's share of the draw moves — the nine heaviest keep
     * exactly the third of the overworld pool they hold today. What the
     * resolution buys is somewhere for the factors to land: 18/15 and 10/15 are
     * exactly 1.2x and a 1.5x reduction at every weight, including 1.
     *
     * <p>A shun cannot reach 0: discouraging is not removing, and removing is
     * what {@code structures.exclude} is for. Naming a structure in both wants
     * and shuns cancels, because neither instruction can be honoured over the
     * other; {@code DimensionLint.checkWantShunConflict} reports it.
     */
    public static int favourWeight(int weight, boolean wanted, boolean shunned) {
        int units = wanted == shunned ? WEIGHT_RESOLUTION
                : wanted ? WANTED_UNITS : SHUNNED_UNITS;
        return Math.max(1, weight) * units;
    }

    /**
     * Whether this dimension asked for a structure that its biomes would
     * otherwise have filtered out — {@code structures.include} names the SET,
     * {@code structures.wants} names the STRUCTURE, and either admits it at
     * full weight.
     *
     * <p>The one definition of "legitimately bypassed". {@link #build} decides
     * a pool entry with it, and a diagnostic asking whether an out-of-biome
     * site is a bug or a request must call the same predicate — the two
     * disagreeing is exactly the failure a second copy would produce.
     */
    public static boolean admittedDespiteBiomes(Set<String> includeSets, Set<String> wantedIds,
                                                String setId, String structureId) {
        return (setId != null && includeSets.contains(setId.toLowerCase(Locale.ROOT)))
                || (structureId != null && wantedIds.contains(structureId));
    }

    /**
     * Every structure id this dimension admits despite its biomes, resolved
     * across the structure-set registry so a caller holding only a structure
     * id can answer the question {@link #admittedDespiteBiomes} asks per entry.
     */
    public static Set<String> admittedStructureIds(
            DimensionConfig def, Iterable<RegistryEntry<StructureSet>> sets) {
        DimensionConfig.Structures block = def.getStructures();
        Set<String> include = lowerSet(block == null ? null : block.include);
        Set<String> wanted = wantedStructureIds(def);
        Set<String> out = new HashSet<>(wanted);
        for (RegistryEntry<StructureSet> setEntry : sets) {
            String setId = setEntry.getKey().map(k -> k.getValue().toString()).orElse(null);
            for (StructureSet.WeightedEntry weighted : setEntry.value().structures()) {
                String structureId = weighted.structure().getKey()
                        .map(k -> k.getValue().toString()).orElse(null);
                if (structureId != null
                        && admittedDespiteBiomes(include, wanted, setId, structureId)) {
                    out.add(structureId);
                }
            }
        }
        return out;
    }

    /**
     * As above with the wanted set supplied.
     *
     * <p>{@code wanted} bypasses the biome-affinity filter exactly as
     * {@code structures.include} does, but per STRUCTURE rather than per set —
     * which is the granularity a want is written at. The structure then reaches
     * {@link StructurePick} and, at its assigned site,
     * {@code NoiseStructureSelectionMixin} creates its start with the biome
     * predicate bypassed. That last step already existed; this is the wire that
     * was missing in front of it.
     *
     * <p>What it does NOT do is guarantee the structure generates: its own
     * {@code createStructureStart} can still decline the position, and that
     * rejection is recorded in {@code census/rejections__*.json}. "Wanted" buys
     * a place in the pool and a chance at every site the noise assigns it, not
     * a promise the terrain will accept it.
     */
    public static Result build(DimensionConfig def,
                               Iterable<RegistryEntry<StructureSet>> sets,
                               BiomeSource biomeSource,
                               NoiseGroupPlan plan,
                               Set<String> exclude,
                               List<String> includeOverride,
                               Set<String> wanted) {
        DimensionConfig.Structures block = def.getStructures();
        Set<String> include = lowerSet(includeOverride != null ? includeOverride
                : (block == null ? null : block.include));
        Set<String> wantedIds = wanted == null ? Set.of() : wanted;
        Set<String> shunnedIds = shunnedStructureIds(def);
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
            if (!noiseManaged(setId, placement)) {
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

            double share = StructureGroupRegistry.rarityShare(rarity);

            for (StructureSet.WeightedEntry weighted : set.structures()) {
                RegistryEntry<Structure> structure = weighted.structure();
                String structureId = structure.getKey().map(k -> k.getValue().toString()).orElse(null);
                if (structureId != null && forcedExclusive.contains(structureId)) {
                    counter[1]++;
                    continue;   // placed by hand, and nowhere else
                }
                boolean admitted = admittedDespiteBiomes(include, wantedIds, setId, structureId);
                double affinity = biomeAffinity(structure, dimensionBiomes);
                if (affinity <= 0.0 && !admitted) {
                    counter[0]++;
                    continue;   // cannot generate in any of this dim's biomes
                }
                // Structures that belong to MORE of this dimension's biomes
                // weigh more, so generation leans towards what fits. A wanted
                // structure keeps full weight: the author asked for it, so it
                // must not be quietly out-competed by whatever happens to fit.
                double affinityFactor = admitted ? 1.0 : 0.5 + 0.5 * affinity;
                int weight = favourWeight(
                        (int) Math.max(1, Math.round(
                                weighted.weight() * share * affinityFactor)),
                        structureId != null && wantedIds.contains(structureId),
                        structureId != null && shunnedIds.contains(structureId));
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
        return new Result(Map.copyOf(pools), considered, customPlacement, forcedExclusive,
                Set.copyOf(wantedIds), Set.copyOf(shunnedIds));
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
    public static double biomeAffinity(RegistryEntry<Structure> structure, Set<Identifier> dimensionBiomes) {
        if (dimensionBiomes.isEmpty()) {
            return 1.0;   // unknown biome source: filter nothing
        }
        RegistryEntryList<Biome> valid;
        try {
            valid = structure.value().getValidBiomes();
        } catch (Exception e) {
            return 1.0;   // a broken structure is not ours to fail on
        }
        java.util.List<Identifier> validIds = new ArrayList<>();
        for (RegistryEntry<Biome> biome : valid) {
            validIds.add(biome.getKey().map(k -> k.getValue()).orElse(null));
        }
        return affinityOf(validIds, dimensionBiomes);
    }

    /**
     * The affinity arithmetic, without a registry. A null id counts towards the
     * total and matches nothing, so an unresolvable biome dilutes rather than
     * disappears.
     */
    static double affinityOf(java.util.List<Identifier> validBiomeIds,
                             Set<Identifier> dimensionBiomes) {
        if (validBiomeIds.isEmpty()) {
            // contains() over an empty list is false everywhere: nowhere, not
            // anywhere. Matches intersectsBiomes.
            return 0.0;
        }
        int matched = 0;
        for (Identifier id : validBiomeIds) {
            if (id != null && dimensionBiomes.contains(id)) {
                matched++;
            }
        }
        return matched / (double) validBiomeIds.size();
    }

    /**
     * Whether a set survives vanilla's biome prefilter — at least one of its
     * structures can generate in one of this dimension's biomes — or is
     * re-admitted because it carries a wanted structure.
     *
     * <p>{@code StructurePlacementCalculator.create} drops such a set BEFORE
     * this builder ever sees it, so a live world's pool is built from a
     * prefiltered list. Anything reproducing a live pool headlessly must apply
     * this first: skipping it yields a strict superset whose extra structures
     * take probability mass a live world would never give them. Positions are
     * unaffected; the weighted PICK is not.
     *
     * <p>One definition, called by the facts engine and by the census — a
     * second copy would drift and the drift would look like a working pool.
     */
    public static boolean survivesVanillaPrefilter(RegistryEntry<StructureSet> entry,
                                                   Set<Identifier> dimensionBiomes,
                                                   Set<String> wanted) {
        for (StructureSet.WeightedEntry weighted : entry.value().structures()) {
            String id = weighted.structure().getKey()
                    .map(k -> k.getValue().toString()).orElse(null);
            if (id != null && wanted.contains(id)) {
                return true;
            }
            if (intersectsBiomes(weighted.structure(), dimensionBiomes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vanilla's own prefilter test: does this structure list a biome the source
     * produces?
     *
     * <p>NOT {@code biomeAffinity > 0}. Affinity is a ratio for weighting a
     * structure already in a pool; this is vanilla's {@code anyMatch} over the
     * list. They agree that an empty list matches nothing — five installed
     * structures have one — but a partial match weighs differently from a
     * hit, so neither substitutes for the other.
     */
    public static boolean intersectsBiomes(RegistryEntry<Structure> structure,
                                           Set<Identifier> dimensionBiomes) {
        if (dimensionBiomes.isEmpty()) {
            return true;   // biome source undeterminable: filter nothing
        }
        try {
            for (RegistryEntry<Biome> biome : structure.value().getValidBiomes()) {
                Identifier id = biome.getKey().map(k -> k.getValue()).orElse(null);
                if (id != null && dimensionBiomes.contains(id)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return true;   // a broken structure is not ours to fail on
        }
        return false;
    }

    /** Biome ids the dimension's source can produce. Empty if undeterminable. */
    public static Set<Identifier> biomeIds(BiomeSource biomeSource) {
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
