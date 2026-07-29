package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import com.customdimensions.dimension.ForcedBiomeBypass;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructureSet;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Half two of the forced-placement biome bypass: replace the biome predicate
 * with one that always passes, for the start attempts
 * {@link StructurePlacementForcedBiomeMixin} armed.
 *
 * 1.21.1 builds the predicate inline in {@code trySetStructureStart}:
 *
 * <pre>
 *   RegistryEntryList&lt;Biome&gt; registryEntryList = structure.getValidBiomes();
 *   Predicate&lt;RegistryEntry&lt;Biome&gt;&gt; predicate = registryEntryList::contains;
 *   StructureStart structureStart = structure.createStructureStart(
 *       ..., chunk, predicate);
 * </pre>
 *
 * so the tenth argument of that {@code createStructureStart} call is the only
 * place the check can be turned off without touching how any other structure
 * generates. {@code structures.force} means "put THIS structure at THIS spot";
 * leaving the biome gate in made the feature silently do nothing whenever the
 * spot's biome was not on the structure's list.
 *
 * Not affected, deliberately: {@code /locate}, which reads
 * {@code StructurePlacementCalculator.getPlacements} — that map is built by
 * {@code calculate()}, which indexes a structure only when its valid biomes
 * intersect the dimension's biome source. A forced structure whose biomes the
 * dimension does not contain now GENERATES but is still not locatable. Use
 * {@code /customdim structure-census} to see it.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorForcedBiomeMixin {

    private static final Predicate<RegistryEntry<Biome>> ANY_BIOME = biomeEntry -> true;

    @ModifyArg(
            method = "trySetStructureStart",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/structure/Structure;createStructureStart("
                            + "Lnet/minecraft/registry/DynamicRegistryManager;"
                            + "Lnet/minecraft/world/gen/chunk/ChunkGenerator;"
                            + "Lnet/minecraft/world/biome/source/BiomeSource;"
                            + "Lnet/minecraft/world/gen/noise/NoiseConfig;"
                            + "Lnet/minecraft/structure/StructureTemplateManager;"
                            + "JLnet/minecraft/util/math/ChunkPos;I"
                            + "Lnet/minecraft/world/HeightLimitView;"
                            + "Ljava/util/function/Predicate;"
                            + ")Lnet/minecraft/structure/StructureStart;"),
            index = 9)
    private Predicate<RegistryEntry<Biome>> customdimensions$bypassForcedBiome(
            Predicate<RegistryEntry<Biome>> vanilla) {
        String dimensionName = ForcedBiomeBypass.armed();
        // Written every attempt, forced or not, so it can never go stale.
        ForcedBiomeBypass.markApplied(dimensionName);
        return dimensionName != null ? ANY_BIOME : vanilla;
    }

    /**
     * One INFO line the first time each forced position actually produces a
     * structure, so the bypass is visible in the server log rather than only
     * in a census file. The return value is vanilla's own "it has children"
     * answer, so this never claims a start that did not happen.
     */
    @Inject(method = "trySetStructureStart", at = @At("RETURN"))
    private void customdimensions$logForcedBiomeBypass(StructureSet.WeightedEntry weightedEntry,
                                                       StructureAccessor structureAccessor,
                                                       DynamicRegistryManager registryManager,
                                                       NoiseConfig noiseConfig,
                                                       StructureTemplateManager templateManager,
                                                       long seed, Chunk chunk, ChunkPos pos,
                                                       ChunkSectionPos sectionPos,
                                                       CallbackInfoReturnable<Boolean> cir) {
        String dimensionName = ForcedBiomeBypass.consumeApplied();
        if (dimensionName == null || !cir.getReturnValueZ()) {
            return;
        }
        String structureId = weightedEntry.structure().getKey()
                .map(key -> key.getValue().toString())
                .orElse("<unregistered structure>");
        if (ForcedBiomeBypass.firstSighting(dimensionName, structureId, pos.x, pos.z)) {
            MultiverseServer.LOGGER.info(
                    "Dimension {}: forced {} generated at chunk [{}, {}] "
                    + "(biome predicate bypassed)",
                    dimensionName, structureId, pos.x, pos.z);
        }
    }
}
