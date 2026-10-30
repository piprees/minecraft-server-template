package com.customdimensions.server.command;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.DefinitionTemplate;
import com.customdimensions.server.command.CustomDimCommands.Dimension;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The {@code /customdim} commands run from a console source with their output
 * captured: list against the level manager, info for a base, a lazy and a
 * clone dimension, tp refused from the console and done with a FakePlayer,
 * delete without confirm changing nothing, create into a temporary
 * definitions folder, audit's unknown ids and catalogue's file.
 */
public final class CommandSelfTest {
    /** The ids the shipped definitions name that a server without the pack's mods lacks. */
    static final Set<String> EXPECTED_UNKNOWN = Set.of("block chipped:runic_carved_obsidian",
            "item regions_unexplored:lavender_wisteria_sapling");

    private CommandSelfTest() {}

    /** Captures what a command sends to its source. */
    static final class Capture implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            lines.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private record Run(CommandSourceStack source, Capture capture) {}

    private static Run console(MinecraftServer server) {
        Capture capture = new Capture();
        return new Run(server.createCommandSourceStack().withSource(capture), capture);
    }

    /** Runs {@code command} (without the slash) from a fresh console source and returns what it said. */
    static List<String> run(MinecraftServer server, String command) {
        Run run = console(server);
        server.getCommands().performPrefixedCommand(run.source(), command);
        return run.capture().lines;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        LevelManager manager = LevelManager.get().orElseThrow(() -> new IllegalStateException("no level manager"));
        lines.add(check("command list matches the level manager", () -> list(server, manager)));
        lines.add(check("command info for a base, a lazy and a clone dimension", () -> info(server, manager)));
        lines.add(check("command tp is refused from the console without a player", () -> {
            List<String> out = run(server, "customdim tp overworld");
            String expected = CustomDimCommands.text("customdim.tp.console");
            if (!out.equals(List.of(expected))) throw new AssertionError(out.toString());
            return out.getFirst();
        }));
        lines.add(check("command delete without confirm changes nothing", () -> deletePreview(server, manager)));
        lines.add(check("command create writes a definition that reads and compiles", () -> create(server)));
        lines.add(check("command audit finds exactly the expected unknown ids", () -> audit(server, manager)));
        lines.add(check("command catalogue writes the registries", () -> catalogue(server)));
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        teleport(server, manager).whenComplete((line, error) -> {
            lines.add(error == null ? line : "FAIL command tp moves a FakePlayer: " + error);
            done.complete(lines);
        });
        return done;
    }

    private static String list(MinecraftServer server, LevelManager manager) {
        List<String> out = run(server, "customdim list");
        int expected = manager.index().baseWorlds().size() + manager.index().lazy().size();
        String header = CustomDimCommands.text("customdim.list.header", "count", String.valueOf(expected),
                "base", String.valueOf(manager.index().baseWorlds().size()),
                "lazy", String.valueOf(manager.index().lazy().size()),
                "open", String.valueOf(manager.loadedLazyCount()));
        if (out.size() != expected + 1 || !out.getFirst().equals(header)) {
            throw new AssertionError(out.size() + " lines, header " + out.getFirst() + ", expected " + header);
        }
        if (out.stream().noneMatch(l -> l.startsWith("overworld: base, "))) throw new AssertionError("no overworld line");
        return out.getFirst();
    }

    private static String info(MinecraftServer server, LevelManager manager) {
        List<Dimension> all = CustomDimCommands.all(manager);
        Dimension lazy = all.stream().filter(d -> !d.base() && d.definition() != null
                && !"clone".equals(d.definition().generator().type())).findFirst().orElseThrow();
        Dimension clone = all.stream().filter(d -> !d.base() && d.definition() != null
                && "clone".equals(d.definition().generator().type())).findFirst().orElseThrow();
        List<String> summary = new ArrayList<>();
        for (Dimension d : List.of(all.getFirst(), lazy, clone)) {
            List<String> out = run(server, "customdim info " + d.slug());
            String generator = CustomDimCommands.text("customdim.info.generator", "generator", InfoCommand.generator(d.definition()));
            if (!out.contains(generator)) throw new AssertionError(d.slug() + ": no generator line in " + out);
            if (out.stream().noneMatch(l -> l.startsWith("Height: y "))) throw new AssertionError(d.slug() + ": no height in " + out);
            if (out.size() < 10) throw new AssertionError(d.slug() + ": " + out);
            summary.add(d.slug() + " (" + out.size() + " lines, " + generator + ")");
        }
        if (!InfoCommand.generator(clone.definition()).startsWith("clone of ")) throw new AssertionError("clone reads " + summary);
        List<String> unknown = run(server, "customdim info no_such_dimension");
        if (!unknown.equals(List.of(CustomDimCommands.text("customdim.unknown", "dimension", "no_such_dimension")))) {
            throw new AssertionError("unknown dimension: " + unknown);
        }
        return String.join("; ", summary);
    }

    private static String deletePreview(MinecraftServer server, LevelManager manager) {
        Dimension target = CustomDimCommands.all(manager).stream().filter(d -> !d.base()).findFirst().orElseThrow();
        Path folder = manager.dimensionPath(target.key());
        boolean existed = Files.exists(folder);
        LevelManager.State before = manager.state(target.slug());
        List<String> out = run(server, "customdim delete " + target.slug());
        String preview = CustomDimCommands.text("customdim.delete.preview", "dimension", target.slug(), "path", folder.toString());
        if (!out.equals(List.of(preview))) throw new AssertionError(out.toString());
        if (Files.exists(folder) != existed || manager.state(target.slug()) != before) {
            throw new AssertionError("the preview changed " + target.slug());
        }
        List<String> base = run(server, "customdim delete overworld confirm");
        if (!base.equals(List.of(CustomDimCommands.text("customdim.delete.base", "dimension", "overworld")))) {
            throw new AssertionError("base world: " + base);
        }
        return target.slug() + " untouched (folder " + (existed ? "present" : "absent") + ", " + before + "); overworld refused";
    }

    private static String create(MinecraftServer server) throws Exception {
        Path source = LevelIndex.definitionsDir().orElseThrow();
        Path dir = Files.createTempDirectory("customdim-create-selftest");
        try (Stream<Path> files = Files.list(source)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) Files.copy(f, dir.resolve(f.getFileName()));
        }
        CreateCommand.definitionsOverride = dir;
        try {
            List<String> out = run(server, "customdim create selftest_created");
            Path written = dir.resolve("selftest_created.json");
            if (!Files.isRegularFile(written)) throw new AssertionError(out + ", no file");
            Definition created = DefinitionReader.read(written);
            JsonObject template = CreateCommand.read(dir.resolve(DefinitionTemplate.DEFAULT + ".json"));
            JsonObject json = JsonParser.parseString(Files.readString(written)).getAsJsonObject();
            String reply = CustomDimCommands.text("customdim.create.done", "path", written.toString(),
                    "template", DefinitionTemplate.DEFAULT, "seed", json.get("seed").getAsString(),
                    "colour", json.getAsJsonArray("portals").get(0).getAsJsonObject().get("color").getAsString(),
                    "slug", "selftest_created");
            if (!out.equals(List.of(reply))) throw new AssertionError(out + " is not " + reply);
            if (created.seed().value() == template.get("seed").getAsLong()) throw new AssertionError("the seed was copied");
            Set<String> colours = DefinitionTemplate.colours(json);
            if (colours.size() != 1 || DefinitionTemplate.colours(template).containsAll(colours)) {
                throw new AssertionError("portal colours " + colours);
            }
            List<String> again = run(server, "customdim create selftest_created");
            List<String> bad = run(server, "customdim create Bad_Slug");
            List<String> noTemplate = run(server, "customdim create selftest_other no_such_template");
            List<String> base = run(server, "customdim create overworld");
            if (!again.equals(List.of(CustomDimCommands.text("customdim.create.exists", "slug", "selftest_created")))
                    || !bad.equals(List.of(CustomDimCommands.text("customdim.create.invalid_slug", "slug", "Bad_Slug")))
                    || !noTemplate.equals(List.of(CustomDimCommands.text("customdim.create.unknown_template", "template", "no_such_template")))
                    || !base.equals(List.of(CustomDimCommands.text("customdim.create.exists", "slug", "overworld")))) {
                throw new AssertionError("refusals: " + again + bad + noTemplate + base);
            }
            if (Files.exists(dir.resolve("selftest_other.json")) || Files.exists(source.resolve("selftest_created.json"))) {
                throw new AssertionError("a refused or redirected create wrote a file");
            }
            return "seed " + created.seed().value() + ", colour " + colours + "; exists, bad slug, unknown template refused";
        } finally {
            CreateCommand.definitionsOverride = null;
        }
    }

    private static String audit(MinecraftServer server, LevelManager manager) {
        AuditCommand.Audit audit = AuditCommand.run(server, manager);
        List<String> out = run(server, "customdim audit");
        if (out.size() != 1 || !out.getFirst().startsWith("Audit written to ")) throw new AssertionError(out.toString());
        if (!Files.isRegularFile(AuditCommand.file(server))) throw new AssertionError("no " + AuditCommand.file(server));
        if (!audit.unknownIds().equals(EXPECTED_UNKNOWN)) throw new AssertionError("unknown ids " + audit.unknownIds());
        if (audit.compileError() != null) throw new AssertionError("compile: " + audit.compileError());
        return "unknown " + audit.unknownIds() + "; inputs " + audit.inputs() + ", " + audit.stale().size() + " stale, "
                + audit.missing().size() + " missing, " + audit.orphans().size() + " orphans, " + audit.links().size()
                + " links, " + audit.bedrock().size() + " Bedrock, " + audit.warnings().size() + " compile warnings";
    }

    private static String catalogue(MinecraftServer server) throws Exception {
        Path out = Files.createTempDirectory("customdim-catalogue").resolve("registries.json");
        CatalogueCommand.outputOverride = out;
        try {
            List<String> said = run(server, "customdim catalogue");
            JsonObject json = JsonParser.parseString(Files.readString(out)).getAsJsonObject();
            for (String key : List.of("blocks", "items", "biomes", "features", "entityTypes")) {
                if (json.getAsJsonArray(key).isEmpty()) throw new AssertionError(key + " is empty");
            }
            for (String key : List.of("structures", "structureSets", "biomeTags")) {
                if (json.getAsJsonObject(key).isEmpty()) throw new AssertionError(key + " is empty");
            }
            JsonObject tags = json.getAsJsonObject("tags");
            for (String registry : List.of("minecraft:block", "minecraft:item", "minecraft:entity_type")) {
                if (tags.getAsJsonObject(registry).isEmpty()) throw new AssertionError("no " + registry + " tags");
            }
            if (!json.getAsJsonArray("blocks").contains(new com.google.gson.JsonPrimitive("minecraft:stone"))
                    || !tags.getAsJsonObject("minecraft:block").getAsJsonArray("minecraft:logs").contains(
                    new com.google.gson.JsonPrimitive("minecraft:oak_log"))) {
                throw new AssertionError("stone or #logs ∋ oak_log missing");
            }
            JsonObject counts = json.getAsJsonObject("counts");
            if (said.size() != 1 || !said.getFirst().startsWith("Catalogue written to ")) throw new AssertionError(said.toString());
            return counts.toString();
        } finally {
            CatalogueCommand.outputOverride = null;
        }
    }

    private static CompletableFuture<String> teleport(MinecraftServer server, LevelManager manager) {
        Dimension target = CustomDimCommands.find(manager, "selftest_void")
                .or(() -> CustomDimCommands.all(manager).stream().filter(d -> !d.base()).findFirst()).orElseThrow();
        FakePlayer player = FakePlayer.get(server.overworld(),
                new GameProfile(UUID.nameUUIDFromBytes("customdimensions:commands".getBytes()), "cd-commands"));
        Run run = console(server);
        return TeleportCommand.send(run.source(), manager, target, player).thenApply(level -> {
            Optional<ServerLevel> open = manager.level(target.slug());
            if (open.isEmpty() || player.level() != open.get()) {
                throw new AssertionError("the FakePlayer is in " + player.level().dimension().identifier());
            }
            String done = CustomDimCommands.text("customdim.tp.done", "player", "cd-commands", "dimension", target.slug());
            if (!run.capture().lines.contains(done)) throw new AssertionError(run.capture().lines.toString());
            return "PASS command tp moves a FakePlayer: " + target.slug() + " at " + player.blockPosition().toShortString();
        });
    }

    private static String check(String name, Check check) {
        try {
            return "PASS " + name + ": " + check.run();
        } catch (Throwable t) {
            return "FAIL " + name + ": " + t;
        }
    }

    @FunctionalInterface
    private interface Check {
        String run() throws Exception;
    }
}
