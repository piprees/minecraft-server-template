package com.customdimensions.server.mixin;

import com.customdimensions.server.structure.StructureReskin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A hard-coded piece's blocks re-skinned while a start with re-skin rules places ({@link StructureReskin}). */
@Mixin(StructurePiece.class)
abstract class PieceReskinMixin {
    @ModifyVariable(method = "placeBlock", at = @At("HEAD"), argsOnly = true)
    private BlockState customdimensions$reskin(BlockState state, WorldGenLevel level, BlockState same, int x, int y, int z, BoundingBox box) {
        if (!StructureReskin.active()) return state;
        StructurePiece self = (StructurePiece) (Object) this;
        return StructureReskin.apply(state, new BlockPos(x, y, z).offset(self.getBoundingBox().minX(), self.getBoundingBox().minY(),
                self.getBoundingBox().minZ()));
    }
}
