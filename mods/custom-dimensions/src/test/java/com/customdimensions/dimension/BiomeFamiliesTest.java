package com.customdimensions.dimension;

import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which world a biome comes from. The classifier decides whose surface rule
 * dresses a transplanted biome, so a wrong answer here is a nether biome
 * wearing grass — plausible, and wrong.
 */
class BiomeFamiliesTest {

    /** A biome carrying exactly the given tags and no others. */
    @SafeVarargs
    private static Predicate<TagKey<Biome>> tagged(TagKey<Biome>... tags) {
        Set<TagKey<Biome>> set = Set.of(tags);
        return set::contains;
    }

    private static Identifier id(String s) {
        return Identifier.of(s);
    }

    @Test
    void theFamilyTagsDecideForEveryModThatMaintainsThem() {
        // Verified in the shipped jars: Incendium adds 8 biomes to
        // #minecraft:is_nether, Nullscape adds its set to #is_end through a
        // nested tag, Terralith and Nature's Spirit add 50 to #is_overworld.
        // Reading the tag is what makes this work for a mod added next year
        // without touching this class.
        assertEquals(BiomeFamilies.NETHER, BiomeFamilies.familyOf(
                id("incendium:infernal_dunes"), tagged(BiomeTags.IS_NETHER)));
        assertEquals(BiomeFamilies.END, BiomeFamilies.familyOf(
                id("nullscape:crystal_peaks"), tagged(BiomeTags.IS_END)));
        assertEquals(BiomeFamilies.OVERWORLD, BiomeFamilies.familyOf(
                id("terralith:lush_valley"), tagged(BiomeTags.IS_OVERWORLD)));
        assertEquals(BiomeFamilies.NETHER, BiomeFamilies.familyOf(
                id("minecraft:crimson_forest"), tagged(BiomeTags.IS_NETHER)));
    }

    @Test
    void aNamespaceWithNoFamilyTagIsStillPlaced() {
        // Paradise Lost ships no is_overworld/is_nether/is_end membership at
        // all — only has_structure/* — so its fifteen biomes would fall
        // through to nothing and keep the host's skin forever.
        assertEquals(BiomeFamilies.PARADISE_LOST, BiomeFamilies.familyOf(
                id("paradise_lost:highlands"), tagged()));
    }

    @Test
    void anExplicitNamespaceOutranksAGenericTag() {
        // A deliberate statement about a whole mod's dimension beats a tag
        // some other mod may have added loosely. Without this order, one
        // stray #is_overworld entry would dress every Paradise Lost biome as
        // overworld.
        assertEquals(BiomeFamilies.PARADISE_LOST, BiomeFamilies.familyOf(
                id("paradise_lost:highlands"), tagged(BiomeTags.IS_OVERWORLD)));
    }

    @Test
    void anUnclaimedBiomeGetsNoFamilyRatherThanAGuess() {
        // No family means "keep the host's fall-through", which is exactly
        // today's behaviour. Guessing a family here would put the wrong
        // blocks on a biome nobody asked us to re-skin.
        assertNull(BiomeFamilies.familyOf(id("somemod:unknown_place"), tagged()));
        assertNull(BiomeFamilies.familyOf(null, tagged()));
    }

    @Test
    void theHostFamilyComesFromTheTypeAndNeverFromTheScoringHint() {
        // the_pale_reach is type paradise_lost:paradise_lost with six
        // transplanted overworld biomes and one of its own — and it sets
        // seedRoll.family: "overworld", because that field says which biome
        // table a SEED should be judged against. Reading it here inverts the
        // question and re-skins the one native biome instead of the six
        // foreign ones. hostFamily never sees the config.
        assertEquals(BiomeFamilies.PARADISE_LOST,
                BiomeFamilies.hostFamily("paradise_lost:paradise_lost"));
        assertEquals(BiomeFamilies.OVERWORLD, BiomeFamilies.hostFamily("multi_biome"));
        assertEquals(BiomeFamilies.OVERWORLD, BiomeFamilies.hostFamily("cave"));
        assertEquals(BiomeFamilies.OVERWORLD, BiomeFamilies.hostFamily("amplified"));
        assertEquals(BiomeFamilies.NETHER, BiomeFamilies.hostFamily("nether"));
        assertEquals(BiomeFamilies.NETHER, BiomeFamilies.hostFamily("nether_islands"));
        assertEquals(BiomeFamilies.END, BiomeFamilies.hostFamily("end"));
    }

    @Test
    void aTypeWithNoFamilyLeavesTheDimensionAlone() {
        // Without a host family there is nothing to call foreign, so the
        // caller must not compose at all rather than guess a host.
        assertNull(BiomeFamilies.hostFamily("void"));
        assertNull(BiomeFamilies.hostFamily("superflat"));
        assertNull(BiomeFamilies.hostFamily(null));
        assertNull(BiomeFamilies.hostFamily("  "));
    }

    @Test
    void everyFamilyNamesTheGeneratorThatDressesIt() {
        // A family with no home settings is a family whose biomes can be
        // classified and then never dressed — the classifier would report a
        // match and nothing would come of it.
        for (String family : BiomeFamilies.known()) {
            assertNotNull(BiomeFamilies.homeSettings(family),
                    family + " is a known family with no home settings");
        }
        assertEquals(Identifier.of("minecraft", "nether"),
                BiomeFamilies.homeSettings(BiomeFamilies.NETHER));
        assertEquals(Identifier.of("minecraft", "end"),
                BiomeFamilies.homeSettings(BiomeFamilies.END));
        assertEquals(Identifier.of("paradise_lost", "noise"),
                BiomeFamilies.homeSettings(BiomeFamilies.PARADISE_LOST));
        assertNull(BiomeFamilies.homeSettings(null));
        assertNull(BiomeFamilies.homeSettings("no_such_family"));
    }
}
