package com.customdimensions.mixin;

import com.customdimensions.dimension.RejectionCensus;
import com.customdimensions.dimension.StructurePick;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
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

import java.util.List;
import java.util.function.Predicate;

/**
 * Enforces the noise-managed structure pick at generation time: a noise site
 * is filled from its own candidate chain and by nothing else. Each candidate
 * is held to its declared biomes, except one the dimension asked for by
 * {@code structures.include} or a want, which keeps the bypassed predicate
 * (same technique as {@link ChunkGeneratorForcedStartMixin}).
 *
 * <p>Priority 900, same as the forced-start mixin. Both target
 * {@code trySetStructureStart} at HEAD. Mixin application order within the
 * same priority is by class name (alphabetical), so
 * {@code ChunkGeneratorForcedStartMixin} runs BEFORE this class. A
 * {@code structures.force} placement at a chunk that is also a noise site
 * wins: the forced-start mixin returns first, and this mixin never sees it.
 *
 * <p>Separate class from the forced-start mixin on purpose: the two serve
 * different registries ({@link com.customdimensions.dimension.ForcedStartOverride}
 * vs {@link StructurePick}), and mixing their concerns would make testing
 * and ordering both harder.
 *
 * <p>A site whose whole chain declines is left empty and recorded once by
 * {@link RejectionCensus}, so occupancy is a recorded fact.
 */
@Mixin(value = ChunkGenerator.class, priority = 900)
public abstract class NoiseStructureSelectionMixin {

    @Unique
    private static final Predicate<RegistryEntry<Biome>> CUSTOMDIMENSIONS$ANY_BIOME_NOISE =
            biomeEntry -> true;

    @Inject(method = "trySetStructureStart", at = @At("HEAD"), cancellable = true)
    private void customdimensions$noiseStructureSelection(
            StructureSet.WeightedEntry weightedEntry,
            StructureAccessor structureAccessor,
            DynamicRegistryManager registryManager,
            NoiseConfig noiseConfig,
            StructureTemplateManager templateManager,
            long seed, Chunk chunk, ChunkPos pos,
            ChunkSectionPos sectionPos,
            CallbackInfoReturnable<Boolean> cir) {

        WorldAccess access = ((StructureAccessorAccessor) structureAccessor).getWorld();
        ServerWorld world = access instanceof ServerWorldAccess serverAccess
                ? serverAccess.toServerWorld() : null;
        if (world == null) {
            return;
        }
        String worldId = world.getRegistryKey().getValue().toString();

        // Identity lookup: miss -> pass-throughs, forced sets, exit shrines,
        // other mods' sets. All untouched.
        StructurePick.GroupSelection sel = StructurePick.lookup(worldId, weightedEntry);
        if (sel == null) {
            return;
        }

        String entryId = weightedEntry.structure().getKey()
                .map(key -> key.getValue().toString()).orElse(null);
        if (entryId == null) {
            return;
        }

        long pickValue = StructurePick.pickValue(sel.noiseSeed(), pos.x, pos.z);
        List<StructurePick.PoolEntry> chain = StructurePick.candidates(
                sel.sortedPool(), pickValue, StructurePick.MAX_CANDIDATES);
        if (chain.isEmpty()) {
            return;
        }

        // Not the assigned structure -> suppress. Vanilla's setStructureStarts
        // loop removes the entry and redraws, so every non-assigned entry is
        // rejected until the assigned one is tried (or the pool exhausts).
        String assigned = chain.get(0).structureId();
        if (!entryId.equals(assigned)) {
            cir.setReturnValue(false);
            return;
        }

        // Walk the chain: the assigned structure, then each re-draw, until one
        // accepts the position. The bypass belongs to the ASSIGNED structure
        // alone — a want that also absorbed every re-draw would turn one
        // request into hundreds of out-of-biome placements.
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        boolean[] biomeRejected = {false};
        int tried = 0;
        int biomeRejections = 0;
        for (StructurePick.PoolEntry candidate : chain) {
            RegistryEntry<Structure> entry = candidate.structure() != null
                    ? candidate.structure()
                    : (tried == 0 ? weightedEntry.structure() : null);
            if (entry == null) {
                break;
            }
            tried++;
            biomeRejected[0] = false;
            if (customdimensions$tryStart(self, entry.value(), candidate.bypassBiome() && tried == 1,
                    structureAccessor, registryManager, noiseConfig, templateManager,
                    seed, chunk, pos, sectionPos, biomeRejected)) {
                cir.setReturnValue(true);
                return;
            }
            if (biomeRejected[0]) {
                biomeRejections++;
            }
        }

        // Nothing in the chain accepted: the site stays empty. Recorded once
        // per site, not once per candidate, with how many of the candidates
        // the biome turned away rather than the terrain.
        RejectionCensus.siteEmpty(world, worldId, sel.group(), assigned,
                pos.x, pos.z, tried, biomeRejections);
        cir.setReturnValue(false);
    }

    /**
     * One candidate's attempt. Returns true when the site is occupied by this
     * structure — including when it already was, which is what makes the walk
     * idempotent and therefore order-free.
     *
     * <p>The predicate records whether it was the biome that turned the
     * position away, so an empty site can say which of its candidates the
     * biome refused and which the terrain did.
     */
    @Unique
    private static boolean customdimensions$tryStart(
            ChunkGenerator self, Structure structure, boolean bypassBiome,
            StructureAccessor structureAccessor, DynamicRegistryManager registryManager,
            NoiseConfig noiseConfig, StructureTemplateManager templateManager,
            long seed, Chunk chunk, ChunkPos pos, ChunkSectionPos sectionPos,
            boolean[] biomeRejected) {

        StructureStart existing = structureAccessor.getStructureStart(sectionPos, structure, chunk);
        if (existing != null && existing.hasChildren()) {
            return true;
        }
        int references = existing != null ? existing.getReferences() : 0;

        Predicate<RegistryEntry<Biome>> predicate = CUSTOMDIMENSIONS$ANY_BIOME_NOISE;
        if (!bypassBiome) {
            RegistryEntryList<Biome> valid;
            try {
                valid = structure.getValidBiomes();
            } catch (RuntimeException e) {
                // A structure whose biome list will not resolve is not ours to
                // fail on; it keeps the bypass, as NoisePoolBuilder does.
                valid = null;
            }
            if (valid != null) {
                RegistryEntryList<Biome> biomes = valid;
                predicate = biomeEntry -> {
                    if (biomes.contains(biomeEntry)) {
                        return true;
                    }
                    biomeRejected[0] = true;
                    return false;
                };
            }
        }

        StructureStart start = structure.createStructureStart(registryManager,
                self, self.getBiomeSource(), noiseConfig, templateManager, seed, pos,
                references, chunk, predicate);
        if (!start.hasChildren()) {
            return false;
        }
        structureAccessor.setStructureStart(sectionPos, structure, start, chunk);
        return true;
    }
}
