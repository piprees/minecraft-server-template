package com.customdimensions.command;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Computes getStartChunk positions for pass-through structure sets within
 * a horizon, so Python can parity-test its vanilla grid maths against the
 * live placement calculator per placement type.
 *
 * A pass-through set is one whose placement is neither
 * NoiseStructurePlacement nor FixedStructurePlacement — it keeps its own
 * grid placement unchanged. The census records its positions so the roller
 * can verify whether the Python maths agrees with the live calculator for
 * each placement type.
 */
public final class PassThroughCensus {

    private PassThroughCensus() {
    }

    /** One pass-through set's census data. */
    public record SetCensus(
            String setId,
            String placementType,
            int spacing,
            int separation,
            int salt,
            float frequency,
            List<int[]> positions
    ) {
    }

    /**
     * Computes positions for all pass-through sets in a dimension's
     * structure placement calculator.
     *
     * For each set whose placement is a RandomSpreadStructurePlacement
     * subclass (but not our noise or fixed types), calls getStartChunk
     * over every grid cell whose start chunk falls within the horizon.
     *
     * @param sets         the calculator's structure sets
     * @param worldSeed    the world seed
     * @param horizonChunks the maximum distance from spawn (in chunks)
     *                      to probe; positions beyond this are omitted
     * @return setId -> SetCensus, sorted by set id
     */
    public static Map<String, SetCensus> census(
            Iterable<RegistryEntry<StructureSet>> sets,
            net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator calculator,
            long worldSeed,
            int horizonChunks) {

        Map<String, SetCensus> result = new TreeMap<>();
        for (var entry : sets) {
            var placement = entry.value().placement();
            // Skip noise-managed and fixed placements
            if (placement instanceof com.customdimensions.dimension.NoiseStructurePlacement
                    || placement instanceof com.customdimensions.dimension.FixedStructurePlacement) {
                continue;
            }
            // Only RandomSpreadStructurePlacement and subclasses have
            // spacing/separation/salt and getStartChunk
            if (!(placement instanceof RandomSpreadStructurePlacement random)) {
                continue;
            }
            String setId = entry.getKey()
                    .map(k -> k.getValue().toString()).orElse(null);
            if (setId == null) {
                continue;
            }

            String placementType = resolveType(placement);
            int spacing = random.getSpacing();
            int separation = random.getSeparation();
            int salt = ((com.customdimensions.mixin.StructurePlacementAccessor) placement)
                    .getSaltField();
            float frequency = ((com.customdimensions.mixin.StructurePlacementAccessor) placement)
                    .getFrequencyField();

            // The live gate: shouldGenerate applies the grid maths AND the
            // frequency reduction AND any exclusion zone — bare getStartChunk
            // would emit positions the server never generates for any set
            // with frequency < 1.0.
            List<int[]> positions = probePositions(
                    random, worldSeed, spacing, horizonChunks,
                    (cx, cz) -> placement.shouldGenerate(calculator, cx, cz));

            result.put(setId, new SetCensus(
                    setId, placementType, spacing, separation, salt,
                    frequency, positions));
        }
        return result;
    }

    /**
     * Probes every grid cell whose start chunk falls within the horizon
     * and collects positions the live gate accepts.
     *
     * Grid cells are spacing-sized; a horizon of H chunks at spacing S
     * means probing regions from -(H/S + 2) to +(H/S + 2) in each axis
     * — the +2 accounts for the region offset within get_start_chunk.
     *
     * @param liveGate the placement's own shouldGenerate, bound to the live
     *                 calculator; unit tests pass a synthetic gate
     */
    static List<int[]> probePositions(
            RandomSpreadStructurePlacement placement,
            long worldSeed,
            int spacing,
            int horizonChunks,
            java.util.function.BiPredicate<Integer, Integer> liveGate) {

        if (spacing <= 0 || horizonChunks <= 0) {
            return Collections.emptyList();
        }
        int regionRange = horizonChunks / spacing + 2;
        long horizonSq = (long) horizonChunks * horizonChunks;
        List<int[]> positions = new ArrayList<>();

        for (int rx = -regionRange; rx <= regionRange; rx++) {
            for (int rz = -regionRange; rz <= regionRange; rz++) {
                ChunkPos start = placement.getStartChunk(worldSeed, rx, rz);
                int cx = start.x;
                int cz = start.z;
                long distSq = (long) cx * cx + (long) cz * cz;
                if (distSq <= horizonSq && liveGate.test(cx, cz)) {
                    positions.add(new int[]{cx, cz});
                }
            }
        }
        return positions;
    }

    /**
     * Resolves the registered placement type id for a placement instance.
     * Returns the string form of the registry id, or the class name as
     * a fallback when the type is unregistered.
     */
    static String resolveType(StructurePlacement placement) {
        var typeId = net.minecraft.registry.Registries.STRUCTURE_PLACEMENT
                .getId(placement.getType());
        if (typeId != null) {
            return typeId.toString();
        }
        return placement.getClass().getSimpleName();
    }

    /**
     * Emits the passThrough census as a JSON object fragment.
     * Each set is a keyed entry with placementType, spacing, separation,
     * salt, frequency, and positions array.
     */
    public static String toJson(Map<String, SetCensus> census) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        int count = 0;
        for (var entry : census.entrySet()) {
            if (count++ > 0) {
                json.append(',');
            }
            SetCensus sc = entry.getValue();
            json.append("\n  \"").append(entry.getKey()).append("\": {");
            json.append("\n   \"placementType\": \"").append(sc.placementType()).append("\",");
            json.append("\n   \"spacing\": ").append(sc.spacing()).append(',');
            json.append("\n   \"separation\": ").append(sc.separation()).append(',');
            json.append("\n   \"salt\": ").append(sc.salt()).append(',');
            json.append("\n   \"frequency\": ").append(sc.frequency()).append(',');
            json.append("\n   \"positions\": [");
            for (int i = 0; i < sc.positions().size(); i++) {
                if (i > 0) {
                    json.append(", ");
                }
                int[] pos = sc.positions().get(i);
                json.append('[').append(pos[0]).append(", ").append(pos[1]).append(']');
            }
            json.append("]\n  }");
        }
        json.append(count > 0 ? "\n }" : "}");
        return json.toString();
    }
}
