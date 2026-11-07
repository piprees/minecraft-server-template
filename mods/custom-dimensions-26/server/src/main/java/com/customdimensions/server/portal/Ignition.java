package com.customdimensions.server.portal;

import com.customdimensions.core.config.MultiverseConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.portal.IgniterSpend;
import com.customdimensions.core.portal.IgnitionRefusal;
import com.customdimensions.core.portal.IgnitionScan;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelManager;
import java.util.List;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Lighting a portal: an igniter item used on a frame block, through Fabric's
 * use-block event. Igniters are shared across dimensions, so every definition
 * the item lights is a candidate, those whose frame matches the clicked block
 * first, and the first with a valid frame at the click wins. A refusal says
 * why: INFO when the clicked block is that portal's frame, DEBUG otherwise,
 * since the event runs for every right-click in the game.
 */
public final class Ignition {
    public static final String REFUSED = "portal ignition refused";

    private Ignition() {
    }

    /** What one use came to: a lit zone, or the closest refusal. */
    public record Outcome(PortalZone zone, IgnitionRefusal refusal, String detail) {
        public boolean consumed() {
            return zone != null || refusal == IgnitionRefusal.ALREADY_LIT;
        }
    }

    static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(level instanceof ServerLevel server)) {
                return InteractionResult.PASS;
            }
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty()) {
                return InteractionResult.PASS;
            }
            return ignite(server, hit.getBlockPos(), stack, player, hand).consumed()
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
    }

    /** The whole ignition path; {@code player} may be null (a dispenser, or the self-test). */
    public static Outcome ignite(ServerLevel level, BlockPos clicked, ItemStack stack, Player player, InteractionHand hand) {
        String clickedId = PortalWorld.blockId(level.getBlockState(clicked));
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        MultiverseConfig config = MultiverseConfig.getInstance();
        List<PortalDefinition> candidates = config.getPortalsByIgniter(itemId, clickedId);
        if (candidates.isEmpty()) {
            PortalDefinition fallback = config.getDefaultPortalForFrameBlock(clickedId);
            if (fallback == null) {
                return new Outcome(null, IgnitionRefusal.NO_IGNITER_MATCH, null);
            }
            candidates = List.of(fallback);
        }
        IgnitionScan.Refusal closest = null;
        for (PortalDefinition def : candidates) {
            IgnitionScan.Attempt attempt = IgnitionScan.sweep(PortalWorld.frameView(level), Pos.core(clicked), def);
            if (attempt.site() != null) {
                IgnitionScan.Site site = attempt.site();
                PortalZone zone = new PortalZone(Pos.mc(site.fill()), def, Pos.mc(site.axis()), level.dimension(),
                        PortalRegistry.key(def.getTargetDimension()), false);
                if (!PortalRegistry.get().addIfAbsent(zone)) {
                    return new Outcome(null, IgnitionRefusal.ALREADY_LIT, def.getId());
                }
                PortalWorld.fill(level, zone.interior, zone.axis);
                PortalRegistry.get().save();
                Portals.playSound(level, Pos.mc(site.soundPos()), def.getIgniteSound());
                spend(level, stack, player, hand, def);
                prewarm(zone);
                CustomDimensions.LOG.info("portal lit: {} in {} at {} ({} cells, axis {})", def.getId(),
                        level.dimension().identifier(), zone.landingCell(), zone.interior.size(), zone.axis);
                return new Outcome(zone, null, def.getId());
            }
            log(level, clicked, clickedId, def, attempt.refusal());
            closest = IgnitionScan.Refusal.furthest(closest, attempt.refusal());
        }
        return new Outcome(null, closest == null ? IgnitionRefusal.NO_CANDIDATE_CELL : closest.reason(),
                closest == null ? null : String.valueOf(closest.blockedBy()));
    }

    private static void spend(ServerLevel level, ItemStack stack, Player player, InteractionHand hand, PortalDefinition def) {
        switch (IgniterSpend.of(stack.isDamageableItem(), player != null && player.isCreative(), def.consumesIgniter())) {
            case DAMAGE -> {
                if (player != null) {
                    stack.hurtAndBreak(1, player, hand == InteractionHand.OFF_HAND ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND);
                } else {
                    stack.hurtAndBreak(1, level, null, item -> { });
                }
            }
            case CONSUME -> stack.shrink(1);
            case NOTHING -> { }
        }
    }

    /** Opens a lazy target the moment its portal is lit, so the first crossing does not wait for it. */
    private static void prewarm(PortalZone zone) {
        LevelManager.get().flatMap(m -> m.slugOf(zone.target)).ifPresent(slug -> LevelManager.get().get().activate(slug));
    }

    private static void log(ServerLevel level, BlockPos clicked, String clickedId, PortalDefinition def,
                            IgnitionScan.Refusal refusal) {
        if (refusal == null) {
            return;
        }
        boolean deliberate = def.resolveFrameForms().acceptsBlockId(clickedId);
        if (!deliberate && !CustomDimensions.LOG.isDebugEnabled()) {
            return;
        }
        StringBuilder line = new StringBuilder(REFUSED).append(": ").append(def.getId()).append(" at ")
                .append(clicked.toShortString()).append(" in ").append(level.dimension().identifier())
                .append(" (clicked ").append(clickedId).append("): ").append(refusal.reason().sentence());
        if (refusal.blockedBy() != null) {
            BlockPos at = Pos.mc(refusal.blockedBy());
            line.append(" — the fill ran into ").append(PortalWorld.blockId(level.getBlockState(at))).append(" at ")
                    .append(at.toShortString());
        }
        line.append(" [").append(refusal.reason().name());
        if (refusal.axis() != null) {
            line.append(" axis=").append(refusal.axis());
        }
        if (refusal.cells() > 0) {
            line.append(" cells=").append(refusal.cells());
        }
        line.append(']');
        if (deliberate) {
            CustomDimensions.LOG.info(line.toString());
        } else {
            CustomDimensions.LOG.debug(line.toString());
        }
    }
}
