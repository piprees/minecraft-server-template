package com.customdimensions.core.score;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContactSheetTest {

    @Test
    void theSheetLinksEachSeedsImagesAndScorecardByRelativePathAndLoadsNothingFromOutside(@TempDir Path dir) throws Exception {
        Scorecard card = new Scorecard("d", 42, Scorecard.Verdict.SCORED, "", 1, 2, List.of(
                new Scorecard.Entry("water_matches_intent", Criterion.Group.APPROPRIATE, "none: <0.2", "score", 0.5, "a & b")));
        Map<String, String> images = new LinkedHashMap<>();
        images.put("biome", "42-r1024-biome.png");
        images.put("structures", "42-r1024-structures.png");
        Path out = ContactSheet.write(dir, "the_test", 5, "5 seeds", List.of(
                new ContactSheet.Entry(1, card, images, "42-r1024-scorecard.json", Map.of("minecraft:igloo", "#FF0000"))));
        String html = Files.readString(out);
        assertTrue(html.contains("src=\"42-r1024-biome.png\""));
        assertTrue(html.contains("href=\"42-r1024-scorecard.json\""));
        assertTrue(html.contains("a &amp; b"));
        assertTrue(html.contains("none: &lt;0.2"));
        assertTrue(html.contains("#1 seed 42"));
        assertFalse(html.contains("http://") || html.contains("https://") || html.contains("src=\"/"));
        assertFalse(Files.exists(dir.resolve("index.html.tmp")));
    }
}
