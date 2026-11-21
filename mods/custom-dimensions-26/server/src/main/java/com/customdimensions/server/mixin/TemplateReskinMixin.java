package com.customdimensions.server.mixin;

import com.customdimensions.server.structure.StructureReskin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** A template's processed blocks re-skinned while a start with re-skin rules places ({@link StructureReskin}). */
@Mixin(StructureTemplate.class)
abstract class TemplateReskinMixin {
    @Inject(method = "processBlockInfos", at = @At("RETURN"), cancellable = true)
    private static void customdimensions$reskin(ServerLevelAccessor level, BlockPos position, BlockPos reference, StructurePlaceSettings settings,
                                                List<StructureTemplate.StructureBlockInfo> original,
                                                CallbackInfoReturnable<List<StructureTemplate.StructureBlockInfo>> cir) {
        if (!StructureReskin.active()) return;
        List<StructureTemplate.StructureBlockInfo> list = new java.util.ArrayList<>(cir.getReturnValue());
        for (int i = 0; i < list.size(); i++) {
            StructureTemplate.StructureBlockInfo info = list.get(i);
            BlockState to = StructureReskin.apply(info.state(), info.pos());
            if (to != info.state()) list.set(i, new StructureTemplate.StructureBlockInfo(info.pos(), to, info.nbt()));
        }
        cir.setReturnValue(list);
    }
}
