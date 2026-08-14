package com.customdimensions.dimension;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.surfacebuilder.MaterialRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composing a host generator's surface rule with the rules of the families
 * whose biomes have been transplanted into it.
 *
 * <p>The assertion that matters most is {@link #aDimensionThatMixesNothingKeepsItsOwnRuleObject()}:
 * every dimension in the pack that is not mixing families must come out of
 * this with the identical rule instance it went in with, or "this does not
 * change existing generation" is a hope rather than a property.
 */
class SurfaceCompositionTest {

    private static RegistryKey<Biome> biome(String id) {
        return RegistryKey.of(RegistryKeys.BIOME, Identifier.of(id));
    }

    /**
     * A distinguishable rule that needs neither a registry nor Bootstrap.
     *
     * <p>Two things rule out the obvious fixtures.
     * {@code MaterialRules.block(Blocks.STONE…)} drags {@code Blocks} and
     * therefore Bootstrap into a unit test, which this codebase deliberately
     * keeps out. And {@code MaterialRule} cannot be implemented from another
     * package at all — it extends {@code Function<MaterialRuleContext, …>}
     * and {@code MaterialRuleContext} is package-private, so the {@code
     * apply} signature is not even nameable here.
     *
     * <p>A proxy answers both: it satisfies the interface without naming its
     * parameter types, and the composer only ever holds a rule as an opaque
     * reference, so identity is all these assertions need.
     */
    private static MaterialRules.MaterialRule rule(String name) {
        return (MaterialRules.MaterialRule) java.lang.reflect.Proxy.newProxyInstance(
                SurfaceCompositionTest.class.getClassLoader(),
                new Class<?>[] {MaterialRules.MaterialRule.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "rule:" + name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "a composed rule is never applied or serialised in these tests");
                });
    }

    private static MaterialRules.MaterialRule rule() {
        return rule("host");
    }

    /** Records what it was asked to build; constructs no vanilla rule record. */
    private static final class RecordingAssembler implements SurfaceComposition.Assembler {
        final List<List<RegistryKey<Biome>>> gated = new java.util.ArrayList<>();
        List<MaterialRules.MaterialRule> sequenced;

        @Override
        public MaterialRules.MaterialRule gate(List<RegistryKey<Biome>> biomes,
                                               MaterialRules.MaterialRule r) {
            gated.add(biomes);
            return rule("gate" + gated.size());
        }

        @Override
        public MaterialRules.MaterialRule sequence(List<MaterialRules.MaterialRule> rules) {
            sequenced = rules;
            return rule("sequence");
        }
    }

    @Test
    void aDimensionThatMixesNothingKeepsItsOwnRuleObject() {
        MaterialRules.MaterialRule host = rule();

        assertSame(host, SurfaceComposition.compose(host, Map.of(), f -> rule()).rule());
        assertSame(host, SurfaceComposition.compose(host, null, f -> rule()).rule());
        // A family listed with no biomes is not a family.
        assertSame(host, SurfaceComposition.compose(
                host, Map.of(BiomeFamilies.NETHER, List.of()), f -> rule()).rule());
        assertFalse(SurfaceComposition.compose(host, Map.of(), f -> rule()).composed());
    }

    @Test
    void aFamilyWithNoRuleToBorrowChangesNothing() {
        // The settings that own a family may simply not be loaded — no
        // Paradise Lost, no paradise_lost:noise. Inventing a rule would put
        // the wrong blocks down; keeping the host's fall-through is exactly
        // today's behaviour for that biome.
        MaterialRules.MaterialRule host = rule();
        SurfaceComposition.Result r = SurfaceComposition.compose(host,
                Map.of(BiomeFamilies.PARADISE_LOST, List.of(biome("paradise_lost:highlands"))),
                family -> null);

        assertSame(host, r.rule());
        assertFalse(r.composed());
    }

    @Test
    void aForeignFamilyGetsItsOwnRuleAndTheHostStaysLast() {
        MaterialRules.MaterialRule host = rule();
        MaterialRules.MaterialRule netherRule = rule("nether");
        RecordingAssembler asm = new RecordingAssembler();
        SurfaceComposition.Result r = SurfaceComposition.compose(host,
                Map.of(BiomeFamilies.NETHER,
                        List.of(biome("minecraft:crimson_forest"), biome("incendium:ash_barrens"))),
                family -> BiomeFamilies.NETHER.equals(family) ? netherRule : null, asm);

        assertTrue(r.composed());
        assertEquals(Map.of(BiomeFamilies.NETHER, 2), r.byFamily());
        assertEquals(2, r.branches().size(), "one gated branch plus the host");
        assertSame(host, r.branches().get(r.branches().size() - 1),
                "the host's own rule must stay last, so anything the gates "
                + "decline falls through to exactly what it does today");
    }

    @Test
    void eachFamilyIsGatedSeparatelySoOneCannotSpeakForAnother() {
        MaterialRules.MaterialRule host = rule();
        RecordingAssembler asm = new RecordingAssembler();
        SurfaceComposition.Result r = SurfaceComposition.compose(host,
                Map.of(BiomeFamilies.NETHER, List.of(biome("minecraft:crimson_forest")),
                        BiomeFamilies.END, List.of(biome("nullscape:shadowlands"),
                                biome("minecraft:end_barrens"))),
                family -> rule(), asm);

        assertEquals(3, r.branches().size(), "two gated branches plus the host");
        assertSame(host, r.branches().get(2));
        assertEquals(1, r.byFamily().get(BiomeFamilies.NETHER));
        assertEquals(2, r.byFamily().get(BiomeFamilies.END));
    }

    @Test
    void theDescriptionIsStableSoItCanFingerprintTheComposition() {
        SurfaceComposition.Result r = SurfaceComposition.compose(rule(),
                Map.of(BiomeFamilies.NETHER, List.of(biome("minecraft:crimson_forest"))),
                family -> rule(), new RecordingAssembler());
        assertEquals("nether=1", r.describe());
        assertEquals("", SurfaceComposition.compose(rule(), Map.of(), f -> rule()).describe());
    }
}
