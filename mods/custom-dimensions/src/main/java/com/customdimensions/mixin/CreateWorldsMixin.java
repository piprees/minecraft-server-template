package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import com.customdimensions.dimension.DimensionManager;
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
 * Filters vanilla's createWorlds dimension loop to skip non-overworld
 * dimensions. The loop iterates Registry.getEntrySet() and creates a
 * ServerWorld for every entry — by redirecting getEntrySet() to return
 * only the overworld entry, we prevent vanilla from eagerly creating
 * worlds for nether/end/paradise_lost and all custom dimensions.
 *
 * Those worlds are created lazily by DimensionManager.getOrCreateDimension()
 * when a player enters via portal or command.
 *
 * FRAGILE: targets the specific getEntrySet() call inside createWorlds'
 * non-overworld loop (1.21.1 bytecode offset 333). Any MC version that
 * restructures createWorlds will need this remapped. Pinned comment in
 * the mixin config and a build-time class-count check guard against
 * silent breakage.
 */
@Mixin(MinecraftServer.class)
public class CreateWorldsMixin {

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
        // Return only the overworld entry — vanilla's loop skips it anyway
        // (it checks key == OVERWORLD and continues), but including it is
        // harmless and keeps the filter simple. Everything else is deferred.
        Set<Map.Entry<RegistryKey<DimensionOptions>, DimensionOptions>> filtered =
            registry.getEntrySet().stream()
                .filter(entry -> entry.getKey().equals(DimensionOptions.OVERWORLD))
                .collect(Collectors.toSet());

        int skipped = registry.getEntrySet().size() - filtered.size();
        if (skipped > 0) {
            MultiverseServer.LOGGER.info(
                "Lazy world creation: skipped {} non-overworld dimensions in vanilla's boot loop " +
                "(will be created on first player entry)", skipped);
        }
        return filtered;
    }
}
