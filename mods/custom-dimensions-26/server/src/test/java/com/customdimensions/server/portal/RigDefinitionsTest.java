package com.customdimensions.server.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.customdimensions.core.definition.DefinitionReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The e2e rig's definitions (scripts/e2e/rig-26/) read as the server reads them, each portal on its own frame. */
class RigDefinitionsTest {
    private static final Path RIG = Path.of("../../../scripts/e2e/rig-26");

    @Test
    void everyRigDefinitionReadsAndNoTwoShareAFrame() throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(RIG)) {
            files = listing.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertEquals(4, files.size(), files.toString());
        Set<String> frames = new HashSet<>();
        for (Path file : files) {
            var definition = DefinitionReader.read(file);
            assertFalse(definition.portals().isEmpty(), file.toString());
            String frame = definition.portals().get(0).get("frameBlock").getAsString();
            assertEquals(true, frames.add(frame), "two rig dimensions share the frame " + frame);
        }
    }
}
