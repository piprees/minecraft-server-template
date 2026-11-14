package com.customdimensions.core.structure;

import com.customdimensions.core.dimension.StructureGroupRegistry.LootRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootRulesTest {

    private static final LootRule ELYTRA = new LootRule("minecraft:elytra", 0.08, List.of("dungeons"));

    @Test
    void aContainerOutsideEveryTargetGainsNothingAndRollsNothing() {
        int[] rolls = {0};
        List<String> items = LootRules.roll(List.of(ELYTRA), target -> false, () -> {
            rolls[0]++;
            return 0.0;
        });
        assertTrue(items.isEmpty());
        assertEquals(0, rolls[0]);
    }

    @Test
    void aContainerInATargetGainsTheItemAtTheRuleChance() {
        SplittableRandom random = new SplittableRandom(20261114L);
        int gained = 0;
        int containers = 100_000;
        for (int i = 0; i < containers; i++) {
            gained += LootRules.roll(List.of(ELYTRA), "dungeons"::equals, random::nextDouble).size();
        }
        double rate = gained / (double) containers;
        assertTrue(Math.abs(rate - 0.08) < 0.004, "rate " + rate);
    }

    @Test
    void eachMatchingRuleRollsOnceAndAnyOfItsTargetsMatches() {
        LootRule trident = new LootRule("minecraft:trident", 1.0, List.of("#minecraft:village", "minecraft:end_city"));
        LootRule never = new LootRule("minecraft:totem_of_undying", 1.0, List.of("landmarks"));
        assertEquals(List.of("minecraft:elytra", "minecraft:trident"),
                LootRules.roll(List.of(ELYTRA, trident, never), t -> t.equals("dungeons") || t.equals("minecraft:end_city"), () -> 0.0));
        assertEquals(List.of("minecraft:trident"),
                LootRules.roll(List.of(ELYTRA, trident), t -> t.equals("minecraft:end_city"), () -> 0.5));
    }
}
