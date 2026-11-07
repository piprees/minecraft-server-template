package com.customdimensions.core.firewall;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Who may own part of world generation besides this mod. {@link #MODS} lists
 * the mods whose mixins into {@link WorldgenClasses} pass; {@link #NAMESPACES}
 * the registry namespaces whose dimensions, noise settings and biome sources
 * pass; {@link #FORBIDDEN} the worldgen frameworks that never pass without a bridge.
 */
public final class AllowList {

    /** One allowed mod id, or every id starting with {@code id} when {@code prefix}. */
    public record Entry(String id, boolean prefix, String reason) {
        boolean matches(String modId) {
            return prefix ? modId.startsWith(id) : modId.equals(id);
        }
    }

    public static final List<Entry> MODS = List.of(
            new Entry("c2me", true, "performance: parallel and allocation-free vanilla generation, same output"),
            new Entry("lithium", false, "performance: caches generator settings and biome temperatures, same output"),
            new Entry("servercore", false, "performance: chunk-ticket handling in ChunkGenerator, same output"),
            new Entry("fabric-", true, "Fabric API: its worldgen hooks act only for mods that call them, and the biome check removes their additions"),
            new Entry("customdimensions", false, "this mod"),
            new Entry("fantasy", false, "runtime levels, nested in this mod (RB-10)"),
            new Entry("distanthorizons", false, "LOD generation hook in ChunkGenerator: reads terrain, does not shape it"),
            new Entry("moogs_structures", false, "structure library the Moog structure packs need: replaces vanilla structures it configures, Beardifier terrain adaptation"),
            new Entry("carpet", false, "admin tool and the self-tests' fake players; its ChunkGenerator, feature and template hooks sit behind off-by-default rules"));

    /** Namespaces this pack owns: vanilla's, the compiled pack's and Fantasy's runtime dimension types. */
    public static final Set<String> NAMESPACES = Set.of("minecraft", "adventure", "customdimensions", "fantasy");

    /** Worldgen frameworks: their presence alone is refused. */
    public static final Set<String> FORBIDDEN = Set.of("terrablender", "biolith", "lithostitched");

    private AllowList() {}

    public static Optional<Entry> find(String modId) {
        return MODS.stream().filter(e -> e.matches(modId)).findFirst();
    }
}
