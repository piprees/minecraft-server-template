package com.customdimensions.server.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /customdim use <x> <y> <z> [face]}, run as a player ({@code execute
 * as <player> run ...}): the main-hand item used on a block face through the
 * game mode's own {@code useItemOn}, so the use-block event and ignition run
 * as a right-click does. Refuses a block beyond reach rather than reaching
 * through a wall. Registered with {@code e2e-state}, only under
 * {@code -Dcustomdimensions.e2e=true}.
 */
final class UseCommand {
    /** Survival reach plus a margin for a block-centre measurement. */
    static final double REACH = 6.0;

    private UseCommand() {
    }

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        if (!E2eStateCommand.enabled()) {
            return;
        }
        root.then(Commands.literal("use")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(c -> use(c, "up"))
                                        .then(Commands.argument("face", StringArgumentType.word())
                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(Direction.values())
                                                                .map(d -> d.getSerializedName()), b))
                                                .executes(c -> use(c, StringArgumentType.getString(c, "face"))))))));
    }

    private static int use(CommandContext<CommandSourceStack> c, String faceName) {
        CommandSourceStack source = c.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            CustomDimCommands.fail(source, "customdim.use.no_player");
            return 0;
        }
        Direction face = Direction.byName(faceName.toLowerCase(Locale.ROOT));
        if (face == null) {
            CustomDimCommands.fail(source, "customdim.use.face", "face", faceName);
            return 0;
        }
        BlockPos pos = new BlockPos(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"),
                IntegerArgumentType.getInteger(c, "z"));
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        double reach = player.getEyePosition().distanceTo(hit);
        if (reach > REACH) {
            CustomDimCommands.fail(source, "customdim.use.reach", "player", player.getName().getString(),
                    "distance", String.format(Locale.ROOT, "%.1f", reach), "reach", String.valueOf(REACH));
            return 0;
        }
        ItemStack stack = player.getMainHandItem();
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String block = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).getBlock()).toString();
        InteractionResult result = player.gameMode.useItemOn(player, player.level(), stack, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, pos, false));
        CustomDimCommands.ok(source, "customdim.use.done", "player", player.getName().getString(), "item", item,
                "block", block, "x", String.valueOf(pos.getX()), "y", String.valueOf(pos.getY()),
                "z", String.valueOf(pos.getZ()), "face", face.getSerializedName(), "result", result.toString());
        return result.consumesAction() ? 1 : 0;
    }
}
