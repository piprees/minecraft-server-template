package com.customdimensions.compiler.datapack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The SHA-256 of everything a compile depends on: each definition file and
 * {@code settings.json} by name and bytes, the mod version and the Minecraft
 * version. The pack carries it in {@value #FILE}; an equal hash means the
 * pack on disk is what a compile would write.
 */
public final class PackInputs {
    public static final String FILE = "inputs.sha256";

    private PackInputs() {
    }

    public static String hash(Path definitionsDir, String modVersion, String minecraftVersion) {
        MessageDigest digest = sha256();
        field(digest, "mod", modVersion.getBytes(StandardCharsets.UTF_8));
        field(digest, "minecraft", minecraftVersion.getBytes(StandardCharsets.UTF_8));
        for (Path file : inputFiles(definitionsDir)) {
            try {
                field(digest, file.getFileName().toString(), Files.readAllBytes(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The hash the pack in {@code packDir} was compiled from, or empty. */
    public static Optional<String> read(Path packDir) {
        Path file = packDir.resolve(FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file).trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void write(Path packDir, String hash) {
        try {
            Files.writeString(packDir.resolve(FILE), hash + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every {@code *.json} in the folder, {@code settings.json} included, by name. */
    static List<Path> inputFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void field(MessageDigest digest, String name, byte[] bytes) {
        byte[] label = name.getBytes(StandardCharsets.UTF_8);
        digest.update(length(label.length));
        digest.update(label);
        digest.update(length(bytes.length));
        digest.update(bytes);
    }

    private static byte[] length(int n) {
        return new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n};
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
