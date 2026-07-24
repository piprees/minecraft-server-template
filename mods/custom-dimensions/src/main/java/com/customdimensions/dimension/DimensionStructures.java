package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.mixin.StructurePlacementAccessor;
import com.customdimensions.mixin.StructurePlacementCalculatorInvoker;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-dimension structure control ("structureDensity" in
 * multiverse_config.json: dense | normal | sparse | none), plus the automatic
 * peaceful overlay for hostileSpawning:false dimensions.
 *
 * Applied by rebuilding the world's StructurePlacementCalculator with a
 * transformed structure-set list (ServerChunkLoadingManagerMixin). Placement
 * copies are unregistered direct entries scoped to this one world — the
 * global registry objects are never mutated, so every other dimension keeps
 * the shared placements.
 *
 * Caveats honoured (customising-structures.md):
 * - Only exact minecraft:random_spread placements are rescaled. Custom
 *   placement types (YUNG's, Moog's) and concentric rings pass through
 *   unchanged — dropping whole sets is type-agnostic and still applies.
 * - Theme knowledge comes from the jar-baked structure_themes.json; sets
 *   without a theme are only affected by "none".
 */
public final class DimensionStructures {

    private DimensionStructures() {
    }

    /**
     * Returns null when the world needs no transformed calculator. The biome
     * source is passed explicitly — this runs inside the chunk manager's
     * constructor, before world.getChunkManager() is assigned.
     */
    public static StructurePlacementCalculator transformed(ServerWorld world, BiomeSource biomeSource,
            NoiseConfig noiseConfig, StructurePlacementCalculator original) {
        Identifier key = world.getRegistryKey().getValue();
        if (!MultiverseConfig.getInstance().isManagedNamespace(key.getNamespace())) {
            return null;
        }
        DimensionConfig def = com.customdimensions.dimension.DimensionManager.getInstance().resolveDefinition(key.getPath());
        if (def == null) {
            return null;
        }
        String density = normalizedDensity(def);
        boolean peaceful = !def.isHostileSpawningEnabled();
        DimensionConfig.Structures structBlock = def.getStructures();
        java.util.Map<String, DimensionConfig.SpacingOverride> spacingOverrides =
                structBlock != null && structBlock.spacing != null
                        ? structBlock.spacing : java.util.Map.of();
        String mode = normalizedMode(def.getName(), structBlock);
        java.util.Set<String> modeList = structBlock != null && structBlock.list != null
                ? new java.util.HashSet<>(structBlock.list) : java.util.Set.of();
        java.util.List<DimensionConfig.ForcedStructure> forced =
                structBlock != null && structBlock.force != null
                        ? structBlock.force : java.util.List.of();
        if ("normal".equals(density) && !peaceful && spacingOverrides.isEmpty()
                && !def.hasExitShrines() && mode == null && forced.isEmpty()) {
            return null;
        }
        List<RegistryEntry<StructureSet>> transformed = new ArrayList<>();
        int dropped = 0;
        int rescaled = 0;
        for (RegistryEntry<StructureSet> entry : original.getStructureSets()) {
            if ("none".equals(density)) {
                dropped++;
                continue;
            }
            String setId = entry.getKey().map(k -> k.getValue().toString()).orElse(null);
            String theme = setId != null ? StructureThemes.themeOf(setId) : null;

            // Exit shrines ship with a near-zero frequency so they can never
            // generate in worlds that bypass this rebuild (base worlds).
            // Opted-in dims get the full-frequency copy; everyone else keeps
            // the effectively-off original. Exempt from every theme factor.
            if ("adventure:exit_shrines".equals(setId)) {
                if (def.hasExitShrines()) {
                    StructureSet set = entry.value();
                    // ships at frequency 0.001; x1000 -> min(1.0, ...) = full
                    StructurePlacement full = rescale(set.placement(), 1.0, 1000.0);
                    if (full != null) {
                        // Explicit structures.spacing wins; otherwise the
                        // spacing derives from the playable border — a
                        // 256-radius pocket wants 1-2 shrines, not a grid.
                        // MIRRORED in scripts/seed/fast_roller.py (tier 1);
                        // change both together or shrine scoring lies.
                        DimensionConfig.SpacingOverride ov = spacingOverrides.get(setId);
                        if (ov == null) {
                            ov = derivedShrineSpacing(def.getPlayerBorderRadius());
                        }
                        StructurePlacement spaced = withExplicitSpacing(def, setId, full, ov);
                        if (spaced != null) {
                            full = spaced;
                        }
                        transformed.add(RegistryEntry.of(new StructureSet(set.structures(), full)));
                        rescaled++;
                        continue;
                    }
                }
                transformed.add(entry);
                continue;
            }

            // Organic-set filter (structures.mode): applied after the
            // exit-shrines opt-in (which is config-driven, not organic) and
            // before every density/theme path. Forced placements are
            // synthetic sets appended after the loop — mode never touches
            // them ("mode": "none" + force = ONLY the forced structures).
            if (mode != null) {
                boolean keep = switch (mode) {
                    case "allow" -> setId != null && modeList.contains(setId);
                    case "reject" -> setId == null || !modeList.contains(setId);
                    default -> false; // "none"
                };
                if (!keep) {
                    dropped++;
                    continue;
                }
            }

            if (peaceful && "dungeon".equals(theme)) {
                dropped++;
                continue;
            }

            // Explicit per-set override (structures.spacing) wins over the
            // theme-based density factors — but never resurrects a set the
            // peaceful/none drops above already removed.
            DimensionConfig.SpacingOverride override = setId != null ? spacingOverrides.get(setId) : null;
            if (override != null) {
                StructureSet set = entry.value();
                StructurePlacement explicit = withExplicitSpacing(def, setId, set.placement(), override);
                if (explicit != null) {
                    transformed.add(RegistryEntry.of(new StructureSet(set.structures(), explicit)));
                    rescaled++;
                    continue;
                }
                // invalid values or custom placement type: warned inside,
                // fall through to the normal theme path
            }

            double spacingFactor = 1.0;
            double frequencyFactor = 1.0;
            if ("dense".equals(density)) {
                if ("dungeon".equals(theme) || "loot".equals(theme)) {
                    spacingFactor = 0.7; // ~2x density
                } else if ("landmark".equals(theme) || "maritime".equals(theme)) {
                    spacingFactor = 0.85;
                }
            } else if ("sparse".equals(density)) {
                if ("dungeon".equals(theme) || "loot".equals(theme) || "landmark".equals(theme)) {
                    frequencyFactor = 0.5;
                } else if ("settlement".equals(theme) || "maritime".equals(theme)) {
                    frequencyFactor = 0.7;
                }
            }
            if (peaceful && ("settlement".equals(theme) || "maritime".equals(theme)
                    || "landmark".equals(theme) || "loot".equals(theme))) {
                // Rare villages, ships and fun stuff — nothing to fight.
                frequencyFactor *= 0.3;
            }

            if (spacingFactor == 1.0 && frequencyFactor == 1.0) {
                transformed.add(entry);
                continue;
            }
            StructureSet set = entry.value();
            StructurePlacement scaled = rescale(set.placement(), spacingFactor, frequencyFactor);
            if (scaled == null) {
                transformed.add(entry); // custom placement type — caveat: pass through
                continue;
            }
            transformed.add(RegistryEntry.of(new StructureSet(set.structures(), scaled)));
            rescaled++;
        }

        // Fixed placements (structures.force): one synthetic single-structure
        // set per forced structure id, positioned by FixedStructurePlacement.
        // Unknown structure ids (e.g. from a removed mod) warn and skip —
        // never a boot break (optional-mods promise).
        int forcedCount = 0;
        if (!forced.isEmpty()) {
            var structureRegistry = world.getRegistryManager()
                    .get(net.minecraft.registry.RegistryKeys.STRUCTURE);
            java.util.Map<Identifier, java.util.List<net.minecraft.util.math.ChunkPos>> byStructure =
                    new java.util.LinkedHashMap<>();
            for (DimensionConfig.ForcedStructure f : forced) {
                if (f == null || f.structure == null || f.x == null || f.z == null) {
                    MultiverseServer.LOGGER.warn(
                            "Dimension {}: structures.force entry missing structure/x/z — skipped",
                            def.getName());
                    continue;
                }
                Identifier sid = Identifier.tryParse(f.structure);
                if (sid == null) {
                    MultiverseServer.LOGGER.warn(
                            "Dimension {}: structures.force id '{}' is not a valid identifier — skipped",
                            def.getName(), f.structure);
                    continue;
                }
                byStructure.computeIfAbsent(sid, k -> new java.util.ArrayList<>())
                        .add(new net.minecraft.util.math.ChunkPos(f.x >> 4, f.z >> 4));
            }
            for (var e : byStructure.entrySet()) {
                var entry = structureRegistry.getEntry(
                        net.minecraft.registry.RegistryKey.of(
                                net.minecraft.registry.RegistryKeys.STRUCTURE, e.getKey()));
                if (entry.isEmpty()) {
                    MultiverseServer.LOGGER.warn(
                            "Dimension {}: forced structure {} not in the registry (mod removed?) — skipped",
                            def.getName(), e.getKey());
                    continue;
                }
                transformed.add(RegistryEntry.of(new StructureSet(
                        entry.get(), new FixedStructurePlacement(e.getValue()))));
                forcedCount += e.getValue().size();
                MultiverseServer.LOGGER.info(
                        "Dimension {}: forced {} at chunk(s) {}",
                        def.getName(), e.getKey(), e.getValue());
            }
        }

        MultiverseServer.LOGGER.info(
                "Dimension {} structure profile: density={}{}{}{} ({} sets kept, {} rescaled, {} dropped)",
                def.getName(), density, peaceful ? "+peaceful" : "",
                mode != null ? "+mode=" + mode : "",
                forcedCount > 0 ? "+" + forcedCount + " forced" : "",
                transformed.size(), rescaled, dropped);
        return StructurePlacementCalculatorInvoker.invokeNew(
                noiseConfig, biomeSource, original.getStructureSeed(), original.getStructureSeed(), transformed);
    }

    /** Validated structures.mode: allow | reject | none, or null (off).
     *  Package-private for unit tests (same pattern as derivedShrineSpacing). */
    static String normalizedMode(String dimName, DimensionConfig.Structures block) {
        if (block == null || block.mode == null || block.mode.isEmpty()) {
            return null;
        }
        String mode = block.mode.toLowerCase();
        switch (mode) {
            case "allow":
            case "reject":
            case "none":
                return mode;
            default:
                MultiverseServer.LOGGER.warn(
                        "Unknown structures.mode '{}' on dimension {} — ignoring the filter",
                        block.mode, dimName);
                return null;
        }
    }

    private static String normalizedDensity(DimensionConfig def) {
        String density = def.getStructureDensity();
        if (density == null || density.isEmpty()) {
            return "normal";
        }
        String normalized = density.toLowerCase();
        switch (normalized) {
            case "dense":
            case "normal":
            case "sparse":
            case "none":
                return normalized;
            default:
                MultiverseServer.LOGGER.warn(
                        "Unknown structureDensity '{}' on dimension {} — using normal",
                        density, def.getName());
                return "normal";
        }
    }

    // Explicit spacing/separation for one set (structures.spacing override).
    /**
     * Automatic shrine spacing from the playable border: roughly
     * radius-in-chunks / 2, clamped 12..48. Pure — unit-tested, and
     * mirrored bit-for-bit in scripts/seed/fast_roller.py (roller parity).
     */
    static DimensionConfig.SpacingOverride derivedShrineSpacing(int playerBorderRadiusBlocks) {
        DimensionConfig.SpacingOverride out = new DimensionConfig.SpacingOverride();
        out.spacing = Math.max(12, Math.min(48, playerBorderRadiusBlocks / 32));
        out.separation = out.spacing / 2;
        return out;
    }

    // Same random_spread-only constraint as rescale; invariants enforced
    // (spacing >= 2, 0 <= separation < spacing — vanilla's codec is strict
    // about separation < spacing, violating it crashes placement).
    private static StructurePlacement withExplicitSpacing(DimensionConfig def, String setId,
            StructurePlacement placement, DimensionConfig.SpacingOverride override) {
        if (placement.getClass() != RandomSpreadStructurePlacement.class) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: structures.spacing for {} ignored — custom placement type {}",
                    def.getName(), setId, placement.getClass().getSimpleName());
            return null;
        }
        RandomSpreadStructurePlacement random = (RandomSpreadStructurePlacement) placement;
        StructurePlacementAccessor base = (StructurePlacementAccessor) placement;
        int spacing = override.spacing != null ? override.spacing : random.getSpacing();
        int separation = override.separation != null ? override.separation : random.getSeparation();
        if (spacing < 2 || spacing > 4096 || separation < 0 || separation >= spacing) {
            MultiverseServer.LOGGER.warn(
                    "Dimension {}: structures.spacing for {} invalid (spacing: {}, separation: {} — "
                    + "need 2 <= spacing <= 4096, 0 <= separation < spacing) — using the theme-based path",
                    def.getName(), setId, spacing, separation);
            return null;
        }
        return new RandomSpreadStructurePlacement(
                base.getLocateOffsetField(),
                base.getFrequencyReductionMethodField(),
                base.getFrequencyField(),
                base.getSaltField(),
                base.getExclusionZoneField(),
                spacing,
                separation,
                random.getSpreadType());
    }

    // Only exact random_spread placements can be rescaled generically; any
    // subclass (custom placement type) returns null and passes through.
    private static StructurePlacement rescale(StructurePlacement placement,
            double spacingFactor, double frequencyFactor) {
        if (placement.getClass() != RandomSpreadStructurePlacement.class) {
            return null;
        }
        RandomSpreadStructurePlacement random = (RandomSpreadStructurePlacement) placement;
        StructurePlacementAccessor base = (StructurePlacementAccessor) placement;
        int spacing = Math.max(2, (int) Math.round(random.getSpacing() * spacingFactor));
        int separation = Math.min(spacing - 1,
                Math.max(0, (int) Math.round(random.getSeparation() * spacingFactor)));
        float frequency = (float) Math.min(1.0, base.getFrequencyField() * frequencyFactor);
        return new RandomSpreadStructurePlacement(
                base.getLocateOffsetField(),
                base.getFrequencyReductionMethodField(),
                frequency,
                base.getSaltField(),
                base.getExclusionZoneField(),
                spacing,
                separation,
                random.getSpreadType());
    }
}
