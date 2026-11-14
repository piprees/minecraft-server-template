package com.customdimensions.server.mixin;

import com.customdimensions.server.structure.PlanLocate;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The structure search finds the plan's own placements too ({@link PlanLocate}), answering whichever start is nearer. */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorLocateMixin {
    @Inject(method = "findNearestMapStructure", at = @At("RETURN"), cancellable = true)
    private void customdimensions$planPlacements(ServerLevel level, HolderSet<Structure> wanted, BlockPos pos, int maxSearchRadius,
            boolean createReference, CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        Pair<BlockPos, Holder<Structure>> found = cir.getReturnValue();
        Pair<BlockPos, Holder<Structure>> nearer = PlanLocate.nearer(level, wanted, pos, maxSearchRadius, createReference, found);
        if (nearer != found) cir.setReturnValue(nearer);
    }
}
