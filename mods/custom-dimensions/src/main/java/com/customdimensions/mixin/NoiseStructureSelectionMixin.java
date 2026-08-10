package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import com.customdimensions.command.Artefacts;
import com.customdimensions.command.InputHash;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.StructurePick;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Enforces the noise-managed structure pick at generation time: only the
 * assigned structure can start at a noise site, and its biome predicate is
 * bypassed (same technique as {@link ChunkGeneratorForcedStartMixin}).
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
 * <p>On structural rejection (the structure's own generation declines the
 * position), the rejection is logged with dedupe and appended to the
 * census/rejections artefact so occupancy is a recorded fact.
 */
@Mixin(value = ChunkGenerator.class, priority = 900)
public abstract class NoiseStructureSelectionMixin {

    @Unique
    private static final Predicate<RegistryEntry<Biome>> CUSTOMDIMENSIONS$ANY_BIOME_NOISE =
            biomeEntry -> true;

    /** Dedupe: (dim, group, structure, chunk) seen once. */
    @Unique
    private static final Set<String> CUSTOMDIMENSIONS$LOGGED = ConcurrentHashMap.newKeySet();

    @Unique
    private static final int CUSTOMDIMENSIONS$LOG_CAP = 4096;

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

        String assigned = StructurePick.assignedStructure(
                sel.noiseSeed(), pos.x, pos.z, sel.sortedPool());

        // Not the assigned structure -> suppress. Vanilla's setStructureStarts
        // loop removes the entry and redraws, so every non-assigned entry is
        // rejected until the assigned one is tried (or the pool exhausts).
        if (!entryId.equals(assigned)) {
            cir.setReturnValue(false);
            return;
        }

        // This IS the assigned structure -> generate it, biome predicate
        // bypassed (same as forced-start: pools are already biome-filtered
        // by NoisePoolBuilder and affinity-weighted).
        Structure structure = weightedEntry.structure().value();
        StructureStart existing = structureAccessor.getStructureStart(sectionPos, structure, chunk);
        int references = existing != null ? existing.getReferences() : 0;
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        StructureStart start = structure.createStructureStart(registryManager,
                self, self.getBiomeSource(),
                noiseConfig, templateManager, seed, pos, references, chunk,
                CUSTOMDIMENSIONS$ANY_BIOME_NOISE);

        if (start.hasChildren()) {
            structureAccessor.setStructureStart(sectionPos, structure, start, chunk);
            cir.setReturnValue(true);
        } else {
            // Structural rejection: the structure's own generation declined.
            // The site stays empty. Record it.
            customdimensions$recordRejection(world, worldId, sel.group(), entryId, pos.x, pos.z);
            cir.setReturnValue(false);
        }
    }

    @Unique
    private static void customdimensions$recordRejection(
            ServerWorld world, String worldId, String group, String structureId,
            int chunkX, int chunkZ) {
        String key = worldId + '/' + group + '/' + structureId + '@' + chunkX + ',' + chunkZ;
        if (CUSTOMDIMENSIONS$LOGGED.size() < CUSTOMDIMENSIONS$LOG_CAP
                && CUSTOMDIMENSIONS$LOGGED.add(key)) {
            MultiverseServer.LOGGER.info(
                    "Noise pick: {} rejected at chunk [{}, {}] in group {} (world {}) "
                    + "-- structural rejection, site stays empty",
                    structureId, chunkX, chunkZ, group, worldId);
        }

        // Append to .seed-rolling/events/{inputHash}/{dimension}/rejections.json
        // (atomic rewrite on each event — a rejection is proven once, when
        // the chunk generates, and re-proving it means regenerating the chunk).
        try {
            var dimensionId = world.getRegistryKey().getValue();
            var def = MultiverseConfig.getInstance().getDimension(dimensionId.getPath());
            if (def == null) {
                def = MultiverseConfig.getInstance().getWorld(dimensionId.getPath());
            }
            String hash = InputHash.of(def, world.getServer());
            String dimPart = worldId.replace(":", "__");
            Path rejectPath = Artefacts.rollingDir().resolve("events")
                    .resolve(hash).resolve(dimPart).resolve("rejections.json");
            StringBuilder json;
            if (Files.exists(rejectPath)) {
                String existing = Files.readString(rejectPath);
                // Strip trailing \n]\n}\n and append
                int lastBracket = existing.lastIndexOf(']');
                if (lastBracket > 0) {
                    json = new StringBuilder(existing.substring(0, lastBracket));
                    json.append(",\n  ");
                } else {
                    json = customdimensions$newRejectionFile(worldId);
                }
            } else {
                json = customdimensions$newRejectionFile(worldId);
            }
            json.append("{\"group\": \"").append(group)
                    .append("\", \"structure\": \"").append(structureId)
                    .append("\", \"chunkX\": ").append(chunkX)
                    .append(", \"chunkZ\": ").append(chunkZ)
                    .append('}');
            json.append("\n ]\n}\n");
            Artefacts.write(rejectPath, json.toString());
        } catch (IOException e) {
            // Best effort -- a failed rejection log is not worth crashing over.
            MultiverseServer.LOGGER.debug("Failed to write rejection artefact: {}", e.getMessage());
        }
    }

    @Unique
    private static StringBuilder customdimensions$newRejectionFile(String worldId) {
        StringBuilder json = new StringBuilder(Artefacts.jsonHeader("structure-rejections"));
        json.append(" \"dimension\": \"").append(worldId).append("\",\n");
        json.append(" \"rejections\": [\n  ");
        return json;
    }
}
