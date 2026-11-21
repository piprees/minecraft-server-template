package com.customdimensions.server.mixin;

import com.customdimensions.server.structure.StructureReskin;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds the level's re-skin rules for the structure whose start is placing ({@link StructureReskin}). */
@Mixin(StructureStart.class)
abstract class StructureReskinMixin {
    @Shadow
    public abstract Structure getStructure();

    @Inject(method = "placeInChunk", at = @At("HEAD"))
    private void customdimensions$reskinBegin(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                                             BoundingBox box, ChunkPos chunk, CallbackInfo ci) {
        StructureReskin.begin(level, getStructure());
    }

    @Inject(method = "placeInChunk", at = @At("RETURN"))
    private void customdimensions$reskinEnd(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                                           BoundingBox box, ChunkPos chunk, CallbackInfo ci) {
        StructureReskin.end();
    }
}
