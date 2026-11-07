package com.customdimensions.server.command;

import com.customdimensions.server.command.CustomDimCommands.Dimension;
import com.customdimensions.server.level.LevelManager;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;

/** {@code /customdim list}: every dimension with its state, players and loaded chunks. */
final class ListCommand {
    private ListCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("list").executes(c -> list(c.getSource())));
    }

    static int list(CommandSourceStack source) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return 0;
        LevelManager m = manager.get();
        List<Dimension> all = CustomDimCommands.all(m);
        long base = all.stream().filter(Dimension::base).count();
        long open = all.stream().filter(d -> !d.base() && m.level(d.slug()).isPresent()).count();
        CustomDimCommands.ok(source, "customdim.list.header", "count", String.valueOf(all.size()),
                "base", String.valueOf(base), "lazy", String.valueOf(all.size() - base), "open", String.valueOf(open));
        for (Dimension d : all) {
            Optional<ServerLevel> level = CustomDimCommands.level(source.getServer(), m, d);
            CustomDimCommands.ok(source, "customdim.list.line", "dimension", d.slug(),
                    "state", CustomDimCommands.state(m, d),
                    "players", String.valueOf(level.map(l -> l.players().size()).orElse(0)),
                    "chunks", String.valueOf(level.map(l -> l.getChunkSource().getLoadedChunksCount()).orElse(0)));
        }
        return all.size();
    }
}
