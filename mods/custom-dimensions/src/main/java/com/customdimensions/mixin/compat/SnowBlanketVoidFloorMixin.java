package com.customdimensions.mixin.compat;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives Wilder Wild's snow blanket a world floor (K6).
 *
 * <p>{@code findLowestHeightForSnow} walks down from the heightmap looking for
 * ground while each block is air or search-through with no fluid. Below the
 * world bottom every read is {@code VOID_AIR}, which is registered as air — so
 * in a column with no ground the walk never ends, the chunk never finishes, and
 * the main thread parks forever in {@code getChunkBlocking}.
 *
 * <p>Returning a solid state below the floor ends the walk there. For a
 * groundless column the walk then exits on its first read having decremented
 * nothing, so the result equals the surface height and the feature's own
 * {@code height > bottomHeight} check skips the column — by exactly one, since
 * an empty column's heightmap returns the world bottom itself. Columns with
 * real ground never reach the floor and are untouched.
 *
 * <p>Descriptors are intermediary and unremapped: the target belongs to another
 * mod, so nothing here goes through the refmap.
 */
@Pseudo
@Mixin(targets = "net.frozenblock.wilderwild.worldgen.impl.feature.SnowBlanketFeature", remap = false)
public class SnowBlanketVoidFloorMixin {

    @Redirect(
            method = "findLowestHeightForSnow",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/class_5281;method_8320"
                            + "(Lnet/minecraft/class_2338;)Lnet/minecraft/class_2680;"),
            remap = false,
            require = 1)
    private static BlockState customdimensions$stopAtTheWorldFloor(StructureWorldAccess level, BlockPos pos) {
        if (pos.getY() < level.getBottomY()) {
            // BEDROCK deliberately: solid, outside the search-through tag, no
            // fluid, and — unlike GRASS_BLOCK/PODZOL/MYCELIUM — it carries no
            // SNOWY property for placeSnowLayer to write back into.
            return Blocks.BEDROCK.getDefaultState();
        }
        return level.getBlockState(pos);
    }
}
