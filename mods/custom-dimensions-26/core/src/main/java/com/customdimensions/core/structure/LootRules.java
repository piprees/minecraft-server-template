package com.customdimensions.core.structure;

import com.customdimensions.core.dimension.StructureGroupRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;

/**
 * Which items a container's loot gains from a level's loot rules: each rule
 * whose structures name one the container stands in adds its item at its
 * chance, one roll per rule.
 */
public final class LootRules {

    private LootRules() {
    }

    /**
     * The items gained. {@code standsIn} answers whether the container stands
     * in a piece of a structure a target names (an id, a {@code #tag} or a
     * group); {@code random} gives a value in [0, 1).
     */
    public static List<String> roll(List<StructureGroupRegistry.LootRule> rules, Predicate<String> standsIn,
                                    DoubleSupplier random) {
        List<String> out = new ArrayList<>();
        for (StructureGroupRegistry.LootRule rule : rules) {
            if (rule.structures().stream().anyMatch(standsIn) && random.getAsDouble() < rule.chance()) {
                out.add(rule.item());
            }
        }
        return out;
    }
}
