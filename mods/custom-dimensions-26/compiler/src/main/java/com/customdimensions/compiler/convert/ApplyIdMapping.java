package com.customdimensions.compiler.convert;

import com.customdimensions.compiler.datapack.BiomeLibrary;
import com.customdimensions.compiler.datapack.CompileReport;
import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.compiler.datapack.StructuresCompiler;
import com.customdimensions.compiler.datapack.VanillaData;
import com.customdimensions.compiler.datapack.VersionTable;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Applies {@code definitions/id-mapping.csv} to every definition in a folder
 * (RB-36). The rewritten set goes to {@code <out>/definitions/}, is compiled
 * there as {@code :compiler:compileDatapack} compiles {@code definitions/}, and
 * {@code <out>/report.md} lists every change. With {@code --write true} the
 * changed files are also written over the source folder, once the table is
 * complete and the rewritten set compiles.
 *
 * <pre>
 * ApplyIdMapping --definitions &lt;dir&gt; --mapping &lt;csv&gt; --register &lt;mod-register.csv&gt;
 *                --namespaces &lt;namespaces.csv&gt; --vanilla &lt;dir&gt; --bridges &lt;dir&gt; --out &lt;dir&gt; [--write true]
 * </pre>
 *
 * Run it through Gradle: {@code ./gradlew :compiler:applyIdMapping [-PwriteDefinitions=true]}.
 */
public final class ApplyIdMapping {

    public static final String REPORT_FILE = "report.md";
    public static final String COMPILE_REPORT_FILE = "compile-report.md";
    public static final String DEFINITIONS_FOLDER = "definitions";
    private static final double FLAG_LOSS = 1.0 / 3.0;

    private ApplyIdMapping() {
    }

    public record Paths(Path definitions, Path mapping, Path modRegister, Path namespaces, Path vanilla, Path bridges,
                        Path out) {
    }

    /** Every file's result, the source and rewritten text by file name, and the compile before and after (null after, with its error, when it fails). */
    public record Run(List<IdMappingApplier.Result> results, Map<String, String> source, Map<String, String> rewritten,
                      DatapackCompiler.Output compiledBefore, DatapackCompiler.Output compiledAfter, String compileError,
                      int rows) {

        public boolean complete() {
            return results.stream().allMatch(r -> r.missing().isEmpty());
        }
    }

    public static void main(String[] args) {
        Map<String, String> named = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            named.put(args[i], args[i + 1]);
        }
        for (String flag : List.of("--definitions", "--mapping", "--register", "--namespaces", "--vanilla", "--bridges", "--out")) {
            if (!named.containsKey(flag)) {
                throw new IllegalArgumentException("usage: ApplyIdMapping --definitions <dir> --mapping <csv> --register <csv> "
                        + "--namespaces <csv> --vanilla <dir> --bridges <dir> --out <dir> [--write true] (missing " + flag + ")");
            }
        }
        Paths paths = new Paths(Path.of(named.get("--definitions")), Path.of(named.get("--mapping")),
                Path.of(named.get("--register")), Path.of(named.get("--namespaces")), Path.of(named.get("--vanilla")),
                Path.of(named.get("--bridges")), Path.of(named.get("--out")));
        boolean inPlace = Boolean.parseBoolean(named.getOrDefault("--write", "false"));
        Run run = run(paths);
        System.out.println("Rewrote " + changed(run).size() + " of " + run.results().size() + " files into "
                + paths.out().resolve(DEFINITIONS_FOLDER) + "; report: " + paths.out().resolve(REPORT_FILE));
        if (run.compileError() != null) {
            throw new ConversionException("the rewritten set does not compile: " + run.compileError());
        }
        if (!run.complete()) {
            String message = missing(run) + " ids from cut mods have no row in " + paths.mapping().getFileName()
                    + "; see \"Missing rows\" in " + paths.out().resolve(REPORT_FILE);
            if (inPlace) throw new ConversionException(message + "; definitions/ is unchanged");
            System.out.println("WARNING: " + message);
        }
        if (inPlace) {
            writeFiles(changedText(run), paths.definitions());
            System.out.println("Wrote " + changed(run).size() + " files over " + paths.definitions());
        }
    }

    /** Applies the table, writes the rewritten set and both reports under {@code out}, and compiles the set. */
    public static Run run(Paths paths) {
        IdMapping mapping = IdMapping.read(paths.mapping());
        NamespaceRegister register = NamespaceRegister.load(paths.modRegister(), paths.namespaces());
        VanillaData vanilla = new VanillaData(paths.vanilla());
        Map<String, String> source = readFolder(paths.definitions());
        IdMappingApplier applier = new IdMappingApplier(mapping, register,
                targets(vanilla, paths.bridges(), namespace(source), source.keySet()));

        List<IdMappingApplier.Result> results = new ArrayList<>();
        Map<String, String> rewritten = new TreeMap<>();
        for (Map.Entry<String, String> file : source.entrySet()) {
            IdMappingApplier.Result result = applier.apply(file.getKey(), parse(file.getKey(), file.getValue()));
            results.add(result);
            rewritten.put(file.getKey(), result.changed() ? JsonWriter.write(result.json()) : file.getValue());
        }
        Path outDefinitions = paths.out().resolve(DEFINITIONS_FOLDER);
        clean(outDefinitions);
        writeFiles(rewritten, outDefinitions);

        DatapackCompiler.Output before = DatapackCompiler.compile(paths.definitions(), paths.bridges(), vanilla, VersionTable.V26_3);
        DatapackCompiler.Output after = null;
        String compileError = null;
        try {
            after = DatapackCompiler.compile(outDefinitions, paths.bridges(), vanilla, VersionTable.V26_3);
        } catch (RuntimeException e) {
            compileError = e.getMessage();
        }
        Run run = new Run(results, source, rewritten, before, after, compileError, mapping.size());
        writeFiles(Map.of(REPORT_FILE, IdMappingReport.render(run, FLAG_LOSS),
                COMPILE_REPORT_FILE, after != null ? after.reportText() : "Compile failed: " + compileError + "\n"), paths.out());
        return run;
    }

    /** What the compiler knows over vanilla 26.3, the shipped biome library, the bridges and the set's own slugs. */
    public static IdMappingApplier.Targets targets(VanillaData vanilla, Path bridges, String namespace, Set<String> files) {
        BiomeLibrary.Known biomes = BiomeLibrary.shipped().known(vanilla, namespace);
        StructuresCompiler.Catalogue catalogue = StructuresCompiler.Catalogue.of(vanilla, bridges);
        Set<String> blocks = vanilla.registry("minecraft:block");
        Set<String> items = vanilla.registry("minecraft:item");
        Set<String> dimensions = new java.util.HashSet<>(Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
        files.forEach(f -> dimensions.add(namespace + ":" + f.substring(0, f.length() - ".json".length())));
        return new IdMappingApplier.Targets() {
            public boolean biome(String id) {
                return biomes.contains(id);
            }

            public boolean structure(String id) {
                return catalogue.knowsStructure(id);
            }

            public boolean structureSet(String id) {
                return catalogue.sets().contains(id);
            }

            public boolean block(String id) {
                return blocks.contains(id);
            }

            public boolean item(String id) {
                return items.contains(id);
            }

            public boolean dimension(String id) {
                return dimensions.contains(id);
            }
        };
    }

    private static long missing(Run run) {
        return run.results().stream().flatMap(r -> r.missing().stream()).map(IdMappingApplier.Missing::id).distinct().count();
    }

    static List<IdMappingApplier.Result> changed(Run run) {
        return run.results().stream().filter(IdMappingApplier.Result::changed).toList();
    }

    private static Map<String, String> changedText(Run run) {
        Map<String, String> out = new TreeMap<>();
        changed(run).forEach(r -> out.put(r.file(), run.rewritten().get(r.file())));
        return out;
    }

    private static String namespace(Map<String, String> source) {
        String settings = source.get("settings.json");
        if (settings == null) return "adventure";
        JsonElement ns = parse("settings.json", settings).get("namespace");
        return ns != null && ns.isJsonPrimitive() ? ns.getAsString() : "adventure";
    }

    /** Every {@code .json} file in {@code dir} by name, settings.json included. */
    static Map<String, String> readFolder(Path dir) {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                out.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + dir, e);
        }
        if (out.isEmpty()) {
            throw new ConversionException("no definitions in " + dir);
        }
        return out;
    }

    private static JsonObject parse(String file, String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new ConversionException(file + ": not a JSON object");
            return parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ConversionException(file + ": not valid JSON: " + e.getMessage());
        }
    }

    private static void writeFiles(Map<String, String> files, Path dir) {
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, String> file : files.entrySet()) {
                Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write to " + dir, e);
        }
    }

    /** Deletes the JSON files an earlier run left in {@code dir}. */
    private static void clean(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot clean " + dir, e);
        }
    }

    /** Skipped biomes and structure warnings summed over a compile. */
    static int[] compileCounts(DatapackCompiler.Output output) {
        int skipped = 0;
        int warnings = 0;
        for (CompileReport.Entry entry : output.report().entries().values()) {
            skipped += entry.skippedBiomes().size();
            warnings += entry.structureWarnings().size();
        }
        return new int[] {skipped, warnings};
    }
}
