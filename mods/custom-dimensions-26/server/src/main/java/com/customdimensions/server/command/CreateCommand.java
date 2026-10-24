package com.customdimensions.server.command;

import com.customdimensions.compiler.convert.JsonWriter;
import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.VersionTable;
import com.customdimensions.core.definition.DefinitionReader;
import com.customdimensions.core.definition.DefinitionTemplate;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.level.PackInstaller;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * {@code /customdim create <slug> [template]} (RB-34): writes
 * {@code <definitions>/<slug>.json} from the template definition
 * ({@value DefinitionTemplate#DEFAULT} when none is named) with a fresh seed and
 * portal colour, after reading it back through the v2 reader and compiling it
 * with every other definition. Dimension types are frozen once the world loads,
 * so the dimension opens after the next restart.
 */
final class CreateCommand {
    /** Self-test hook: the folder {@code create} writes to instead of the definitions folder. */
    static Path definitionsOverride;
    private static final Random RANDOM = new SecureRandom();
    private static final SuggestionProvider<CommandSourceStack> TEMPLATES = (context, builder) ->
            SharedSuggestionProvider.suggest(definitionFiles(definitionsDir()).stream().map(p -> {
                String name = p.getFileName().toString();
                return name.substring(0, name.length() - ".json".length());
            }), builder);

    private CreateCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("create").then(Commands.argument("slug", StringArgumentType.word())
                .executes(c -> create(c.getSource(), StringArgumentType.getString(c, "slug"), DefinitionTemplate.DEFAULT))
                .then(Commands.argument("template", StringArgumentType.word()).suggests(TEMPLATES)
                        .executes(c -> create(c.getSource(), StringArgumentType.getString(c, "slug"),
                                StringArgumentType.getString(c, "template"))))));
    }

    static Path definitionsDir() {
        if (definitionsOverride != null) return definitionsOverride;
        return LevelIndex.definitionsDir()
                .orElse(FabricLoader.getInstance().getGameDir().resolve(LevelIndex.DEFINITIONS_FALLBACK));
    }

    static int create(CommandSourceStack source, String slug, String template) {
        if (!DefinitionTemplate.validSlug(slug)) {
            CustomDimCommands.fail(source, "customdim.create.invalid_slug", "slug", slug);
            return 0;
        }
        Path dir = definitionsDir();
        Path target = dir.resolve(slug + ".json");
        boolean known = LevelManager.get().flatMap(m -> CustomDimCommands.find(m, slug)).isPresent();
        if (known || Files.exists(target)) {
            CustomDimCommands.fail(source, "customdim.create.exists", "slug", slug);
            return 0;
        }
        Path templateFile = dir.resolve(template + ".json");
        if (!DefinitionTemplate.validSlug(template) || !Files.isRegularFile(templateFile)) {
            CustomDimCommands.fail(source, "customdim.create.unknown_template", "template", template);
            return 0;
        }
        String text;
        JsonObject derived;
        try {
            List<String> used = new ArrayList<>();
            for (Path file : definitionFiles(dir)) used.addAll(DefinitionTemplate.colours(read(file)));
            String colour = DefinitionTemplate.freshColour(RANDOM, used);
            derived = DefinitionTemplate.derive(read(templateFile), RANDOM.nextLong(), colour);
            text = JsonWriter.write(derived);
            DefinitionReader.read(text, target.toString());
            compiles(dir, slug, text);
        } catch (RuntimeException | IOException e) {
            CustomDimCommands.fail(source, "customdim.create.invalid", "slug", slug, "reason", CustomDimCommands.reason(e));
            return 0;
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(target, text);
        } catch (IOException e) {
            CustomDimCommands.fail(source, "customdim.write_failed", "path", target.toString(), "reason", CustomDimCommands.reason(e));
            return 0;
        }
        String colour = derived.getAsJsonArray("portals").isEmpty() ? "none"
                : derived.getAsJsonArray("portals").get(0).getAsJsonObject().get("color").getAsString();
        CustomDimCommands.ok(source, "customdim.create.done", "path", target.toString(), "template", template,
                "seed", derived.get("seed").getAsString(), "colour", colour, "slug", slug);
        return 1;
    }

    /** Compiles the folder with the new definition added, in a temporary copy, and checks it produced the slug. */
    private static void compiles(Path dir, String slug, String text) throws IOException {
        Path copy = Files.createTempDirectory("customdim-create");
        try {
            for (Path file : definitionFiles(dir)) Files.copy(file, copy.resolve(file.getFileName()));
            Path settings = dir.resolve("settings.json");
            if (Files.isRegularFile(settings)) Files.copy(settings, copy.resolve("settings.json"));
            else Files.writeString(copy.resolve("settings.json"), "{\"schemaVersion\": 2}");
            Files.writeString(copy.resolve(slug + ".json"), text);
            DatapackCompiler.Output output = DatapackCompiler.compile(copy, PackInstaller.vanilla(),
                    VersionTable.forId(PackInstaller.minecraftVersion()));
            if (output.report().entry(slug) == null) throw new IllegalStateException("the compile produced nothing for " + slug);
        } finally {
            try (Stream<Path> walk = Files.walk(copy)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    static List<Path> definitionFiles(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("settings.json"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A definition file as JSON, whole-line {@code //} comments dropped as the v2 reader does. */
    static JsonObject read(Path file) throws IOException {
        String text = Files.readAllLines(file).stream().filter(l -> !l.trim().startsWith("//"))
                .collect(Collectors.joining("\n"));
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
