package com.customdimensions.mixin;

import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Set;

@Mixin(ItemStack.class)
public class PortalIgnitionMixin {
    @Inject(method = "useOnBlock", at = @At("HEAD"), cancellable = true)
    private void onItemUseOnBlock(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        World world = context.getWorld();
        if (world.isClient()) {
            return;
        }
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }

        BlockPos clickedPos = context.getBlockPos();
        PortalDefinition def = null;

        Item heldItem = context.getStack().getItem();
        Identifier itemId = Registries.ITEM.getId(heldItem);
        if (itemId != null) {
            Optional<PortalDefinition> portalDef = MultiverseConfig.getInstance().getPortalByIgniter(itemId.toString());
            if (portalDef.isPresent()) {
                def = portalDef.get();
            }
        }

        if (def == null) {
            String blockId = Registries.BLOCK.getId(serverWorld.getBlockState(clickedPos).getBlock()).toString();
            def = MultiverseConfig.getInstance().getDefaultPortalForFrameBlock(blockId);
            if (def == null) {
                return;
            }
        }

        Identifier frameId = Identifier.of(def.getFrameBlock());
        Block frameBlock = Registries.BLOCK.get(frameId);
        if (frameBlock == null) {
            return;
        }

        for (Direction dir : Direction.values()) {
            BlockPos candidate = clickedPos.offset(dir);
            if (!PortalHelper.isPortalFillable(serverWorld.getBlockState(candidate))) {
                continue;
            }

            Set<BlockPos> xFill = PortalHelper.floodFill(serverWorld, candidate, frameBlock, Direction.Axis.X);
            Set<BlockPos> zFill = PortalHelper.floodFill(serverWorld, candidate, frameBlock, Direction.Axis.Z);
            Set<BlockPos> yFill = PortalHelper.floodFill(serverWorld, candidate, frameBlock, Direction.Axis.Y);
            boolean xValid = !xFill.isEmpty() && PortalHelper.isAreaBoundedByFrame(serverWorld, xFill, frameBlock, Direction.Axis.X);
            boolean zValid = !zFill.isEmpty() && PortalHelper.isAreaBoundedByFrame(serverWorld, zFill, frameBlock, Direction.Axis.Z);
            boolean yValid = !yFill.isEmpty() && PortalHelper.isAreaBoundedByFrame(serverWorld, yFill, frameBlock, Direction.Axis.Y);

            if (!xValid && !zValid && !yValid) {
                continue;
            }

            Set<BlockPos> fill;
            Direction.Axis axis;
            Direction.Axis clickedAxis = dir.getAxis();

            if (yValid && clickedAxis == Direction.Axis.Y) {
                fill = yFill;
                axis = Direction.Axis.Y;
            } else if (xValid && clickedAxis == Direction.Axis.X) {
                fill = xFill;
                axis = Direction.Axis.X;
            } else if (zValid && clickedAxis == Direction.Axis.Z) {
                fill = zFill;
                axis = Direction.Axis.Z;
            } else if (yValid) {
                fill = yFill;
                axis = Direction.Axis.Y;
            } else if (xValid) {
                fill = xFill;
                axis = Direction.Axis.X;
            } else {
                fill = zFill;
                axis = Direction.Axis.Z;
            }

            RegistryKey<World> worldKey = serverWorld.getRegistryKey();
            PortalHelper.PortalZone zone = new PortalHelper.PortalZone(fill, def, axis, worldKey, def.getTargetKey());
            PortalHelper.registerZone(zone);
            prewarmTarget(def);
            PortalHelper.spawnParticles(serverWorld, zone);
            playIgniteSound(serverWorld, clickedPos, def);

            if (context.getPlayer() == null || !context.getPlayer().isCreative()) {
                context.getStack().decrement(1);
            }
            cir.setReturnValue(ActionResult.SUCCESS);
            return;
        }

        BlockPos center = clickedPos;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos candidate = center.add(dx, dy, dz);
                    if (!PortalHelper.isPortalFillable(serverWorld.getBlockState(candidate))) {
                        continue;
                    }

                    Set<BlockPos> xFill = PortalHelper.floodFill(serverWorld, candidate, frameBlock, Direction.Axis.X);
                    Set<BlockPos> zFill = PortalHelper.floodFill(serverWorld, candidate, frameBlock, Direction.Axis.Z);
                    Set<BlockPos> yFill = PortalHelper.floodFill(serverWorld, candidate, frameBlock, Direction.Axis.Y);
                    boolean xValid = !xFill.isEmpty() && PortalHelper.isAreaBoundedByFrame(serverWorld, xFill, frameBlock, Direction.Axis.X);
                    boolean zValid = !zFill.isEmpty() && PortalHelper.isAreaBoundedByFrame(serverWorld, zFill, frameBlock, Direction.Axis.Z);
                    boolean yValid = !yFill.isEmpty() && PortalHelper.isAreaBoundedByFrame(serverWorld, yFill, frameBlock, Direction.Axis.Y);

                    if (!xValid && !zValid && !yValid) {
                        continue;
                    }

                    Set<BlockPos> fill;
                    Direction.Axis axis;
                    if (yValid) {
                        fill = yFill;
                        axis = Direction.Axis.Y;
                    } else if (xValid) {
                        fill = xFill;
                        axis = Direction.Axis.X;
                    } else {
                        fill = zFill;
                        axis = Direction.Axis.Z;
                    }

                    RegistryKey<World> worldKey = serverWorld.getRegistryKey();
                    PortalHelper.PortalZone zone = new PortalHelper.PortalZone(fill, def, axis, worldKey, def.getTargetKey());
                    PortalHelper.registerZone(zone);
                    prewarmTarget(def);
                    PortalHelper.spawnParticles(serverWorld, zone);
                    playIgniteSound(serverWorld, candidate, def);

                    if (context.getPlayer() == null || !context.getPlayer().isCreative()) {
                        context.getStack().decrement(1);
                    }
                    cir.setReturnValue(ActionResult.SUCCESS);
                    return;
                }
            }
        }
    }

    // Pre-warm the target dimension the moment its portal ignites — world
    // creation takes seconds under load, and deferring it to first entry made
    // the player's first traversal eat that delay (queued via END_SERVER_TICK,
    // never created synchronously from here: tick-loop threading rule).
    private static void prewarmTarget(PortalDefinition def) {
        RegistryKey<World> target = def.getTargetKey();
        if (target != null) {
            DimensionManager.getInstance().requestWorldLoad(target.getValue().getPath());
        }
    }

    private static void playIgniteSound(ServerWorld world, BlockPos pos, PortalDefinition def) {
        Identifier soundId = Identifier.tryParse(def.getIgniteSound());
        if (soundId != null) {
            SoundEvent sound = Registries.SOUND_EVENT.get(soundId);
            if (sound != null) {
                world.playSound(null, pos, sound, SoundCategory.BLOCKS, 1.0f, 1.0f);
            }
        }
    }
}
