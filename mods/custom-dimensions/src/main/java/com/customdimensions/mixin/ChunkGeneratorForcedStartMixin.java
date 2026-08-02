package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import com.customdimensions.dimension.ForcedStartOverride;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureSet;
import net.minecraft.structure.StructureStart;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Performs {@code structures.force} start attempts itself, at the head of
 * {@code ChunkGenerator.trySetStructureStart}, so a forced placement survives
 * everything later in that method: the structure's own biome predicate AND
 * other mods' cancellable HEAD injects (all seven YUNG's structure mods cancel
 * every vanilla start of the structure TYPE they replace — a forced
 * {@code minecraft:fortress} died there regardless of placement class; see
 * TROUBLESHOOTING.md#t25).
 *
 * <p>Priority 900: HEAD callbacks execute in application order (lower
 * priority applies first), so this one must run BEFORE default-priority
 * (1000) cancels — see TROUBLESHOOTING.md#t25 for why 900 is load-bearing.
 * The body mirrors vanilla's own {@code trySetStructureStart} with the
 * biome predicate replaced — "put THIS structure at THIS spot" is a
 * literal override, not a suggestion.
 *
 * <p>Not affected, deliberately: {@code /locate}, which reads
 * {@code StructurePlacementCalculator.getPlacements} — that map indexes a
 * structure only when its valid biomes intersect the dimension's biome
 * source. A forced structure whose biomes the dimension does not contain
 * GENERATES but is still not locatable; use {@code /customdim
 * structure-census} to see it.
 */
@Mixin(value = ChunkGenerator.class, priority = 900)
public abstract class ChunkGeneratorForcedStartMixin {

    @Unique
    private static final Predicate<RegistryEntry<Biome>> CUSTOMDIMENSIONS$ANY_BIOME =
            biomeEntry -> true;

    @Inject(method = "trySetStructureStart", at = @At("HEAD"), cancellable = true)
    private void customdimensions$forceStructureStart(StructureSet.WeightedEntry weightedEntry,
                                                      StructureAccessor structureAccessor,
                                                      DynamicRegistryManager registryManager,
                                                      NoiseConfig noiseConfig,
                                                      StructureTemplateManager templateManager,
                                                      long seed, Chunk chunk, ChunkPos pos,
                                                      ChunkSectionPos sectionPos,
                                                      CallbackInfoReturnable<Boolean> cir) {
        String structureId = weightedEntry.structure().getKey()
                .map(key -> key.getValue().toString()).orElse(null);
        if (structureId == null) {
            return;
        }
        WorldAccess access = ((StructureAccessorAccessor) structureAccessor).getWorld();
        ServerWorld world = access instanceof ServerWorldAccess serverAccess
                ? serverAccess.toServerWorld() : null;
        if (world == null) {
            return;
        }
        String worldId = world.getRegistryKey().getValue().toString();
        if (!ForcedStartOverride.isForced(worldId, pos.toLong(), structureId)) {
            return;
        }

        Structure structure = weightedEntry.structure().value();
        StructureStart existing = structureAccessor.getStructureStart(sectionPos, structure, chunk);
        int references = existing != null ? existing.getReferences() : 0;
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        StructureStart start = structure.createStructureStart(registryManager,
                self, self.getBiomeSource(),
                noiseConfig, templateManager, seed, pos, references, chunk,
                CUSTOMDIMENSIONS$ANY_BIOME);
        String dimensionName = ForcedStartOverride.dimensionName(worldId);
        if (start.hasChildren()) {
            structureAccessor.setStructureStart(sectionPos, structure, start, chunk);
            if (ForcedStartOverride.firstSighting(dimensionName, structureId, pos.x, pos.z)) {
                MultiverseServer.LOGGER.info(
                        "Dimension {}: forced {} generated at chunk [{}, {}] "
                        + "(start overridden; biome predicate bypassed)",
                        dimensionName, structureId, pos.x, pos.z);
            }
            cir.setReturnValue(true);
        } else {
            if (ForcedStartOverride.firstFailure(dimensionName, structureId, pos.x, pos.z)) {
                MultiverseServer.LOGGER.warn(
                        "Dimension {}: forced {} produced no start at chunk [{}, {}] — "
                        + "the structure's own generation rejected the position",
                        dimensionName, structureId, pos.x, pos.z);
            }
            cir.setReturnValue(false);
        }
    }
}
