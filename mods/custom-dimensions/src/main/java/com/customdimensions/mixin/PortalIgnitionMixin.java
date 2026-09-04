package com.customdimensions.mixin;

import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.portal.FrameView;
import com.customdimensions.portal.IgnitionLog;
import com.customdimensions.portal.IgnitionRefusal;
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
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ItemStack.class)
public class PortalIgnitionMixin {
    @Inject(method = "useOnBlock", at = @At("HEAD"), cancellable = true)
    private void onItemUseOnBlock(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        World world = context.getWorld();
        BlockPos clickedPos = context.getBlockPos();
        if (world.isClient()) {
            IgnitionLog.refusedEarly(IgnitionRefusal.CLIENT_WORLD, world, clickedPos, context.getStack());
            return;
        }
        if (!(world instanceof ServerWorld serverWorld)) {
            IgnitionLog.refusedEarly(IgnitionRefusal.NOT_SERVER_WORLD, world, clickedPos, context.getStack());
            return;
        }

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
                IgnitionLog.refusedEarly(IgnitionRefusal.NO_IGNITER_MATCH, world, clickedPos,
                        context.getStack());
                return;
            }
            candidates = List.of(fallback);
        }

        for (PortalDefinition def : candidates) {
            IgnitionScan.Attempt attempt = IgnitionScan.sweepDetailed(
                    FrameView.of(serverWorld), clickedPos, def.resolveFrameMatcher(), def);
            if (attempt.site() != null) {
                IgnitionScan.Site site = attempt.site();
                if (!registerAndFinish(serverWorld, site, context, def)) {
                    IgnitionLog.refused(serverWorld, clickedPos, clickedBlockId, def,
                            new IgnitionScan.Refusal(IgnitionRefusal.ALREADY_LIT, clickedPos,
                                    site.axis(), site.fill().size()));
                }
                // Cancelled either way. Falling through to vanilla would set
                // fire to the frame of a portal that is already burning.
                cir.setReturnValue(ActionResult.SUCCESS);
                return;
            }
            IgnitionLog.refused(serverWorld, clickedPos, clickedBlockId, def, attempt.refusal());
        }
    }

    // False when this frame is already a lit portal. The zone is deduped on
    // (target, axis, interior), so a re-light registers nothing — and an
    // ignition that registers nothing spends no igniter and makes no sound.
    private static boolean registerAndFinish(ServerWorld serverWorld, IgnitionScan.Site site,
            ItemUsageContext context, PortalDefinition def) {
        RegistryKey<World> worldKey = serverWorld.getRegistryKey();
        PortalHelper.PortalZone zone = new PortalHelper.PortalZone(
                site.fill(), def, site.axis(), worldKey, def.getTargetKey());
        if (!PortalHelper.registerZone(zone)) {
            return false;
        }
        prewarmTarget(def);
        PortalHelper.spawnParticles(serverWorld, zone);
        playIgniteSound(serverWorld, site.soundPos(), def);

        if (context.getPlayer() == null || !context.getPlayer().isCreative()) {
            context.getStack().decrement(1);
        }
        return true;
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
