package com.customdimensions.server.mixin;

import com.customdimensions.server.structure.Footings;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After a start's pieces are placed in a chunk, the footing and terrace adaptations shape the ground under and round them ({@link Footings}). */
@Mixin(StructureStart.class)
abstract class StructureStartFootingMixin {
    @Inject(method = "placeInChunk", at = @At("RETURN"))
    private void customdimensions$footings(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
            BoundingBox chunkBox, ChunkPos chunk, CallbackInfo ci) {
        Footings.apply((StructureStart) (Object) this, level, generator, chunkBox);
    }
}
