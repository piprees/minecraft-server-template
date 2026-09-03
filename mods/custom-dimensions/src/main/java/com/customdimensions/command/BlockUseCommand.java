package com.customdimensions.command;

import com.customdimensions.portal.PortalHelper;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * {@code /customdim use <x> <y> <z> [face]} — a real block use at a position,
 * performed by the executing player.
 *
 * <p>Exists because some interactions can only be tested by performing one.
 * Socketing an ender eye is the case that forced it: synthetic mouse and
 * keyboard input do not reach the game on the test machine, Carpet's
 * {@code player ... use} is inert in this pack, and {@code setblock} cannot
 * substitute — the completion check that lights an end portal runs inside the
 * USE, so a set block leaves twelve sockets and no portal.
 *
 * <p><b>It routes through {@code ServerPlayerInteractionManager.interactBlock}
 * — the one method a real right-click goes through</b> — so the block's
 * {@code onUse}, its {@code onUseWithItem} and then the held item's
 * {@code useOnBlock} all run in vanilla's own order, along with every mixin
 * this mod applies to that path. {@code BlockState.onUse} alone would NOT do:
 * {@code EndPortalFrameBlock} does not override it, and the eye is socketed by
 * {@code EnderEyeItem.useOnBlock}, which only the interaction manager reaches.
 *
 * <p>Nothing here knows what an end portal frame is. A generic use is what
 * makes it trustworthy as a test instrument.
 *
 * <p>There is no player on an RCON connection, so drive it as one:
 * {@code execute as <player> run customdim use 100 64 -20}.
 */
public final class BlockUseCommand {

    /**
     * Slack added to the player's block interaction range, matching what
     * {@code ServerPlayNetworkHandler} allows a real click. Reach is checked
     * rather than bypassed: an instrument that can reach through walls proves
     * less than one that cannot.
     */
    private static final double REACH_SLACK = 1.0;

    private BlockUseCommand() {
    }

    /** The centre of one face of a block — where a click on that face lands. */
    static Vec3d hitVector(BlockPos pos, Direction side) {
        return new Vec3d(
                pos.getX() + 0.5 + side.getOffsetX() * 0.5,
                pos.getY() + 0.5 + side.getOffsetY() * 0.5,
                pos.getZ() + 0.5 + side.getOffsetZ() * 0.5);
    }

    /** The hit a click on {@code side} of {@code pos} produces. */
    static BlockHitResult hitOn(BlockPos pos, Direction side) {
        return new BlockHitResult(hitVector(pos, side), side, pos, false);
    }

    /** Named face, or null when the name is not a direction. */
    static Direction parseFace(String name) {
        return name == null ? null : Direction.byName(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** {@code minecraft:end_portal_frame[eye=true,facing=north]} — greppable. */
    static String describe(BlockState state) {
        return state.toString().replace("Block{", "").replace("}", "");
    }

    static int use(CommandContext<ServerCommandSource> ctx, int x, int y, int z, String faceName) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal(
                    "use: no player — a use is performed BY somebody. Run it as one: "
                    + "execute as <player> run customdim use " + x + " " + y + " " + z));
            return 0;
        }
        Direction side = parseFace(faceName);
        if (side == null) {
            source.sendError(Text.literal("use: '" + faceName + "' is not a face "
                    + "(down, up, north, south, east, west)"));
            return 0;
        }

        BlockPos pos = new BlockPos(x, y, z);
        ServerWorld world = player.getServerWorld();
        // Never force the chunk: a diagnostic that generates terrain to answer
        // a question is a watchdog kill waiting to happen ([K1]/[K6]).
        if (!PortalHelper.isColumnResident(world, x, z)) {
            source.sendError(Text.literal("use: " + x + " " + y + " " + z + " is not loaded in "
                    + world.getRegistryKey().getValue()
                    + " — go there first. This command does not generate chunks."));
            return 0;
        }
        if (!player.canInteractWithBlockAt(pos, REACH_SLACK)) {
            source.sendError(Text.literal("use: " + x + " " + y + " " + z + " is out of "
                    + player.getNameForScoreboard() + "'s reach — move closer. Reach is checked "
                    + "here exactly as it is for a real click."));
            return 0;
        }

        BlockState before = world.getBlockState(pos);
        Hand hand = Hand.MAIN_HAND;
        String held = net.minecraft.registry.Registries.ITEM
                .getId(player.getStackInHand(hand).getItem()).toString();

        ActionResult result = player.interactionManager.interactBlock(
                player, world, player.getStackInHand(hand), hand, hitOn(pos, side));

        BlockState after = world.getBlockState(pos);
        String line = "use " + x + " " + y + " " + z + ": " + describe(before)
                + " -> " + describe(after)
                + " (result=" + result.name() + ", hand=" + held
                + ", face=" + side.getName()
                + ", changed=" + (before != after) + ")";
        com.customdimensions.MultiverseServer.LOGGER.info(line);
        source.sendFeedback(() -> Text.literal(line), false);
        return result.isAccepted() ? 1 : 0;
    }
}
