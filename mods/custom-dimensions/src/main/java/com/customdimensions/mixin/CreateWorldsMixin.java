package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.dimension.BiomeSuppression;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.dimension.DimensionOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filters vanilla's createWorlds dimension loop down to the BASE WORLDS.
 * The loop iterates Registry.getEntrySet() and creates a ServerWorld for
 * every entry; redirecting getEntrySet() leaves the ~80 custom dimensions
 * to DimensionManager.getOrCreateDimension(), which builds each one when a
 * player first enters it, so DH and c2me only pay for dimensions in use.
 *
 * The base worlds are NOT deferred. Vanilla asks for them by key from paths
 * with no lazy-creation hook — portal travel is ServerWorld.getWorld(NETHER)
 * and takes null at face value — so they must exist from the first tick,
 * exactly as they do without this mod. This mixin is the ONE definition of
 * which worlds boot eagerly.
 *
 * FRAGILE: targets the specific getEntrySet() call inside createWorlds'
 * non-overworld loop (1.21.1 bytecode offset 333). Any MC version that
 * restructures createWorlds will need this remapped. Pinned comment in
 * the mixin config and a build-time class-count check guard against
 * silent breakage.
 */
@Mixin(MinecraftServer.class)
public class CreateWorldsMixin {

    /**
     * The overworld is constructed BEFORE the dimension loop, straight from
     * this registry fetch — the loop redirect below never sees it. Same
     * filter, same construction-only semantics: nothing is written back to
     * the registry or level.dat.
     */
    @Redirect(
        method = "createWorlds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/registry/Registry;get(Lnet/minecraft/registry/RegistryKey;)Ljava/lang/Object;",
            ordinal = 0
        )
    )
    private Object filterOverworldOptions(Registry<DimensionOptions> registry,
                                          RegistryKey<DimensionOptions> key) {
        DimensionOptions options = registry.get(key);
        if (options == null) {
            return null;
        }
        return BiomeSuppression.filterOptions(options, key.getValue().toString());
    }

    @Redirect(
        method = "createWorlds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/registry/Registry;getEntrySet()Ljava/util/Set;",
            ordinal = 0
        )
    )
    private Set<Map.Entry<RegistryKey<DimensionOptions>, DimensionOptions>> filterDimensionLoop(
            Registry<DimensionOptions> registry) {
        Set<Map.Entry<RegistryKey<DimensionOptions>, DimensionOptions>> filtered =
            registry.getEntrySet().stream()
                .filter(entry -> entry.getKey().equals(DimensionOptions.OVERWORLD)
                        || DimensionConfig.BASE_WORLD_IDS.contains(entry.getKey().getValue().toString()))
                // Base worlds are mod-controlled like every other world:
                // suppress.biomes filters the options vanilla constructs
                // from, right here at the one seam that defines them.
                // Construction-only — nothing is written back to the
                // registry or level.dat. The overworld is skipped: vanilla's
                // loop skips it too, and the fetch redirect above already
                // filtered the copy it is actually built from.
                .map(entry -> {
                    if (entry.getKey().equals(DimensionOptions.OVERWORLD)) {
                        return entry;
                    }
                    DimensionOptions withSuppression = BiomeSuppression.filterOptions(
                            entry.getValue(), entry.getKey().getValue().toString());
                    return withSuppression == entry.getValue() ? entry
                            : Map.entry(entry.getKey(), withSuppression);
                })
                .collect(Collectors.toSet());

        int skipped = registry.getEntrySet().size() - filtered.size();
        if (skipped > 0) {
            MultiverseServer.LOGGER.info(
                "Lazy world creation: {} custom dimension(s) deferred to first entry, "
                + "{} base world(s) created now", skipped, filtered.size());
        }
        return filtered;
    }
}
