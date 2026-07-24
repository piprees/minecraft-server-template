package com.customdimensions.mixin;

import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.portal.FrameMatcher;
import com.customdimensions.portal.IgnitionScan;
import com.customdimensions.portal.PortalHelper;
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

import java.util.List;
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
        String clickedBlockId = Registries.BLOCK.getId(serverWorld.getBlockState(clickedPos).getBlock()).toString();

        // Igniter items are shared across dimensions (eight dims use
        // ender_eye), so EVERY matching definition is a candidate — each
        // hunts for its own frame block, and the first with a valid frame
        // at the click site wins. Definitions whose frame matches the
        // clicked block are tried first.
        Item heldItem = context.getStack().getItem();
        Identifier itemId = Registries.ITEM.getId(heldItem);
        List<PortalDefinition> candidates = itemId != null
                ? MultiverseConfig.getInstance().getPortalsByIgniter(itemId.toString(), clickedBlockId)
                : List.of();

        if (candidates.isEmpty()) {
            PortalDefinition fallback = MultiverseConfig.getInstance().getDefaultPortalForFrameBlock(clickedBlockId);
            if (fallback == null) {
                return;
            }
            candidates = List.of(fallback);
        }

        for (PortalDefinition def : candidates) {
            if (tryIgnite(serverWorld, clickedPos, context, def, cir)) {
                return;
            }
        }
    }

    // Frame detection + zone registration for one candidate definition.
    // Returns true when a portal was ignited (cir is then set to SUCCESS).
    private static boolean tryIgnite(ServerWorld serverWorld, BlockPos clickedPos, ItemUsageContext context,
            PortalDefinition def, CallbackInfoReturnable<ActionResult> cir) {
        // Frameless gateways: click-to-place, no flood-fill, no frame. The
        // gateway goes on the clicked face (like placing a torch); the
        // matcher may legitimately be empty for these configs.
        if (com.customdimensions.portal.PortalShape.END_GATEWAY.equals(def.getShape())) {
            BlockPos gatewayPos = clickedPos.offset(context.getSide());
            if (!PortalHelper.isPortalFillable(serverWorld.getBlockState(gatewayPos))) {
                return false;
            }
            serverWorld.setBlockState(gatewayPos, net.minecraft.block.Blocks.END_GATEWAY.getDefaultState(),
                    net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
            registerAndFinish(serverWorld, gatewayPos, context, def, Set.of(gatewayPos), Direction.Axis.X);
            cir.setReturnValue(ActionResult.SUCCESS);
            return true;
        }
        FrameMatcher matcher = def.resolveFrameMatcher();
        if (matcher.isEmpty()) {
            return false;
        }

        for (Direction dir : Direction.values()) {
            BlockPos candidate = clickedPos.offset(dir);
            if (!PortalHelper.isPortalFillable(serverWorld.getBlockState(candidate))) {
                continue;
            }

            IgnitionScan fills = IgnitionScan.discover(serverWorld, candidate, matcher, def);
            if (fills == null) {
                continue;
            }
            // Prefer the axis matching the clicked face, then Y, X, Z.
            Direction.Axis axis = fills.pick(dir.getAxis());
            registerAndFinish(serverWorld, clickedPos, context, def, fills.get(axis), axis);
            cir.setReturnValue(ActionResult.SUCCESS);
            return true;
        }

        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos candidate = clickedPos.add(dx, dy, dz);
                    if (!PortalHelper.isPortalFillable(serverWorld.getBlockState(candidate))) {
                        continue;
                    }

                    IgnitionScan fills = IgnitionScan.discover(serverWorld, candidate, matcher, def);
                    if (fills == null) {
                        continue;
                    }
                    Direction.Axis axis = fills.pick(null);
                    registerAndFinish(serverWorld, candidate, context, def, fills.get(axis), axis);
                    cir.setReturnValue(ActionResult.SUCCESS);
                    return true;
                }
            }
        }
        return false;
    }

    private static void registerAndFinish(ServerWorld serverWorld, BlockPos soundPos, ItemUsageContext context,
            PortalDefinition def, Set<BlockPos> fill, Direction.Axis axis) {
        RegistryKey<World> worldKey = serverWorld.getRegistryKey();
        PortalHelper.PortalZone zone = new PortalHelper.PortalZone(fill, def, axis, worldKey, def.getTargetKey());
        PortalHelper.registerZone(zone);
        placeCentreBlock(serverWorld, def, fill, axis);
        prewarmTarget(def);
        PortalHelper.spawnParticles(serverWorld, zone);
        playIgniteSound(serverWorld, soundPos, def);

        if (context.getPlayer() == null || !context.getPlayer().isCreative()) {
            context.getStack().decrement(1);
        }
    }

    // end_exit pedestal: a real block at the interior's centre cell (dragon
    // egg, trophy). Source zones stay invisible otherwise — the pedestal is
    // scenery, not a portal block, and zone validity only checks the frame
    // ring, so occupying one interior cell is safe. Placement uses
    // NOTIFY_LISTENERS | FORCE_STATE like every other frame/portal write.
    private static void placeCentreBlock(ServerWorld world, PortalDefinition def,
            Set<BlockPos> fill, Direction.Axis axis) {
        if (axis != Direction.Axis.Y
                || !com.customdimensions.portal.PortalShape.END_EXIT.equals(def.getShape())
                || def.getCentreBlock() == null) {
            return;
        }
        Identifier blockId = Identifier.tryParse(def.getCentreBlock());
        net.minecraft.block.Block block = blockId != null ? Registries.BLOCK.get(blockId) : null;
        if (block == null || block == net.minecraft.block.Blocks.AIR) {
            return;
        }
        BlockPos centre = com.customdimensions.portal.PortalShape.centreOf(fill);
        world.setBlockState(centre, block.getDefaultState(),
                net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
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
