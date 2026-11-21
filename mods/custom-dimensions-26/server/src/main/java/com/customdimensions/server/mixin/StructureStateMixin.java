package com.customdimensions.server.mixin;

import com.customdimensions.server.firewall.WorldgenFirewall;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A structure set the firewall switched off never enters a level's structure state, so it places nothing and cannot be located. */
@Mixin(ChunkGeneratorStructureState.class)
abstract class StructureStateMixin {
    @ModifyExpressionValue(method = "createForNormal",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/HolderLookup;listElements()Ljava/util/stream/Stream;"))
    private static Stream<Holder.Reference<StructureSet>> customdimensions$dropSwitchedOff(Stream<Holder.Reference<StructureSet>> sets) {
        return sets.filter(set -> WorldgenFirewall.placed(set.key()));
    }
}
