package com.customdimensions.compiler.convert;

import com.customdimensions.core.config.DimensionConfig;
import com.customdimensions.core.config.DimensionConfigLoader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Converts every 1.21.1 definition in {@code config/custom-dimensions/} to
 * schema v2 and writes the set, settings.json and {@code conversion-report.md}
 * to the output directory, removing v2 files whose v1 source is gone. Each v1
 * file must also load through the carried v1 loader, so only valid v1 converts.
 *
 * <pre>
 * ConvertDefinitions --config &lt;config/custom-dimensions&gt; --register &lt;mod-register.csv&gt;
 *                    --namespaces &lt;namespaces.csv&gt; --out &lt;definitions dir&gt;
 * </pre>
 *
 * Run it through Gradle: {@code ./gradlew :compiler:convertDefinitions}.
 */
public final class ConvertDefinitions {

    public static final String REPORT_FILE = "conversion-report.md";
    private static final String THUMBNAIL_SUFFIX = "_thumb.json";

    private ConvertDefinitions() {
    }

    /** Where the converter reads and writes. */
    public record Paths(Path config, Path modRegister, Path namespaces, Path out) {
    }

    /** The converted set in memory: file name to text, in write order, plus the per-file reports. */
    public record Result(Map<String, String> files, List<FileReport> reports) {
    }

    public static void main(String[] args) {
        // The v1 loader logs a line per dimension at INFO; its warnings and errors still show.
        java.util.logging.Logger.getLogger("customdimensions").setLevel(java.util.logging.Level.WARNING);
        Paths paths = parseArgs(args);
        Result result = convert(paths);
        write(result, paths.out());
        System.out.println("Converted " + (result.files().size() - 2) + " definitions and settings.json into "
                + paths.out() + "; report: " + paths.out().resolve(REPORT_FILE));
    }

    static Paths parseArgs(String[] args) {
        Map<String, String> named = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            named.put(args[i], args[i + 1]);
        }
        List<String> required = List.of("--config", "--register", "--namespaces", "--out");
        for (String flag : required) {
            if (!named.containsKey(flag)) {
                throw new IllegalArgumentException("usage: ConvertDefinitions --config <dir> --register <csv> "
                        + "--namespaces <csv> --out <dir> (missing " + flag + ")");
            }
        }
        return new Paths(Path.of(named.get("--config")), Path.of(named.get("--register")),
                Path.of(named.get("--namespaces")), Path.of(named.get("--out")));
    }

    /** Converts without writing anything. */
    public static Result convert(Paths paths) {
        NamespaceRegister register = NamespaceRegister.load(paths.modRegister(), paths.namespaces());
        DefinitionConverter converter = new DefinitionConverter(register);
        Map<String, Path> v1Files = definitionFiles(paths.config().resolve("dimensions"));
        if (v1Files.isEmpty()) {
            throw new ConversionException("no v1 definitions in " + paths.config().resolve("dimensions"));
        }
        requireV1Loads(paths.config(), v1Files);

        Map<String, String> files = new LinkedHashMap<>();
        List<FileReport> reports = new ArrayList<>();
        Map<String, String> sourceOf = new TreeMap<>();
        for (Map.Entry<String, Path> entry : v1Files.entrySet()) {
            DefinitionConverter.Converted converted = converter.convert(entry.getKey(), readObject(entry.getValue()));
            String previous = sourceOf.put(converted.fileName(), entry.getValue().getFileName().toString());
            if (previous != null) {
                throw new ConversionException(previous + " and " + entry.getValue().getFileName()
                        + " both convert to " + converted.fileName());
            }
            files.put(converted.fileName(), JsonWriter.write(converted.json()));
            reports.add(converted.report());
        }
        DefinitionConverter.Converted settings =
                converter.convertSettings(readObject(paths.config().resolve("settings.json")));
        if (files.containsKey(settings.fileName())) {
            throw new ConversionException("a definition named settings.json would overwrite the settings");
        }
        files.put(settings.fileName(), JsonWriter.write(settings.json()));
        files.put(REPORT_FILE, ConversionReport.render(reports, settings.report(), register));
        reports.add(settings.report());
        return new Result(files, reports);
    }

    /** Writes the set, then deletes JSON files in {@code out} that this run did not produce. */
    public static void write(Result result, Path out) {
        try {
            Files.createDirectories(out);
            for (Map.Entry<String, String> file : result.files().entrySet()) {
                Files.writeString(out.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(out, "*.json")) {
                for (Path existing : stream) {
                    if (!result.files().containsKey(existing.getFileName().toString())) {
                        Files.delete(existing);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the v2 set to " + out, e);
        }
    }

    /** v1 slug to file, sorted by slug, without the roller's thumbnail stamps. */
    static Map<String, Path> definitionFiles(Path dir) {
        Map<String, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.endsWith(THUMBNAIL_SUFFIX)) {
                    files.put(name.substring(0, name.length() - ".json".length()), file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
        return files;
    }

    private static void requireV1Loads(Path config, Map<String, Path> v1Files) {
        Map<String, DimensionConfig> loaded = DimensionConfigLoader.loadAllWithSettings(config, null).dimensions();
        List<String> failed = new ArrayList<>();
        for (String slug : v1Files.keySet()) {
            if (!loaded.containsKey(slug)) {
                failed.add(slug);
            }
        }
        if (!failed.isEmpty()) {
            throw new ConversionException("the v1 loader rejects " + failed
                    + "; fix the v1 file before converting (the loader's log names the problem)");
        }
    }

    private static JsonObject readObject(Path file) {
        try {
            JsonElement parsed = JsonParser.parseString(
                    DimensionConfigLoader.stripJsonComments(Files.readString(file, StandardCharsets.UTF_8)));
            if (!parsed.isJsonObject()) {
                throw new ConversionException(file + ": not a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        } catch (JsonParseException e) {
            throw new ConversionException(file + ": not valid JSON: " + e.getMessage());
        }
    }
}
