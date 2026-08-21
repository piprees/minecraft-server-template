package com.customdimensions.dimension;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.surfacebuilder.MaterialRules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lets a transplanted biome wear its own skin.
 *
 * <p>Terrain shape is already the host dimension's and always was — a biome
 * carries no depth or scale in 1.18+. The one thing that does not travel with
 * a biome is its SURFACE, because surface rules belong to the generator:
 * {@code adventure:wide}'s rule is 117 KB naming 104 biomes, every one of
 * them {@code terralith:} or {@code minecraft:}, so a nether biome placed
 * there matches no branch, takes the overworld fall-through, and generates as
 * a grass hill with nether features on it.
 *
 * <p>The fix composes rather than rewrites:
 *
 * <pre>
 * sequence(
 *   condition(biome_is[foreign nether biomes here], the live minecraft:nether rule),
 *   condition(biome_is[foreign end biomes here],    the live minecraft:end rule),
 *   the host's own rule, unchanged, last)
 * </pre>
 *
 * <p>Each borrowed rule is gated on exactly the foreign biomes present, so it
 * can never speak for a biome the host already handles, and the host's rule
 * stays last and untouched — anything the gates decline falls through to
 * precisely what it does today.
 *
 * <p><b>The safety property is structural.</b> With no foreign biomes the
 * composer returns the host rule OBJECT, not an equal one. A dimension that
 * is not mixing families therefore generates from the identical rule instance
 * it does now, which is a guarantee by construction rather than an assertion
 * about equality — and the test for it is {@code assertSame}.
 */
public final class SurfaceComposition {

    private SurfaceComposition() {
    }

    /**
     * The two vanilla calls this needs, behind a seam.
     *
     * <p>Not indirection for its own sake: {@code MaterialRules}' rule
     * records initialise their codecs statically, so touching one drags
     * Bootstrap into any test. The composer's LOGIC — which families are
     * foreign, which biomes each gate names, that the host stays last, that
     * nothing is built when nothing is foreign — is the part worth pinning,
     * and behind this seam it pins with no registry at all. {@link #VANILLA}
     * is the only implementation that ships.
     */
    public interface Assembler {

        /** A rule that applies only to the named biomes. */
        MaterialRules.MaterialRule gate(List<RegistryKey<Biome>> biomes,
                                        MaterialRules.MaterialRule rule);

        /** Rules tried in order, first match winning. */
        MaterialRules.MaterialRule sequence(List<MaterialRules.MaterialRule> rules);
    }

    public static final Assembler VANILLA = new Assembler() {
        @Override
        public MaterialRules.MaterialRule gate(List<RegistryKey<Biome>> biomes,
                                               MaterialRules.MaterialRule rule) {
            @SuppressWarnings("unchecked")
            RegistryKey<Biome>[] keys = biomes.toArray(new RegistryKey[0]);
            return MaterialRules.condition(MaterialRules.biome(keys), rule);
        }

        @Override
        public MaterialRules.MaterialRule sequence(List<MaterialRules.MaterialRule> rules) {
            return MaterialRules.sequence(rules.toArray(new MaterialRules.MaterialRule[0]));
        }
    };

    /**
     * What a composition did, for the boot log and the fingerprint.
     *
     * @param branches the rules handed to {@code sequence} in order, host
     *                 LAST — or the host alone when nothing was composed.
     *                 Carried because vanilla's {@code SequenceMaterialRule}
     *                 is package-private, so this is the only way a test can
     *                 assert the ordering that makes the change safe.
     */
    public record Result(MaterialRules.MaterialRule rule, Map<String, Integer> byFamily,
                         List<MaterialRules.MaterialRule> branches) {

        public boolean composed() {
            return !byFamily.isEmpty();
        }

        /** "nether=3, end=1" — stable order, for a log line and a fingerprint. */
        public String describe() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Integer> e : byFamily.entrySet()) {
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(e.getKey()).append('=').append(e.getValue());
            }
            return b.toString();
        }
    }

    /**
     * Composes the host rule with one borrowed rule per foreign family.
     *
     * @param host       the host generator's own rule. Returned UNCHANGED and
     *                   by reference when nothing is foreign.
     * @param foreign    family -> the biomes of that family present here that
     *                   the host does not own. Empty families are ignored.
     * @param homeRule   family -> that family's surface rule, or null when the
     *                   settings it lives in are not loaded. Taken as a lookup
     *                   so this stays pure and testable with no registry.
     */
    public static Result compose(MaterialRules.MaterialRule host,
                                 Map<String, List<RegistryKey<Biome>>> foreign,
                                 Function<String, MaterialRules.MaterialRule> homeRule) {
        return compose(host, foreign, homeRule, VANILLA);
    }

    /** As above with the vanilla calls supplied — the seam the tests use. */
    public static Result compose(MaterialRules.MaterialRule host,
                                 Map<String, List<RegistryKey<Biome>>> foreign,
                                 Function<String, MaterialRules.MaterialRule> homeRule,
                                 Assembler assembler) {
        if (host == null || foreign == null || foreign.isEmpty()) {
            return unchanged(host);
        }
        List<MaterialRules.MaterialRule> branches = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, List<RegistryKey<Biome>>> e : foreign.entrySet()) {
            List<RegistryKey<Biome>> biomes = e.getValue();
            if (biomes == null || biomes.isEmpty()) {
                continue;
            }
            MaterialRules.MaterialRule borrowed = homeRule == null ? null : homeRule.apply(e.getKey());
            if (borrowed == null) {
                // No rule to borrow is not a reason to invent one: the biome
                // keeps the host's fall-through, exactly as it does today.
                continue;
            }
            branches.add(assembler.gate(List.copyOf(biomes), borrowed));
            counts.put(e.getKey(), biomes.size());
        }
        if (branches.isEmpty()) {
            return unchanged(host);
        }
        branches.add(host);
        return new Result(assembler.sequence(List.copyOf(branches)),
                Map.copyOf(counts), List.copyOf(branches));
    }

    /** The host's rule, by reference. The whole safety property is this line. */
    private static Result unchanged(MaterialRules.MaterialRule host) {
        return new Result(host, Map.of(),
                host == null ? List.of() : List.of(host));
    }
}
