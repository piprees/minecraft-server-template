package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.StructureTerrainAdaptation;
import net.minecraft.world.gen.structure.Structure;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension terrain-adaptation (Beardifier) overrides.
 *
 * Vanilla reads {@code terrain_adaptation} LIVE from the structure registry
 * during noise fill ({@code StructureWeightSampler.createStructureWeightSampler}),
 * so overriding the value there integrates terrain around every NEWLY
 * generated chunk — all placement types, pass-throughs included, no datapack
 * redeclaration. The getter itself has no world context, so the override is
 * carried by a ThreadLocal armed for exactly the dynamic extent of
 * {@code createStructureWeightSampler} (the forEach that reads adaptations
 * runs synchronously inside it, on the same thread):
 *
 * <ul>
 * <li>{@link com.customdimensions.mixin.StructureWeightSamplerMixin} arms the
 *     calling world's map at HEAD and disarms at RETURN;</li>
 * <li>{@link com.customdimensions.mixin.StructureTerrainAdaptationMixin}
 *     swaps the getter's return value while armed — which also feeds the
 *     {@code != NONE} filter predicate, so a structure whose registry value
 *     is NONE but resolves to a beard is no longer filtered out before its
 *     pieces are collected.</li>
 * </ul>
 *
 * Resolution, most specific first (see {@link #resolveName}):
 * <ol>
 * <li>per-structure key in the dimension's {@code structures.terrainAdaptation}</li>
 * <li>group key (deco/settlements/…) in the same block</li>
 * <li>the jar-baked theme default for the structure's group — applied ONLY
 *     when the registry value is {@code none}: theme defaults fill gaps left
 *     by structure authors, they never overrule an author's explicit
 *     adaptation</li>
 * <li>the structure's own registry value</li>
 * </ol>
 *
 * Maps are installed per world at calculator-rebuild time
 * ({@link DimensionStructures}) and only carry entries that DIFFER from the
 * registry value, keyed by registry-singleton identity so the armed lookup is
 * one IdentityHashMap get. Worlds re-install on every load; a stale entry for
 * an unloaded world is inert (nothing arms it) and is overwritten on reload.
 *
 * Generation-affecting: the resolved values change generated terrain, so the
 * inputs (the dimension's block + the theme-default table) are fingerprinted
 * in dimension_profiles.generation_payload() — change both together.
 */
public final class TerrainAdaptationOverride {

    private static final Map<Identifier, Map<Structure, StructureTerrainAdaptation>> BY_WORLD =
            new ConcurrentHashMap<>();
    private static final ThreadLocal<Map<Structure, StructureTerrainAdaptation>> ARMED =
            new ThreadLocal<>();

    private TerrainAdaptationOverride() {
    }

    /** Installs (or replaces) a world's override map. Empty map = uninstall. */
    public static void install(Identifier worldId,
                               Map<Structure, StructureTerrainAdaptation> map) {
        if (worldId == null) {
            return;
        }
        if (map == null || map.isEmpty()) {
            BY_WORLD.remove(worldId);
        } else {
            BY_WORLD.put(worldId, map);
        }
    }

    /** Arms the current thread with a world's map (null id/unknown world = no-op map). */
    public static void arm(Identifier worldId) {
        ARMED.set(worldId == null ? null : BY_WORLD.get(worldId));
    }

    public static void disarm() {
        ARMED.remove();
    }

    /**
     * The armed override for a structure, or null when unarmed / not
     * overridden — the caller then keeps the vanilla value.
     */
    public static StructureTerrainAdaptation armedOverride(Structure structure) {
        Map<Structure, StructureTerrainAdaptation> map = ARMED.get();
        return map == null ? null : map.get(structure);
    }

    /** Test seam. */
    static void resetForTests() {
        BY_WORLD.clear();
        ARMED.remove();
    }

    /**
     * Pure resolution over NAMES (unit-tested without Bootstrap): returns the
     * resolved adaptation name for one structure, or null for "keep the
     * registry value". {@code registryName} is the structure's own value
     * ("none" when the author set nothing).
     */
    static String resolveName(Map<String, String> dimensionConfig,
                              String structureId, String group,
                              Map<String, String> themeDefaults,
                              String registryName) {
        if (dimensionConfig != null && structureId != null) {
            String exact = dimensionConfig.get(structureId);
            if (exact != null) {
                return exact;
            }
        }
        if (dimensionConfig != null && group != null) {
            String byGroup = dimensionConfig.get(group);
            if (byGroup != null) {
                return byGroup;
            }
        }
        // Theme defaults only FILL a gap ("none") — an author's explicit
        // beard is never overruled by a platform default.
        if ("none".equalsIgnoreCase(registryName) && themeDefaults != null && group != null) {
            return themeDefaults.get(group);
        }
        return null;
    }

    /** Parses an adaptation name; warns and returns null on an unknown one. */
    static StructureTerrainAdaptation parse(String name, String context) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> StructureTerrainAdaptation.NONE;
            case "beard_thin" -> StructureTerrainAdaptation.BEARD_THIN;
            case "beard_box" -> StructureTerrainAdaptation.BEARD_BOX;
            case "bury" -> StructureTerrainAdaptation.BURY;
            case "encapsulate" -> StructureTerrainAdaptation.ENCAPSULATE;
            default -> {
                MultiverseServer.LOGGER.warn(
                        "Unknown terrainAdaptation '{}' ({}) — expected none/beard_thin/"
                        + "beard_box/bury/encapsulate; keeping the registry value",
                        name, context);
                yield null;
            }
        };
    }
}
