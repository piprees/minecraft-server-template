package com.customdimensions.compiler.datapack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where {@link VanillaData} reads vanilla's files from, by root-relative path:
 * {@code data/minecraft/**} as the server jar carries it and
 * {@code reports/biome_parameters/minecraft/<list>.json} as the data generator
 * writes it. A directory extracted by {@code :compiler:vanillaData} is one
 * source; the running game is the other.
 */
public interface VanillaSource {

    /** The text of the file at {@code path}, or empty when there is none. */
    Optional<String> read(String path);

    /** The names of the files directly inside {@code folder}, sorted; empty when it does not exist. */
    List<String> list(String folder);

    /** Where the files come from, for messages. */
    String describe();

    /** The files under {@code root}, laid out as {@code :compiler:vanillaData} extracts them. */
    static VanillaSource directory(Path root) {
        return new VanillaSource() {
            @Override
            public Optional<String> read(String path) {
                Path file = root.resolve(path);
                if (!Files.isRegularFile(file)) return Optional.empty();
                try {
                    return Optional.of(Files.readString(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public List<String> list(String folder) {
                return names(root.resolve(folder));
            }

            @Override
            public String describe() {
                return root.toString();
            }
        };
    }

    /** The sorted file names directly inside {@code dir}, any file system; empty when it is not a directory. */
    static List<String> names(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
