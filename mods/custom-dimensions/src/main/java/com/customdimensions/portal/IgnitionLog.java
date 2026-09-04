package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The refusal line. One format, two readers: a sentence a player can act on,
 * then the reason's constant and the coordinates for a grep.
 *
 * <p>{@code ItemStack.useOnBlock} runs for every right-click on every block,
 * so level is chosen by how much the click looks like somebody trying to
 * light this portal: a click on the portal's OWN frame material is INFO, and
 * everything else is DEBUG ({@code CUSTOMDIM_LOG_LEVEL=debug}). Nothing is
 * built before the level is known — a refused click must cost a boolean.
 */
public final class IgnitionLog {

    /** The stable half of every line. Grep for this. */
    public static final String PREFIX = "portal ignition refused";

    private IgnitionLog() {
    }

    /** True when the clicked block is frame material for this portal. */
    public static boolean isDeliberate(PortalDefinition def, String clickedBlockId) {
        if (def == null || clickedBlockId == null) {
            return false;
        }
        FrameMatcher matcher = def.resolveFrameMatcher();
        return !matcher.isEmpty() && matcher.acceptsBlockId(clickedBlockId);
    }

    /** One candidate definition declining a click. */
    public static String line(String worldId, BlockPos clicked, String clickedBlockId,
            String portalId, IgnitionScan.Refusal refusal) {
        return line(worldId, clicked, clickedBlockId, portalId, refusal, null);
    }

    /** As above, naming the block that stopped the fill when there is one. */
    public static String line(String worldId, BlockPos clicked, String clickedBlockId,
            String portalId, IgnitionScan.Refusal refusal, String blockedByBlockId) {
        StringBuilder out = new StringBuilder(PREFIX);
        out.append(": ").append(portalId)
                .append(" at ").append(coords(clicked))
                .append(" in ").append(worldId)
                .append(" (clicked ").append(clickedBlockId).append("): ")
                .append(refusal.reason().sentence());
        if (refusal.blockedBy() != null) {
            out.append(" — the fill ran into ")
                    .append(blockedByBlockId != null ? blockedByBlockId : "a block")
                    .append(" at ").append(coords(refusal.blockedBy()));
        }
        out.append(" [").append(refusal.reason().name());
        if (!clicked.equals(refusal.at())) {
            out.append(" cell=").append(coords(refusal.at()));
        }
        if (refusal.axis() != null) {
            out.append(" axis=").append(refusal.axis().asString().toUpperCase(java.util.Locale.ROOT));
        }
        if (refusal.cells() > 0) {
            out.append(" cells=").append(refusal.cells());
        }
        return out.append(']').toString();
    }

    /** A click turned away before any portal definition was tried. */
    public static String earlyLine(IgnitionRefusal reason, String worldId, BlockPos clicked,
            String clickedBlockId, String heldItemId) {
        return PREFIX + " at " + coords(clicked) + " in " + worldId
                + " (clicked " + clickedBlockId + "): " + reason.sentence()
                + " [" + reason.name() + " held=" + heldItemId + "]";
    }

    /** Emits a candidate's refusal, INFO when the click was on its own frame. */
    public static void refused(World world, BlockPos clicked, String clickedBlockId,
            PortalDefinition def, IgnitionScan.Refusal refusal) {
        if (refusal == null) {
            return;
        }
        boolean deliberate = isDeliberate(def, clickedBlockId);
        if (!deliberate && !com.customdimensions.MultiverseServer.LOGGER.isDebugEnabled()) {
            return;
        }
        String line = line(world.getRegistryKey().getValue().toString(), clicked, clickedBlockId,
                def.getId(), refusal, blockIdAt(world, refusal.blockedBy()));
        if (deliberate) {
            com.customdimensions.MultiverseServer.LOGGER.info(line);
        } else {
            com.customdimensions.MultiverseServer.LOGGER.debug(line);
        }
    }

    /**
     * Emits a refusal from before the candidate list, always at DEBUG:
     * {@link IgnitionRefusal#NO_IGNITER_MATCH} alone fires on every
     * right-click anyone makes on any block, so nothing is looked up until
     * the level says somebody is reading.
     */
    public static void refusedEarly(IgnitionRefusal reason, World world, BlockPos clicked,
            ItemStack held) {
        if (!com.customdimensions.MultiverseServer.LOGGER.isDebugEnabled()) {
            return;
        }
        com.customdimensions.MultiverseServer.LOGGER.debug(earlyLine(reason,
                world.getRegistryKey().getValue().toString(), clicked,
                Registries.BLOCK.getId(world.getBlockState(clicked).getBlock()).toString(),
                Registries.ITEM.getId(held.getItem()).toString()));
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String blockIdAt(World world, BlockPos pos) {
        return pos == null ? null
                : Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString();
    }
}
