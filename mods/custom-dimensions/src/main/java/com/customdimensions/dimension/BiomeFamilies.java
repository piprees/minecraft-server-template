package com.customdimensions.dimension;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Which world a biome comes from, and which generator's surface rule knows
 * how to dress it.
 *
 * <p>A biome carries no terrain shape — {@code Biome} holds exactly
 * {@code weather}, {@code generationSettings}, {@code spawnSettings} and
 * {@code effects}, and depth/scale were removed in 1.18 — so putting a nether
 * biome in an overworld dimension already gives it overworld terrain at
 * overworld heights. What it does NOT give it is the right blocks on top:
 * surface rules belong to the GENERATOR, and an overworld generator's rule
 * names only overworld biomes, so a transplanted one takes the fall-through
 * and comes out as grass.
 *
 * <p>This class answers the two questions the fix needs: which family a biome
 * belongs to, and which {@code ChunkGeneratorSettings} owns that family's
 * surface rule.
 */
public final class BiomeFamilies {

    public static final String OVERWORLD = "overworld";
    public static final String NETHER = "nether";
    public static final String END = "end";
    public static final String PARADISE_LOST = "paradise_lost";

    /**
     * The generator whose surface rule dresses each family.
     *
     * <p>These are the LIVE registry entries, not vanilla's originals, and
     * that is what makes this cheap: mods that add biomes to a family patch
     * that family's settings themselves. Incendium overrides
     * {@code minecraft:nether} (its rule names 13 biomes, 8 of them
     * {@code incendium:}) and Nullscape overrides {@code minecraft:end}. So
     * borrowing a family's whole rule inherits every mod's own surface work,
     * and a nether mod added next year needs no change here.
     */
    private static final Map<String, Identifier> HOME_SETTINGS = Map.of(
            OVERWORLD, Identifier.of("minecraft", "overworld"),
            NETHER, Identifier.of("minecraft", "nether"),
            END, Identifier.of("minecraft", "end"),
            PARADISE_LOST, Identifier.of("paradise_lost", "noise"));

    /**
     * Namespaces whose biomes belong to a family no vanilla tag names.
     *
     * <p>Checked BEFORE the tags, and only for namespaces listed here.
     * Paradise Lost ships no {@code #minecraft:is_overworld} /
     * {@code is_nether} / {@code is_end} membership at all — only
     * {@code has_structure/*} — so its fifteen biomes would otherwise fall
     * through to no family. An explicit entry here is a deliberate statement
     * about a whole mod's dimension and outranks a generic tag; every other
     * namespace goes to the tags, which is where the answer belongs.
     */
    private static final Map<String, String> FAMILY_BY_NAMESPACE = Map.of(
            "paradise_lost", PARADISE_LOST);

    private BiomeFamilies() {
    }

    /**
     * The family a biome belongs to, or null when nothing claims it.
     *
     * @param isIn membership test for a tag. Taken as a predicate rather than
     *             a registry so the rules below can be pinned without a
     *             server — the same seam {@code DimensionLint} uses for its
     *             registry-backed checks.
     */
    public static String familyOf(Identifier biomeId, Predicate<TagKey<Biome>> isIn) {
        if (biomeId == null) {
            return null;
        }
        String byNamespace = FAMILY_BY_NAMESPACE.get(biomeId.getNamespace());
        if (byNamespace != null) {
            return byNamespace;
        }
        if (isIn == null) {
            return null;
        }
        // Nether and End first: they are small, closed sets, while
        // is_overworld is the one a mod is most likely to add a biome to
        // loosely.
        if (isIn.test(BiomeTags.IS_NETHER)) {
            return NETHER;
        }
        if (isIn.test(BiomeTags.IS_END)) {
            return END;
        }
        return isIn.test(BiomeTags.IS_OVERWORLD) ? OVERWORLD : null;
    }

    /** As above for a live registry entry. */
    public static String familyOf(RegistryEntry<Biome> biome) {
        if (biome == null) {
            return null;
        }
        Identifier id = biome.getKey().map(RegistryKey::getValue).orElse(null);
        return familyOf(id, biome::isIn);
    }

    /** The settings whose surface rule dresses this family, or null. */
    public static Identifier homeSettings(String family) {
        return family == null ? null : HOME_SETTINGS.get(family);
    }

    /**
     * The family a dimension's own GENERATOR belongs to, from its type alone.
     *
     * <p>Deliberately NOT {@code StructureWants.familyOf}, which answers a
     * different question. That one is the SCORING family and honours
     * {@code seedRoll.family} — an author's hint about which biome table a
     * seed should be judged against. Six shipped dimensions set it, and
     * {@code the_pale_reach} sets it to {@code overworld} while its type is
     * {@code paradise_lost:paradise_lost}. Reading it here inverts the whole
     * question: it would call the dimension's own Paradise Lost biome foreign
     * and its six transplanted overworld biomes native, and re-skin exactly
     * the wrong ones.
     *
     * <p>Null for a type that belongs to no family — void, superflat, or
     * anything unrecognised. Without a host family there is no way to say
     * what is foreign, and the caller leaves such a dimension alone.
     */
    public static String hostFamily(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String trimmed = type.trim();
        if (trimmed.startsWith("paradise_lost:")) {
            return PARADISE_LOST;
        }
        return StructureWants.familyForType(trimmed);
    }

    /**
     * Types whose generator borrows another family's whole settings record.
     *
     * <p>{@code sky_islands} and {@code nether_islands} are both built from
     * {@code endGen.getSettings()} — the live {@code minecraft:end} record, so
     * on this stack Nullscape's. Their {@link #hostFamily} is right about
     * everything else about them (an overworld sky world wants overworld
     * structures) and wrong about the only thing this table is for: the
     * surface rule in play names End biomes, and its third and last branch is
     * an unconditional {@code minecraft:end_stone} block with no biome gate.
     * Judged against their structure family every biome they carry is native,
     * nothing composes, and every one of them hits that branch — sakura
     * gardens and crimson forests alike arrive as bare end stone.
     */
    private static final Map<String, String> SURFACE_FAMILY_BY_TYPE = Map.of(
            "sky_islands", END,
            "nether_islands", END);

    /**
     * The family whose surface rule this dimension's generator actually
     * carries — which is what decides whether a biome needs dressing.
     *
     * <p>Usually {@link #hostFamily}, and the two are the same for every type
     * that builds on its own family's settings. Two things separate them:
     *
     * <ul>
     * <li>The island types, which borrow the End's record whole
     *     ({@link #SURFACE_FAMILY_BY_TYPE}).</li>
     * <li>An explicit {@code noiseSettings}, which replaces the record
     *     entirely — surface rule included — so the preset decides and the
     *     type no longer does. Every preset this mod ships is built from the
     *     overworld's rule by {@code gen-terrain-presets.py}; {@code
     *     adventure:void} is suppressed before it reaches a generator and so
     *     never arrives here.</li>
     * </ul>
     *
     * <p>Null carries the same meaning as it does for {@link #hostFamily}: no
     * way to say what is foreign, so the caller dresses nothing.
     *
     * @param type          the dimension's configured type
     * @param noiseSettings its {@code noiseSettings}, or null when unset
     */
    public static String surfaceHostFamily(String type, String noiseSettings) {
        if (noiseSettings != null && !noiseSettings.isBlank()) {
            return OVERWORLD;
        }
        if (type == null || type.isBlank()) {
            return null;
        }
        String borrowed = SURFACE_FAMILY_BY_TYPE.get(type.trim());
        return borrowed != null ? borrowed : hostFamily(type);
    }

    /** Every family this class can dress, for lint and for the boot log. */
    public static java.util.Set<String> known() {
        return HOME_SETTINGS.keySet();
    }
}
