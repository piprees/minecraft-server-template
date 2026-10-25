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

    /** One allowed mod id, or every id starting with {@code id} when {@code prefix}. {@code owner} marks an entry the owner has not ruled on. */
    public record Entry(String id, boolean prefix, String reason, boolean owner) {
        boolean matches(String modId) {
            return prefix ? modId.startsWith(id) : modId.equals(id);
        }
    }

    public static final List<Entry> MODS = List.of(
            new Entry("c2me", true, "performance: parallel and allocation-free vanilla generation, same output", false),
            new Entry("lithium", false, "performance: caches generator settings and biome temperatures, same output", false),
            new Entry("servercore", false, "performance: chunk-ticket handling in ChunkGenerator, same output", false),
            new Entry("fabric-", true, "Fabric API: its worldgen hooks act only for mods that call them, and the biome check removes their additions", false),
            new Entry("customdimensions", false, "this mod", false),
            new Entry("fantasy", false, "runtime levels, nested in this mod (RB-10)", false),
            new Entry("distanthorizons", false, "LOD generation hook in ChunkGenerator; on the 26.3 list, awaiting the owner", true),
            new Entry("moogs_structures", false, "structure library: replaces vanilla structures it configures, Beardifier terrain adaptation; on the 26.3 list, awaiting the owner", true),
            new Entry("carpet", false, "admin tool whose ChunkGenerator, feature and template hooks sit behind off-by-default rules; on the 26.3 list, awaiting the owner", true));

    /** Namespaces this pack owns: vanilla's, the compiled pack's and Fantasy's runtime dimension types. */
    public static final Set<String> NAMESPACES = Set.of("minecraft", "adventure", "customdimensions", "fantasy");

    /** Worldgen frameworks: their presence alone is refused. */
    public static final Set<String> FORBIDDEN = Set.of("terrablender", "biolith", "lithostitched");

    private AllowList() {}

    public static Optional<Entry> find(String modId) {
        return MODS.stream().filter(e -> e.matches(modId)).findFirst();
    }
}
