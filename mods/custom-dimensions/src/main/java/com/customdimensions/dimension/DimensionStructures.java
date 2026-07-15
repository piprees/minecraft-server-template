package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionDefinition;
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
        if (!DimensionDefinition.getNamespace().equals(key.getNamespace())) {
            return null;
        }
        DimensionDefinition def = MultiverseConfig.getInstance().getDimension(key.getPath());
        if (def == null) {
            return null;
        }
        String density = normalizedDensity(def);
        boolean peaceful = !def.isHostileSpawningEnabled();
        if ("normal".equals(density) && !peaceful) {
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

            if (peaceful && "dungeon".equals(theme)) {
                dropped++;
                continue;
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

        MultiverseServer.LOGGER.info(
                "Dimension {} structure profile: density={}{} ({} sets kept, {} rescaled, {} dropped)",
                def.getName(), density, peaceful ? "+peaceful" : "",
                transformed.size(), rescaled, dropped);
        return StructurePlacementCalculatorInvoker.invokeNew(
                noiseConfig, biomeSource, original.getStructureSeed(), original.getStructureSeed(), transformed);
    }

    private static String normalizedDensity(DimensionDefinition def) {
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
