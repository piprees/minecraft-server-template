package com.customdimensions.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two copies of the wire contract are the same file.
 *
 * <p>The mods share no code, so {@code CompanionPayloads} exists twice and its
 * own doc requires the copies to stay byte-identical: same channel ids, same
 * codec order, same field types. Nothing else enforces that, and a half-applied
 * edit does not fail to compile — it fails at runtime, on one channel, as a
 * decode that reads the wrong number of bytes and desynchronises the rest of
 * the connection.
 */
class CompanionPayloadsContractTest {

    private static final Path CLIENT_COPY = Path.of("src", "main", "java",
            "com", "customdimensions", "client", "CompanionPayloads.java");
    private static final Path SERVER_COPY = Path.of("..", "custom-dimensions", "src", "main",
            "java", "com", "customdimensions", "companion", "CompanionPayloads.java");

    private static List<String> body(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file),
                "wire contract not found at " + file.toAbsolutePath()
                        + " — this test reads the sources, it must never silently skip");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(!lines.isEmpty() && lines.get(0).startsWith("package "),
                file + " does not start with its package declaration");
        return lines.subList(1, lines.size());
    }

    @Test
    void bothCopiesOfTheWireContractAreTheSameFileBelowTheirPackageLine() throws IOException {
        assertEquals(body(SERVER_COPY), body(CLIENT_COPY),
                "the two CompanionPayloads copies have drifted. They describe one wire format "
                        + "and each side decodes with its own copy, so a difference is a protocol "
                        + "break that compiles cleanly on both sides.");
    }
}
