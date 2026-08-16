package com.customdimensions.command;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.NoiseGroupPlan;
import com.customdimensions.dimension.NoisePoolBuilder;
import com.customdimensions.dimension.StructureAliases;
import com.customdimensions.dimension.StructureWants;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Validates dimension configs against the live registries. No seeds, no
 * rolling, no world — every answer comes from config plus what the server
 * actually has loaded.
 *
 * <p>Catches a want in {@code structures.wants} that the dimension's noise
 * pool cannot contain — such a want scores zero on every seed of every roll
 * forever, which looks like bad luck rather than the config fault it is.
 *
 * <p>Every finding carries a {@code fix}: the concrete config edit that
 * resolves it. A finding a human cannot act on is a complaint, not a lint.
 */
public final class DimensionLint {

    public static final String ERROR = "error";
    public static final String WARN = "warn";
    public static final String INFO = "info";

    /**
     * One fault. {@code subject} is whatever the finding is about — a want
     * name, a biome id, a structure set — so findings can be diffed between
     * runs without parsing prose.
     */
    public record Finding(String dimension, String severity, String check,
                          String subject, String message, String fix) {
    }

    private DimensionLint() {
    }

    /** Lint every configured dimension, or one named dimension. */
    public static List<Finding> lint(MinecraftServer server, String only) {
        List<Finding> findings = new ArrayList<>();
        Map<String, List<String>> igniters = new TreeMap<>();
        // Reserved dimensions carry the same structures and seedRoll blocks as any
        // other dimension and are rolled the same way, so they are linted
        // the same way.
        for (DimensionConfig def : targets()) {
            if (only != null && !only.equals(def.getName())) {
                continue;
            }
            findings.addAll(lintOne(server, def));
            collectIgniter(igniters, def);
        }
        if (only == null) {
            findings.addAll(igniterCollisions(igniters));
            findings.addAll(checkSuppressList(server));
        }
        return findings;
    }

    /** Every configured dimension AND reserved dimension, in a stable order. */
    public static List<DimensionConfig> targets() {
        List<DimensionConfig> out =
                new ArrayList<>(MultiverseConfig.getInstance().getCustomDimensions());
        out.addAll(MultiverseConfig.getInstance().getAllDimensions().stream()
                .filter(DimensionConfig::isReserved).toList());
        return out;
    }

    // --------------------------------------------------------------- one dim

    private static List<Finding> lintOne(MinecraftServer server, DimensionConfig def) {
        List<Finding> out = new ArrayList<>();
        String name = def.getName();

        SpikeSampler.Base base = SpikeSampler.base(server, def.getDimensionIdentifier());
        if (!base.ok()) {
            out.add(new Finding(name, ERROR, "config_unbuildable", name,
                    "the dimension's generator could not be built from config: " + base.error(),
                    "fix the config error above; until then this dimension cannot "
                    + "be checked, rolled, or created"));
            return out;
        }
        BiomeSource biomeSource = base.generator().getBiomeSource();

        out.addAll(checkBiomes(server, def, biomeSource));
        out.addAll(checkWants(server, def, biomeSource));
        out.addAll(checkForced(server, def));
        out.addAll(checkRadialCurves(def));
        out.addAll(checkPortalBlocks(def));
        return out;
    }

    // ----------------------------------------------------------------- wants

    /**
     * The headline check. A want must resolve to a real structure AND land in
     * the pool {@code NoisePoolBuilder} produces for this dimension.
     *
     * <p>The pool is built from the FULL structure-set registry rather than
     * from a live world's prefiltered calculator, because there is no world.
     * The difference is one-directional and worth knowing: vanilla's
     * {@code StructurePlacementCalculator.create} drops whole sets whose
     * structures' biomes miss the source, and a {@code structures.include}
     * entry cannot resurrect one it dropped. So a want reported in-pool ONLY
     * because of {@code include} gets its own finding rather than a clean bill.
     */
    private static List<Finding> checkWants(MinecraftServer server, DimensionConfig def,
                                            BiomeSource biomeSource) {
        List<Finding> out = new ArrayList<>();
        String name = def.getName();
        StructureWants.Resolved wants = StructureWants.resolve(def);
        if (wants.names().isEmpty()) {
            return out;
        }
        DimensionConfig.Structures block = def.getStructures();
        if (wants.source() == StructureWants.Source.FAMILY_DEFAULT) {
            out.add(new Finding(name, INFO, "wants_inherited", wants.family(),
                    "this dimension names no wants, so it is scored against the "
                    + wants.family() + " family default (" + String.join(", ", wants.names())
                    + ") — structures its author never asked for",
                    "name the wants you actually want in structures.wants, or accept "
                    + "the inherited list deliberately"));
        }

        NoiseGroupPlan plan = NoiseGroupPlan.resolve(def);
        Registry<StructureSet> setRegistry = server.getRegistryManager()
                .get(RegistryKeys.STRUCTURE_SET);
        Registry<net.minecraft.world.gen.structure.Structure> structureRegistry =
                server.getRegistryManager().get(RegistryKeys.STRUCTURE);

        Set<String> exclude = new java.util.HashSet<>(NoisePoolBuilder.lowerSet(
                block == null ? null : block.exclude));
        exclude.addAll(NoisePoolBuilder.lowerSet(
                MultiverseConfig.getInstance().getSuppressedStructureSets()));

        List<RegistryEntry<StructureSet>> sets = new ArrayList<>();
        for (var entry : setRegistry.getIndexedEntries()) {
            sets.add(entry);
        }

        // Three builds distinguish a want that fits from one that only
        // survives because a filter was bypassed for it:
        //
        //   full     what generation actually builds (include + wants)
        //   noWant   without the wants bypass
        //   bare     without either bypass
        boolean noiseSuppressed = plan.isSuppressed();
        Set<String> wantedIds = NoisePoolBuilder.wantedStructureIds(def);
        Map<String, String> poolGroupOf = noiseSuppressed ? Map.of()
                : poolIndex(NoisePoolBuilder.build(
                        def, sets, biomeSource, plan, exclude, null, wantedIds));
        Map<String, String> poolNoWant = noiseSuppressed ? Map.of()
                : poolIndex(NoisePoolBuilder.build(
                        def, sets, biomeSource, plan, exclude, null, Set.of()));
        Map<String, String> poolWithoutInclude = noiseSuppressed ? Map.of()
                : (block != null && block.include != null && !block.include.isEmpty()
                        ? poolIndex(NoisePoolBuilder.build(
                                def, sets, biomeSource, plan, exclude, List.of(), Set.of()))
                        : poolNoWant);

        Set<String> forcedIds = forcedStructureIds(def);

        for (String wantName : wants.names()) {
            String id = StructureAliases.resolve(wantName);
            if (id == null) {
                out.add(new Finding(name, ERROR, "want_unknown_name", wantName,
                        "'" + wantName + "' is neither a known short name nor a "
                        + "namespaced structure id",
                        "use a full id (\"ns:path\") or one of the "
                        + StructureAliases.all().size()
                        + " names in structure_aliases.json"));
                continue;
            }
            if (id.startsWith("#")) {
                out.add(new Finding(name, INFO, "want_is_tag", wantName,
                        "'" + wantName + "' resolves to the tag " + id
                        + ", which is a set of structures rather than one",
                        "no action needed; tag wants are not scored through the "
                        + "structure census"));
                continue;
            }
            Identifier structureId = Identifier.tryParse(id);
            if (structureId == null
                    || structureRegistry.getEntry(RegistryKey.of(
                            RegistryKeys.STRUCTURE, structureId)).isEmpty()) {
                out.add(new Finding(name, ERROR, "want_unknown_structure", wantName,
                        "'" + wantName + "' resolves to " + id
                        + ", which is not in the structure registry "
                        + "(a mod was removed, or the alias is wrong)",
                        "remove the want, or add the mod that provides " + id));
                continue;
            }
            if (forcedIds.contains(id)) {
                // Placed by hand at a fixed spot, so the pool is irrelevant to
                // whether it exists — and `exclusive` (the default) removes
                // it from the pool so it appears nowhere else.
                //
                // Reported anyway because the scorer cannot see it: a banked
                // noiseCensus carries only the noise groups, so a forced
                // structure reads as absent and the want is docked to 0.0 on
                // every seed.
                out.add(new Finding(name, WARN, "want_is_forced", wantName,
                        id + " is placed by structures.force at a fixed position, so "
                        + "it is guaranteed present and deliberately absent from the "
                        + "noise pool — but the census the roller scores against "
                        + "records only noise groups, so this want is scored 0.0 on "
                        + "every seed",
                        "drop the want (the force already guarantees it), or set "
                        + "\"exclusive\": false on the force entry so it also enters "
                        + "the pool and the census can see it"));
                continue;
            }
            if (noiseSuppressed) {
                // An inherited list is not an authoring fault: there is no
                // wants block to remove, so ERROR would fail a build over a
                // config nobody wrote. The dimension places only what it
                // forces, which is a complete answer on its own.
                boolean inherited = wants.source() == StructureWants.Source.FAMILY_DEFAULT;
                out.add(new Finding(name, inherited ? INFO : ERROR, "want_no_noise_groups",
                        wantName,
                        "this dimension places no noise-managed structures ("
                        + plan.reason() + "), so '" + wantName
                        + "' can never be placed or scored"
                        + (inherited ? " — and it comes from the " + wants.family()
                                + " family default, not from this config" : ""),
                        inherited
                                ? "no action needed; a dimension that places only what it "
                                  + "forces is not scored on the family list. Name an empty "
                                  + "structures.wants to silence this."
                                : "remove the wants block, or give the dimension a "
                                  + "structureDensity other than \"none\""));
                continue;
            }
            String group = poolGroupOf.get(id);
            if (group == null) {
                String setId = setIdFor(setRegistry, id);
                // A set whose placement is not noise-managed never reaches a
                // pool BY DESIGN — it keeps its own grid placement and still
                // generates. Four of 380 sets are pass-throughs, so a want
                // naming one is a warning rather than an error.
                String passThroughType = passThroughPlacementType(setRegistry, setId);
                if (passThroughType != null) {
                    out.add(new Finding(name, WARN, "want_is_passthrough", wantName,
                            id + " is placed on its own grid, not by noise: its set "
                            + setId + " uses the " + passThroughType + " placement "
                            + "type, which the pool builder deliberately leaves alone. "
                            + "It generates — but the census the roller scores against "
                            + "records only noise groups, so this want is scored 0.0 "
                            + "on every seed",
                            "no config change makes it a noise placement; score it "
                            + "from the census's passThrough section instead, or drop "
                            + "the want and rely on the set's own grid"));
                    continue;
                }
                boolean excluded = exclude.contains(setId.toLowerCase(Locale.ROOT));
                out.add(new Finding(name, ERROR, "want_not_in_pool", wantName,
                        id + " reaches no eligible pool in this dimension"
                        + (excluded
                                ? ", because its set " + setId + " is excluded by "
                                  + "structures.exclude or the global suppress list"
                                : ", because its set " + setId + " belongs to no noise "
                                  + "group this dimension enables")
                        + " — no seed can place it and the want scores 0.0 forever",
                        excluded
                                ? "remove " + setId + " from structures.exclude (or the "
                                  + "settings.json suppress list), or drop the want"
                                : "drop the want, or enable the group its set belongs "
                                  + "to in structures.noise"));
                continue;
            }
            if (!poolNoWant.containsKey(id)) {
                out.add(new Finding(name, INFO, "want_biome_disjoint", wantName,
                        id + " is in the '" + group + "' pool only because it is "
                        + "wanted: none of this dimension's biomes is on its own "
                        + "valid-biome list, so it is being placed against its "
                        + "author's biome rules. It will generate where the noise "
                        + "assigns it unless its own generation declines the terrain "
                        + "— check census/rejections__* if it never appears",
                        "no action needed if that is what you meant; add a biome it "
                        + "actually belongs in if you would rather it fitted"));
            } else if (!poolWithoutInclude.containsKey(id)) {
                out.add(new Finding(name, INFO, "want_in_pool_only_via_include", wantName,
                        id + " is in the '" + group + "' pool only because "
                        + "structures.include bypasses the biome affinity filter for "
                        + "its set",
                        "no action needed; the want bypass would have covered it "
                        + "anyway, so the include entry may be redundant"));
            }
        }
        return out;
    }

    /** structure id -> the group whose pool holds it. */
    private static Map<String, String> poolIndex(NoisePoolBuilder.Result pools) {
        Map<String, String> out = new LinkedHashMap<>();
        pools.pools().forEach((group, pool) -> {
            for (StructureSet.WeightedEntry weighted : pool.entries()) {
                weighted.structure().getKey().ifPresent(
                        k -> out.putIfAbsent(k.getValue().toString(), group));
            }
        });
        return out;
    }

    /**
     * The registered placement-type id of a set that noise does NOT manage, or
     * null when the set is noise-managed (or unknown). Naming the type in the
     * finding is what makes it actionable: "yungsapi:enhanced_random_spread"
     * tells an author immediately that no biome or include change will move it.
     */
    private static String passThroughPlacementType(Registry<StructureSet> setRegistry,
                                                   String setId) {
        Identifier id = Identifier.tryParse(setId);
        if (id == null) {
            return null;
        }
        StructureSet set = setRegistry.get(id);
        if (set == null || NoisePoolBuilder.noiseManaged(set.placement())) {
            return null;
        }
        Identifier type = net.minecraft.registry.Registries.STRUCTURE_PLACEMENT
                .getId(set.placement().getType());
        return type != null ? type.toString() : set.placement().getClass().getSimpleName();
    }

    /** The set a structure belongs to, for a fix suggestion. */
    private static String setIdFor(Registry<StructureSet> setRegistry, String structureId) {
        for (var entry : setRegistry.getEntrySet()) {
            for (StructureSet.WeightedEntry weighted : entry.getValue().structures()) {
                String id = weighted.structure().getKey()
                        .map(k -> k.getValue().toString()).orElse(null);
                if (structureId.equals(id)) {
                    return entry.getKey().getValue().toString();
                }
            }
        }
        return "<the set containing " + structureId + ">";
    }

    private static Set<String> forcedStructureIds(DimensionConfig def) {
        DimensionConfig.Structures block = def.getStructures();
        if (block == null || block.force == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (DimensionConfig.ForcedStructure f : block.force) {
            if (f != null && f.structure != null) {
                out.add(f.structure);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- biomes

    /**
     * Every listed biome must exist in the registry and must actually be
     * produced by the source the config builds. The second half is the one
     * that matters: {@code buildMixedSource} silently drops a biome it cannot
     * place, and a dimension then generates nothing like its own theme with no
     * error anywhere.
     */
    private static List<Finding> checkBiomes(MinecraftServer server, DimensionConfig def,
                                             BiomeSource biomeSource) {
        List<Finding> out = new ArrayList<>();
        List<String> listed = def.getBiomes();
        if (listed == null || listed.isEmpty()) {
            return out;
        }
        String name = def.getName();
        Registry<Biome> biomeRegistry = server.getRegistryManager().get(RegistryKeys.BIOME);

        Set<String> produced = new LinkedHashSet<>();
        for (RegistryEntry<Biome> entry : biomeSource.getBiomes()) {
            entry.getKey().ifPresent(k -> produced.add(k.getValue().toString()));
        }

        for (String raw : listed) {
            String trimmed = raw == null ? null : raw.trim();
            if (trimmed == null || trimmed.isEmpty()) {
                continue;
            }
            Identifier id = Identifier.tryParse(trimmed.toLowerCase(Locale.ROOT));
            if (id == null || biomeRegistry.getEntry(
                    RegistryKey.of(RegistryKeys.BIOME, id)).isEmpty()) {
                out.add(new Finding(name, ERROR, "biome_unknown", trimmed,
                        trimmed + " is not in the biome registry (a mod was removed, "
                        + "or the id is misspelt)",
                        "remove it from biomes, or add the mod that provides it"));
                continue;
            }
            if (!produced.contains(id.toString())) {
                out.add(new Finding(name, ERROR, "biome_not_produced", trimmed,
                        id + " is listed but the biome source this config builds "
                        + "never produces it — it cannot appear at any seed",
                        "the source ran out of parameter regions to deal it; "
                        + "shorten the biomes list, or give this biome an explicit "
                        + "\"parameters\" block"));
            }
        }

        Map<String, com.google.gson.JsonObject> params = def.getBiomeParameters();
        if (params != null) {
            for (String biomeId : params.keySet()) {
                boolean isListed = listed.stream().anyMatch(
                        b -> b != null && b.trim().equalsIgnoreCase(biomeId));
                if (!isListed) {
                    out.add(new Finding(name, WARN, "parameters_for_unlisted_biome", biomeId,
                            "biomeParameters names " + biomeId
                            + ", which is not in this dimension's biomes list — "
                            + "the parameters are ignored",
                            "add " + biomeId + " to biomes, or drop its parameters entry"));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- forced

    private static List<Finding> checkForced(MinecraftServer server, DimensionConfig def) {
        List<Finding> out = new ArrayList<>();
        DimensionConfig.Structures block = def.getStructures();
        if (block == null || block.force == null || block.force.isEmpty()) {
            return out;
        }
        String name = def.getName();
        Registry<net.minecraft.world.gen.structure.Structure> structureRegistry =
                server.getRegistryManager().get(RegistryKeys.STRUCTURE);
        int generationBorder = def.getGenerationBorderRadius();

        for (DimensionConfig.ForcedStructure f : block.force) {
            if (f == null || f.structure == null || f.x == null || f.z == null) {
                out.add(new Finding(name, ERROR, "force_incomplete",
                        f == null ? "(null)" : String.valueOf(f.structure),
                        "a structures.force entry is missing structure, x or z",
                        "give every force entry all three of structure, x and z"));
                continue;
            }
            Identifier id = Identifier.tryParse(f.structure);
            if (id == null || structureRegistry.getEntry(
                    RegistryKey.of(RegistryKeys.STRUCTURE, id)).isEmpty()) {
                out.add(new Finding(name, ERROR, "force_unknown_structure", f.structure,
                        f.structure + " is not in the structure registry, so the "
                        + "forced placement is skipped at world load",
                        "correct the id, or add the mod that provides it"));
                continue;
            }
            if (generationBorder > 0
                    && (Math.abs(f.x) > generationBorder || Math.abs(f.z) > generationBorder)) {
                out.add(new Finding(name, ERROR, "force_outside_border", f.structure,
                        f.structure + " is forced to (" + f.x + ", " + f.z
                        + "), outside borders.generation (radius " + generationBorder
                        + ") — chunks never generate there, so it never appears",
                        "move it inside +/-" + generationBorder
                        + ", or raise borders.generation"));
            }
        }
        return out;
    }

    // ---------------------------------------------------------- radial curves

    /**
     * A radial curve is ten control points in 0.0-3.0. The mod clamps a
     * malformed one and carries on, which is right at boot and wrong for an
     * author who wanted a shape they did not get. Package-private for unit
     * tests (same pattern as {@code derivedShrineSpacing}).
     */
    static List<Finding> checkRadialCurves(DimensionConfig def) {
        List<Finding> out = new ArrayList<>();
        DimensionConfig.Structures block = def.getStructures();
        if (block == null || block.radial == null || block.radial.isEmpty()) {
            return out;
        }
        String name = def.getName();
        for (Map.Entry<String, List<Double>> e : block.radial.entrySet()) {
            String group = e.getKey();
            List<Double> values = e.getValue();
            if (values == null || values.size() != 10) {
                out.add(new Finding(name, ERROR, "radial_wrong_length", group,
                        "structures.radial." + group + " has "
                        + (values == null ? 0 : values.size())
                        + " values; a curve is exactly 10 control points",
                        "give it 10 numbers, evenly spaced from spawn to the border"));
                continue;
            }
            for (int i = 0; i < values.size(); i++) {
                Double v = values.get(i);
                if (v == null || v < 0.0 || v > 3.0) {
                    out.add(new Finding(name, ERROR, "radial_out_of_range", group,
                            "structures.radial." + group + "[" + i + "] is " + v
                            + "; control points are 0.0-3.0",
                            "clamp the value into 0.0-3.0 (1.0 is the group's own density)"));
                }
            }
            boolean allZero = values.stream().allMatch(v -> v != null && v == 0.0);
            if (allZero) {
                out.add(new Finding(name, ERROR, "radial_all_zero", group,
                        "structures.radial." + group
                        + " is 0.0 at every control point, which suppresses the "
                        + "group entirely — no structure in it can generate",
                        "raise at least one control point above 0.0, or remove the "
                        + "group from structures.noise instead of zeroing its curve"));
            }
        }
        return out;
    }

    // --------------------------------------------------------------- portals

    private static List<Finding> checkPortalBlocks(DimensionConfig def) {
        List<Finding> out = new ArrayList<>();
        DimensionConfig.Portal portal = def.getPortal();
        if (portal == null) {
            return out;
        }
        String name = def.getName();
        for (String form : portal.getFrameAcceptForms()) {
            out.addAll(checkBlockForm(name, "portal.frameBlock", form));
        }
        for (Map.Entry<String, List<String>> part : portal.getFramePartAcceptForms().entrySet()) {
            for (String form : part.getValue()) {
                out.addAll(checkBlockForm(
                        name, "portal.frameMaterials." + part.getKey(), form));
            }
        }
        String place = portal.getFrameBlockId();
        if (place != null) {
            out.addAll(checkBlockForm(name, "portal.framePlaceBlock", place));
        }
        String igniter = portal.igniterItem;
        if (igniter != null && !igniter.isBlank()) {
            Identifier id = Identifier.tryParse(igniter.trim().toLowerCase(Locale.ROOT));
            if (id == null || net.minecraft.registry.Registries.ITEM.getOrEmpty(id).isEmpty()) {
                out.add(new Finding(name, ERROR, "portal_igniter_unknown", igniter,
                        "portal.igniterItem " + igniter
                        + " is not in the item registry — the portal can never be lit",
                        "correct the item id, or add the mod that provides it"));
            }
        }
        return out;
    }

    private static List<Finding> checkBlockForm(String dim, String field, String form) {
        if (form == null || form.isBlank() || form.startsWith("#")) {
            // A tag that matches nothing is not an error: tags are datapack
            // content and an empty one is a legitimate (if useless) state.
            return List.of();
        }
        Identifier id = Identifier.tryParse(form.trim().toLowerCase(Locale.ROOT));
        if (id != null && net.minecraft.registry.Registries.BLOCK.getOrEmpty(id).isPresent()) {
            return List.of();
        }
        return List.of(new Finding(dim, ERROR, "portal_block_unknown", form,
                field + " " + form + " is not in the block registry — the frame "
                + "can never be recognised or built",
                "correct the block id, or add the mod that provides it"));
    }

    // ------------------------------------------------------ igniter collision

    private static void collectIgniter(Map<String, List<String>> into, DimensionConfig def) {
        DimensionConfig.Portal portal = def.getPortal();
        if (portal == null || portal.igniterItem == null || portal.igniterItem.isBlank()) {
            return;
        }
        for (String frame : portal.getFrameAcceptForms()) {
            String key = portal.igniterItem.trim().toLowerCase(Locale.ROOT)
                    + " + " + frame.trim().toLowerCase(Locale.ROOT);
            into.computeIfAbsent(key, k -> new ArrayList<>()).add(def.getName());
        }
    }

    /**
     * Sharing an igniter is deliberate and supported — ignition tries every
     * candidate definition, clicked-frame match first. Sharing an igniter AND
     * a frame block is the collision: the two are then indistinguishable, and
     * which dimension a player reaches is decided by config order.
     */
    private static List<Finding> igniterCollisions(Map<String, List<String>> igniters) {
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : igniters.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            String dims = String.join(", ", e.getValue());
            for (String dim : e.getValue()) {
                out.add(new Finding(dim, WARN, "portal_igniter_collision", e.getKey(),
                        "the igniter/frame pair '" + e.getKey() + "' is shared with "
                        + dims + " — a player lighting it reaches whichever "
                        + "definition matches first, not necessarily this one",
                        "give this dimension its own igniterItem or frameBlock"));
            }
        }
        return out;
    }

    // -------------------------------------------------------------- suppress

    /**
     * settings.json's global {@code suppress.structures}/{@code
     * suppress.biomes} removes ids from every dimension's noise pools and
     * biome sources. An id that resolves to nothing suppresses nothing and
     * is a silent authoring fault — the mod has the live registries, so it
     * resolves them directly rather than cross-referencing an extracted
     * catalogue.
     */
    private static List<Finding> checkSuppressList(MinecraftServer server) {
        List<Finding> out = new ArrayList<>();
        Registry<StructureSet> setRegistry = server.getRegistryManager().get(RegistryKeys.STRUCTURE_SET);
        for (String id : unknownSuppressedIds(MultiverseConfig.getInstance().getSuppressedStructureSets(),
                candidate -> setRegistry.getEntry(RegistryKey.of(RegistryKeys.STRUCTURE_SET, candidate)).isPresent())) {
            out.add(new Finding("<global>", ERROR, "suppress_structure_unknown", id,
                    "settings.json suppress.structures names " + id + ", which is not in the "
                    + "structure_set registry — it suppresses nothing",
                    "correct the id, or remove it from suppress.structures"));
        }
        Registry<Biome> biomeRegistry = server.getRegistryManager().get(RegistryKeys.BIOME);
        for (String id : unknownSuppressedIds(MultiverseConfig.getInstance().getSuppressedBiomes(),
                candidate -> biomeRegistry.getEntry(RegistryKey.of(RegistryKeys.BIOME, candidate)).isPresent())) {
            out.add(new Finding("<global>", ERROR, "suppress_biome_unknown", id,
                    "settings.json suppress.biomes names " + id + ", which is not in the biome "
                    + "registry — it suppresses nothing",
                    "correct the id, or remove it from suppress.biomes"));
        }
        return out;
    }

    /**
     * Suppressed ids that fail {@code known} — malformed ids included.
     * Package-private and predicate-driven so the syntax half can be
     * unit-tested without a registry; {@link #checkSuppressList} supplies
     * the live-registry predicate.
     */
    static List<String> unknownSuppressedIds(List<String> suppressed, java.util.function.Predicate<Identifier> known) {
        List<String> unknown = new ArrayList<>();
        for (String raw : suppressed) {
            String trimmed = raw == null ? null : raw.trim();
            if (trimmed == null || trimmed.isEmpty()) {
                continue;
            }
            Identifier id = Identifier.tryParse(trimmed.toLowerCase(Locale.ROOT));
            if (id == null || !known.test(id)) {
                unknown.add(trimmed);
            }
        }
        return unknown;
    }

    // ------------------------------------------------------------------ json

    public static String toJson(List<Finding> findings, int dimensionsChecked,
                                long elapsedMillis) {
        Map<String, Integer> bySeverity = new TreeMap<>();
        Map<String, Integer> byCheck = new TreeMap<>();
        for (Finding f : findings) {
            bySeverity.merge(f.severity(), 1, Integer::sum);
            byCheck.merge(f.check(), 1, Integer::sum);
        }
        StringBuilder json = new StringBuilder(Artefacts.jsonHeader("dimension-lint"));
        json.append(" \"dimensionsChecked\": ").append(dimensionsChecked).append(",\n");
        json.append(" \"elapsedMillis\": ").append(elapsedMillis).append(",\n");
        appendCounts(json, "bySeverity", bySeverity);
        appendCounts(json, "byCheck", byCheck);
        json.append(" \"findings\": [");
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(java.util.Comparator
                .comparing(Finding::dimension)
                .thenComparing(Finding::check)
                .thenComparing(Finding::subject));
        for (int i = 0; i < sorted.size(); i++) {
            Finding f = sorted.get(i);
            json.append(i > 0 ? ",\n  " : "\n  ");
            json.append("{\"dimension\": ").append(quote(f.dimension()));
            json.append(", \"severity\": ").append(quote(f.severity()));
            json.append(", \"check\": ").append(quote(f.check()));
            json.append(", \"subject\": ").append(quote(f.subject()));
            json.append(", \"message\": ").append(quote(f.message()));
            json.append(", \"fix\": ").append(quote(f.fix())).append('}');
        }
        json.append(sorted.isEmpty() ? "]\n}\n" : "\n ]\n}\n");
        return json.toString();
    }

    private static void appendCounts(StringBuilder json, String key, Map<String, Integer> counts) {
        json.append(" \"").append(key).append("\": {");
        int i = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (i++ > 0) {
                json.append(", ");
            }
            json.append(quote(e.getKey())).append(": ").append(e.getValue());
        }
        json.append("},\n");
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
