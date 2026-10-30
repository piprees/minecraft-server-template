package com.customdimensions.compiler.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Which dimensions a recompile gives different terrain: a definition whose
 * noise settings file or level stem {@code generator} (biome source included)
 * differs between the pack on disk and the new compile. Worldgen is fixed when
 * a chunk generates (D2), so the caller warns for each of these that already
 * holds chunks.
 */
public final class PackDrift {
    private PackDrift() {
    }

    /** Slugs, sorted, whose worldgen output differs between {@code before} and {@code after}. */
    public static List<String> changed(Map<String, String> before, DatapackCompiler.Output after) {
        List<String> out = new ArrayList<>();
        for (CompileReport.Entry entry : new TreeMap<>(after.report().entries()).values()) {
            for (String path : entry.emitted()) {
                if (differs(path, before.get(path), after.files().get(path))) {
                    out.add(entry.slug());
                    break;
                }
            }
        }
        return out;
    }

    static boolean differs(String path, String before, String after) {
        if (path.contains("/worldgen/noise_settings/")) {
            return before != null && !Objects.equals(parse(before), parse(after));
        }
        if (path.contains("/dimension/") || path.contains("/" + DatapackCompiler.LAZY_FOLDER + "/")) {
            return before != null && !Objects.equals(generator(before), generator(after));
        }
        return false;
    }

    /** The pack's files by pack-relative path; empty when there is no pack. */
    public static SortedMap<String, String> read(Path packDir) {
        SortedMap<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(packDir.resolve("data"))) return files;
        try (Stream<Path> walk = Files.walk(packDir.resolve("data"))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(packDir.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static JsonElement parse(String text) {
        return text == null ? null : JsonParser.parseString(text);
    }

    private static JsonElement generator(String text) {
        JsonElement stem = parse(text);
        return stem instanceof JsonObject o ? o.get("generator") : null;
    }
}
