package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a dimension's config plus its world type's defaults into "which
 * groups are active, at what profile, with what radial curve and exclusion".
 *
 * Pure — no world, no registry, no Bootstrap — so the whole precedence chain
 * is unit-testable. {@code DimensionStructures} does the registry work of
 * filling the groups with structures.
 *
 * <h2>Precedence, most specific first</h2>
 *
 * <ol>
 * <li>{@code structures.noise.<group>} — per-group override</li>
 * <li>{@code structures.noise} as a string — every group</li>
 * <li>{@code structureDensity} — {@code none} kills noise outright; the other
 *     values map onto profile names ({@code normal} means "type defaults",
 *     not a profile)</li>
 * <li>difficulty shifts from {@code difficulty.mobMultiplier}</li>
 * <li>{@code types.<type>.profiles.<group>} from structure_type_defaults</li>
 * <li>{@code groupDefaults.<group>.profile}</li>
 * </ol>
 *
 * Radial curves follow the same shape, with {@code structures.radial} taking
 * a raw 10-point array in place of a named curve.
 */
public final class NoiseGroupPlan {

    /** One active group's resolved placement settings. */
    public record Group(String name, NoiseProfile profile, double[] radial, int exclusion) {
    }

    private final Map<String, Group> groups;
    private final boolean suppressed;
    private final boolean noStructures;
    private final String reason;

    private NoiseGroupPlan(Map<String, Group> groups, boolean suppressed,
                           boolean noStructures, String reason) {
        this.groups = groups;
        this.suppressed = suppressed;
        this.noStructures = noStructures;
        this.reason = reason;
    }

    public Map<String, Group> groups() {
        return groups;
    }

    /** True when no noise runs at all for this dimension. */
    public boolean isSuppressed() {
        return suppressed;
    }

    /**
     * True when the suppression means "this dimension has no organic
     * structures at all" — the type enables no groups (void, superflat, an
     * unknown type). The legacy path must then DROP every set: returning the
     * vanilla calculator intact would generate all 367 sets on vanilla grids
     * in a dimension whose type says none belong. Density "none" and the
     * grid escape hatch keep their own meanings (drop-all and keep-vanilla
     * respectively), so they stay false here.
     */
    public boolean suppressesAllSets() {
        return noStructures;
    }

    /** Human-readable why, for the boot log line. */
    public String reason() {
        return reason;
    }

    public static NoiseGroupPlan resolve(DimensionConfig def) {
        String worldType = def.getType();
        DimensionConfig.Structures block = def.getStructures();
        String density = normaliseDensity(def);

        if ("none".equals(density)) {
            return new NoiseGroupPlan(Map.of(), true, false, "structureDensity=none");
        }
        // Deprecated alias: structures.mode "none" meant the same thing before
        // noise existed. Honoured so the_dustbowl-style configs keep working.
        if (block != null && block.mode != null
                && "none".equalsIgnoreCase(block.mode.trim())) {
            return new NoiseGroupPlan(Map.of(), true, false, "structures.mode=none (deprecated)");
        }
        // Escape hatch for one major version: force the old grid behaviour.
        if (block != null && block.noise != null && isBooleanFalse(block.noise)) {
            return new NoiseGroupPlan(Map.of(), true, false, "structures.noise=false (grid mode)");
        }

        var defaults = StructureGroupRegistry.defaults();

        // A group named explicitly under `structures.noise` is ADDED, not just
        // re-profiled. The world type's list is a DEFAULT, and an author who
        // writes {"endgame": "sparse"} on a cave dimension has said what they
        // want plainly — iterating the type list alone would silently ignore
        // them whenever the type's own list omits that group.
        List<String> enabled = new ArrayList<>(StructureGroupRegistry.groupsForType(worldType));
        for (String named : explicitGroups(block)) {
            if (!enabled.contains(named) && StructureGroupRegistry.groupDefault(named) != null) {
                enabled.add(named);
            }
        }
        if (enabled.isEmpty()) {
            return new NoiseGroupPlan(Map.of(), true, true,
                    worldType == null ? "no world type" : "type " + worldType + " enables no groups");
        }

        var typeEntry = defaults.types().get(worldType);
        double mobMultiplier = def.getDifficulty() != null
                ? def.getDifficulty().getMobMultiplier() : 1.0;

        // The global string form, and structureDensity's non-"normal" values,
        // both mean "every group uses this profile".
        String globalProfileName = null;
        if (block != null && block.noise != null && block.noise.isJsonPrimitive()
                && block.noise.getAsJsonPrimitive().isString()) {
            globalProfileName = block.noise.getAsString();
        } else if (!"normal".equals(density)) {
            globalProfileName = density;
        }

        Map<String, Group> resolved = new LinkedHashMap<>();
        for (String group : enabled) {
            var groupDefault = StructureGroupRegistry.groupDefault(group);
            if (groupDefault == null) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: group {} has no defaults — skipped", def.getName(), group);
                continue;
            }

            String profileName = groupDefault.profile();
            if (typeEntry != null && typeEntry.profiles().containsKey(group)) {
                profileName = typeEntry.profiles().get(group);
            }
            if (globalProfileName != null) {
                profileName = globalProfileName;
            }
            // The peaceful shift sits ABOVE structureDensity, not below it as
            // the spike's precedence list has it. A coarse density dial must
            // not resurrect a group the dimension's own difficulty says does
            // not exist there: the_luminous_caverns has mobMultiplier 0.0 and
            // structureDensity "sparse", and structureDensity alone would put
            // its dungeons straight back. The rule that reads correctly is "a
            // peaceful world has no dungeons unless the author names a
            // profile for dungeons specifically" — so only the per-group
            // override below can undo it.
            if (mobMultiplier <= defaults.peacefulMaxMobMultiplier()
                    && defaults.peacefulProfiles().containsKey(group)) {
                profileName = defaults.peacefulProfiles().get(group);
            }
            String perGroup = perGroupNoise(block, group);
            if (perGroup != null) {
                profileName = perGroup;
            }

            final String groupName = group;
            NoiseProfile profile = NoiseProfile.fromString(profileName, bad ->
                    MultiverseServer.LOGGER.warn(
                            "Dimension {}: unknown noise profile '{}' for group {} — "
                            + "group suppressed", def.getName(), bad, groupName));
            if (profile == null) {
                continue;   // "none", or an unknown name already warned about
            }

            double[] radial = resolveRadial(def, block, group, typeEntry, groupDefault,
                    defaults, mobMultiplier);
            int exclusion = Math.max(1, (int) Math.round(
                    groupDefault.exclusion() * profile.exclusionMultiplier()));
            resolved.put(group, new Group(group, profile, radial, exclusion));
        }

        if (resolved.isEmpty()) {
            return new NoiseGroupPlan(Map.of(), true, false, "every group resolved to none");
        }
        return new NoiseGroupPlan(Map.copyOf(resolved), false, false, "noise");
    }

    private static double[] resolveRadial(DimensionConfig def, DimensionConfig.Structures block,
                                         String group,
                                         StructureGroupRegistry.TypeEntry typeEntry,
                                         StructureGroupRegistry.GroupDefault groupDefault,
                                         StructureGroupRegistry.TypeDefaults defaults,
                                         double mobMultiplier) {
        // Explicit curve wins outright.
        if (block != null && block.radial != null && block.radial.containsKey(group)) {
            double[] explicit = toCurve(def.getName(), group, block.radial.get(group));
            if (explicit != null) {
                return explicit;
            }
        }
        String curveName = groupDefault.radial();
        if (typeEntry != null && typeEntry.radial().containsKey(group)) {
            curveName = typeEntry.radial().get(group);
        }
        if (mobMultiplier >= defaults.hostileMinMobMultiplier()
                && defaults.hostileRadial().containsKey(group)) {
            curveName = defaults.hostileRadial().get(group);
        }
        double[] curve = StructureGroupRegistry.curve(curveName);
        if (curve == null) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: unknown radial curve '{}' for group {} — using even",
                    def.getName(), curveName, group);
            return null;   // null means uniform in NoiseFieldIndex
        }
        return curve;
    }

    /** Validates a config-supplied curve; null (with a warning) when unusable. */
    static double[] toCurve(String dimName, String group, List<Double> values) {
        if (values == null) {
            return null;
        }
        if (values.size() != 10) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: structures.radial.{} has {} points, expected 10 — "
                    + "using the type default", dimName, group, values.size());
            return null;
        }
        double[] curve = new double[10];
        for (int i = 0; i < 10; i++) {
            Double v = values.get(i);
            if (v == null || v.isNaN() || v < 0.0 || v > 3.0) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: structures.radial.{}[{}] = {} is outside 0.0-3.0 — "
                        + "using the type default", dimName, group, i, v);
                return null;
            }
            curve[i] = v;
        }
        return curve;
    }

    /**
     * Group names the author wrote under `structures.noise`, in config order.
     * A `"none"` entry is included: it names the group, and resolution below
     * turns it into a suppression rather than a group that never existed.
     */
    private static List<String> explicitGroups(DimensionConfig.Structures block) {
        if (block == null || block.noise == null || !block.noise.isJsonObject()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String key : block.noise.getAsJsonObject().keySet()) {
            JsonElement value = block.noise.getAsJsonObject().get(key);
            if (value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isString()) {
                out.add(key);
            }
        }
        return out;
    }

    private static String perGroupNoise(DimensionConfig.Structures block, String group) {
        if (block == null || block.noise == null || !block.noise.isJsonObject()) {
            return null;
        }
        JsonElement value = block.noise.getAsJsonObject().get(group);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    private static boolean isBooleanFalse(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                && !element.getAsBoolean();
    }

    /**
     * Warns about group names in structures.noise / structures.radial that no
     * world type knows, so a typo does not silently do nothing. Separate from
     * resolve() because it is diagnostics, not behaviour.
     */
    public static void warnUnknownGroups(DimensionConfig def) {
        DimensionConfig.Structures block = def.getStructures();
        if (block == null) {
            return;
        }
        var known = StructureGroupRegistry.knownGroups();
        if (block.noise != null && block.noise.isJsonObject()) {
            for (String key : block.noise.getAsJsonObject().keySet()) {
                if (!known.contains(key)) {
                    MultiverseServer.LOGGER.warn(
                            "Dimension {}: structures.noise names unknown group '{}' — ignored "
                            + "(known: {})", def.getName(), key, known);
                }
            }
        }
        if (block.radial != null) {
            for (String key : block.radial.keySet()) {
                if (!known.contains(key)) {
                    MultiverseServer.LOGGER.warn(
                            "Dimension {}: structures.radial names unknown group '{}' — ignored",
                            def.getName(), key);
                }
            }
        }
    }

    /** structureDensity, lowercased and validated. Never null. */
    static String normaliseDensity(DimensionConfig def) {
        String density = def.getStructureDensity();
        if (density == null || density.isEmpty()) {
            return "normal";
        }
        String normalised = density.toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "dense", "normal", "sparse", "none" -> normalised;
            default -> {
                MultiverseServer.LOGGER.warn(
                        "Unknown structureDensity '{}' on dimension {} — using normal",
                        density, def.getName());
                yield "normal";
            }
        };
    }
}
