package com.customdimensions.mixin;

import com.customdimensions.dimension.ForcedGroundLevel;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers the generator's ground queries with the height a
 * {@code structures.force} entry pinned, while that entry's start attempt is
 * in flight.
 *
 * <p>This is what makes a forced {@code y} mean "no ground needed". A
 * structure decides whether it can generate by asking the generator where the
 * ground is; over void the answer is "nowhere", so it declines and nothing
 * spawns. With a pinned height every such query answers with that height and a
 * column solid up to it, so the structure places exactly there and hangs in
 * open air.
 *
 * <p>Both queries are covered because structures use both: {@code getHeight}
 * for a start position, {@code getColumnSample} for structures that inspect
 * what is actually under them before accepting.
 *
 * <p>Inert unless armed — {@link ForcedGroundLevel#pinned()} is null on every
 * thread that is not inside a pinned attempt, which is all of them the rest of
 * the time.
 */
@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorForcedGroundMixin {

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void customdimensions$pinHeight(int x, int z, Heightmap.Type heightmap,
                                            HeightLimitView world, NoiseConfig noiseConfig,
                                            CallbackInfoReturnable<Integer> cir) {
        Integer pinned = ForcedGroundLevel.pinned();
        if (pinned != null) {
            cir.setReturnValue(pinned);
        }
    }

    @Inject(method = "getColumnSample", at = @At("HEAD"), cancellable = true)
    private void customdimensions$pinColumn(int x, int z, HeightLimitView world,
                                            NoiseConfig noiseConfig,
                                            CallbackInfoReturnable<VerticalBlockSample> cir) {
        Integer pinned = ForcedGroundLevel.pinned();
        if (pinned == null) {
            return;
        }
        int bottom = world.getBottomY();
        BlockState[] states = new BlockState[world.getHeight()];
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        for (int i = 0; i < states.length; i++) {
            states[i] = bottom + i < pinned ? stone : air;
        }
        cir.setReturnValue(new VerticalBlockSample(bottom, states));
    }
}
