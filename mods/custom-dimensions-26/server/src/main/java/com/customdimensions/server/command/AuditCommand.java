package com.customdimensions.server.command;

import com.customdimensions.compiler.datapack.CompileReport;
import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.PackDrift;
import com.customdimensions.compiler.datapack.PackInputs;
import com.customdimensions.compiler.datapack.VersionTable;
import com.customdimensions.core.config.MultiverseConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.level.PackInstaller;
import com.customdimensions.server.portal.PortalRegistry;
import com.customdimensions.server.portal.PortalZone;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * {@code /customdim audit}: writes {@code <world>/customdimensions/audit.md}
 * and replies with its counts. Compares the definitions, compiled afresh,
 * against the pack on disk (stale, missing and removed dimensions); lists
 * level folders with no definition, portal links to unknown dimensions, frame
 * blocks and igniters this server does not have, Bedrock height warnings and
 * the compile report's skipped biomes and unsupported fields.
 */
final class AuditCommand {
    static final String FILE = "audit.md";

    /** What one audit found. {@code unknownIds} reads {@code block <id>} or {@code item <id>}. */
    record Audit(String inputs, List<String> stale, List<String> missing, List<String> removed, List<String> orphans,
                 List<String> links, Set<String> unknownIds, List<String> idUses, List<String> bedrock,
                 List<String> warnings, String compileError) {}

    private AuditCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("audit").executes(c -> audit(c.getSource())));
    }

    static int audit(CommandSourceStack source) {
        Optional<LevelManager> manager = CustomDimCommands.manager(source);
        if (manager.isEmpty()) return 0;
        MinecraftServer server = source.getServer();
        Audit audit = run(server, manager.get());
        Path file = file(server);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, markdown(audit));
        } catch (IOException e) {
            CustomDimCommands.fail(source, "customdim.write_failed", "path", file.toString(), "reason", CustomDimCommands.reason(e));
            return 0;
        }
        CustomDimCommands.ok(source, "customdim.audit.done", "path", file.toString(),
                "stale", String.valueOf(audit.stale().size()), "missing", String.valueOf(audit.missing().size()),
                "removed", String.valueOf(audit.removed().size()), "orphans", String.valueOf(audit.orphans().size()),
                "links", String.valueOf(audit.links().size()), "ids", String.valueOf(audit.unknownIds().size()),
                "bedrock", String.valueOf(audit.bedrock().size()), "warnings", String.valueOf(audit.warnings().size()));
        return 1;
    }

    static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("customdimensions").resolve(FILE);
    }

    static Audit run(MinecraftServer server, LevelManager manager) {
        Path world = server.getWorldPath(LevelResource.ROOT);
        Path pack = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PackInstaller.PACK_NAME);
        Optional<Path> dir = LevelIndex.definitionsDir();
        List<String> stale = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> bedrock = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String compileError = null;
        String inputs = dir.isEmpty() ? "no definitions folder" : PackInputs.read(pack)
                .map(h -> h.equals(PackInputs.hash(dir.get(), PackInstaller.modVersion(), PackInstaller.minecraftVersion()))
                        ? "current" : "stale").orElse("no hash in the pack");
        Map<String, String> disk = PackDrift.read(pack);
        if (dir.isPresent()) {
            try {
                DatapackCompiler.Output output = DatapackCompiler.compile(dir.get(), PackInstaller.vanilla(),
                        VersionTable.forId(PackInstaller.minecraftVersion()));
                for (CompileReport.Entry entry : new TreeMap<>(output.report().entries()).values()) {
                    Optional<String> stem = entry.emitted().stream().filter(AuditCommand::isStem).findFirst();
                    if (stem.isPresent() && !disk.containsKey(stem.get())) {
                        missing.add(entry.slug());
                    } else if (entry.emitted().stream().anyMatch(p -> !Objects.equals(disk.get(p), output.files().get(p)))) {
                        stale.add(entry.slug());
                    }
                    entry.bedrock().forEach(w -> bedrock.add(entry.slug() + ": " + w));
                    entry.skippedBiomes().forEach(b -> warnings.add(entry.slug() + ": skipped biome " + b));
                    entry.unsupported().forEach(u -> warnings.add(entry.slug() + ": unsupported " + u));
                }
                disk.keySet().stream().filter(AuditCommand::isStem).filter(p -> !output.files().containsKey(p))
                        .forEach(removed::add);
            } catch (RuntimeException e) {
                compileError = CustomDimCommands.reason(e);
            }
        }
        Set<String> unknown = new TreeSet<>();
        List<String> uses = new ArrayList<>();
        dir.ifPresent(d -> unknownIds(d, unknown, uses));
        return new Audit(inputs, stale, missing, removed, orphans(world, manager, dir), links(server, manager),
                unknown, uses, bedrock, warnings, compileError);
    }

    private static boolean isStem(String path) {
        return path.contains("/dimension/") || path.contains("/" + DatapackCompiler.LAZY_FOLDER + "/");
    }

    /** Level folders outside {@code minecraft} with no definition, and compiled levels whose definition is gone. */
    static List<String> orphans(Path world, LevelManager manager, Optional<Path> dir) {
        Set<String> out = new TreeSet<>();
        Path dimensions = world.resolve("dimensions");
        Set<String> bases = new TreeSet<>(LevelIndex.baseWorldKeys(manager.index().namespace()).values());
        if (Files.isDirectory(dimensions)) {
            try (Stream<Path> namespaces = Files.list(dimensions)) {
                for (Path ns : namespaces.filter(Files::isDirectory).toList()) {
                    String namespace = ns.getFileName().toString();
                    if (namespace.equals("minecraft")) continue;
                    try (Stream<Path> levels = Files.list(ns)) {
                        for (Path level : levels.filter(Files::isDirectory).toList()) {
                            String slug = level.getFileName().toString();
                            if (bases.contains(namespace + ":" + slug)) continue;
                            if (dir.isEmpty() || !Files.isRegularFile(dir.get().resolve(slug + ".json"))) {
                                out.add(namespace + ":" + slug + " (level folder, no definition)");
                            }
                        }
                    }
                }
            } catch (IOException e) {
                out.add("dimensions folder unreadable: " + e.getMessage());
            }
        }
        manager.index().lazy().values().stream().filter(e -> e.definition() == null)
                .forEach(e -> out.add(e.key().identifier() + " (compiled, no definition)"));
        return new ArrayList<>(out);
    }

    /** Placed portals and catalogue portals that lead to, or sit in, a dimension the server does not know. */
    static List<String> links(MinecraftServer server, LevelManager manager) {
        Set<String> known = new TreeSet<>();
        server.levelKeys().forEach(k -> known.add(k.identifier().toString()));
        manager.index().lazy().values().forEach(e -> known.add(e.key().identifier().toString()));
        manager.index().baseWorlds().forEach(w -> known.add(w.key().identifier().toString()));
        List<String> out = new ArrayList<>();
        for (PortalZone zone : PortalRegistry.get().allZones()) {
            String from = id(zone.world);
            String to = id(zone.target);
            if (!known.contains(from) || (to != null && !known.contains(to))) {
                out.add("placed portal in " + from + " at " + zone.landingCell().toShortString() + " leads to " + to);
            }
        }
        for (PortalDefinition portal : MultiverseConfig.getInstance().getPortals()) {
            String to = portal.getTargetDimension();
            if (to != null && !known.contains(to)) {
                out.add("portal (frame " + portal.getFrameBlock() + ") leads to unknown " + to);
            }
        }
        return out;
    }

    private static String id(ResourceKey<Level> key) {
        return key == null ? null : key.identifier().toString();
    }

    /** Frame blocks and igniter items the definitions and settings name that this server's registries lack. */
    static void unknownIds(Path dir, Set<String> unknown, List<String> uses) {
        for (Path file : CreateCommand.definitionFiles(dir)) {
            String slug = file.getFileName().toString().replace(".json", "");
            JsonObject def;
            try {
                def = CreateCommand.read(file);
            } catch (IOException | RuntimeException e) {
                uses.add(slug + ": unreadable: " + e.getMessage());
                continue;
            }
            if (!def.has("portals")) continue;
            for (JsonElement p : def.getAsJsonArray("portals")) {
                JsonObject portal = p.getAsJsonObject();
                for (String field : List.of("frameBlock", "framePlaceBlock", "frameMaterials")) {
                    strings(portal.get(field)).forEach(id -> check(BuiltInRegistries.BLOCK, Registries.BLOCK, "block", id,
                            slug + " " + field, unknown, uses));
                }
                strings(portal.get("igniterItem")).forEach(id -> check(BuiltInRegistries.ITEM, Registries.ITEM, "item", id,
                        slug + " igniterItem", unknown, uses));
            }
        }
        Path settings = dir.resolve("settings.json");
        if (!Files.isRegularFile(settings)) return;
        try {
            JsonObject s = CreateCommand.read(settings);
            if (s.has("frames")) {
                s.getAsJsonObject("frames").entrySet().forEach(e -> strings(e.getValue()).forEach(id ->
                        check(BuiltInRegistries.BLOCK, Registries.BLOCK, "block", id, "settings frames." + e.getKey(), unknown, uses)));
            }
            if (s.has("defaults") && s.getAsJsonObject("defaults").has("frameBlock")) {
                strings(s.getAsJsonObject("defaults").get("frameBlock")).forEach(id -> check(BuiltInRegistries.BLOCK,
                        Registries.BLOCK, "block", id, "settings defaults.frameBlock", unknown, uses));
            }
        } catch (IOException | RuntimeException e) {
            uses.add("settings.json: unreadable: " + e.getMessage());
        }
    }

    private static <T> void check(Registry<T> registry, ResourceKey<? extends Registry<T>> key, String kind, String value,
                                  String where, Set<String> unknown, List<String> uses) {
        boolean tag = value.startsWith("#");
        Identifier id = Identifier.tryParse(tag ? value.substring(1) : value);
        boolean present = id != null && (tag ? registry.get(TagKey.create(key, id)).isPresent() : registry.containsKey(id));
        if (!present) {
            unknown.add(kind + " " + value);
            uses.add(where + ": " + kind + " " + value);
        }
    }

    /** Every string in a value: itself, or the strings of an array or object, depth first. */
    static List<String> strings(JsonElement value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isJsonNull()) return out;
        if (value.isJsonPrimitive()) {
            out.add(value.getAsString());
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(e -> out.addAll(strings(e)));
        } else {
            value.getAsJsonObject().entrySet().forEach(e -> out.addAll(strings(e.getValue())));
        }
        return out;
    }

    static String markdown(Audit a) {
        StringBuilder out = new StringBuilder("# Custom dimensions audit\n\n");
        out.append("Written by `/customdim audit`. Pack inputs: ").append(a.inputs()).append(".\n");
        if (a.compileError() != null) out.append("\nThe definitions do not compile: ").append(a.compileError()).append('\n');
        section(out, "Stale in the pack (the next start recompiles them)", a.stale());
        section(out, "Missing from the pack (opens after the next restart)", a.missing());
        section(out, "In the pack with no definition", a.removed());
        section(out, "Orphans", a.orphans());
        section(out, "Portal links to unknown dimensions", a.links());
        section(out, "Ids this server does not have", a.idUses());
        section(out, "Bedrock height warnings", a.bedrock());
        section(out, "Compile warnings", a.warnings());
        out.append(com.customdimensions.server.firewall.WorldgenFirewall.markdown());
        return out.toString();
    }

    private static void section(StringBuilder out, String title, List<String> items) {
        out.append("\n## ").append(title).append(" (").append(items.size()).append(")\n\n");
        if (items.isEmpty()) out.append("None.\n");
        items.forEach(i -> out.append("- ").append(i).append('\n'));
    }
}
