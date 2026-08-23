package com.customdimensions.web;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every {@code {{NAME}}} in the viewer template must be one {@link ViewerPage}
 * substitutes. An unwired placeholder renders literally on the page — the
 * flagged toggle would read "below {{SCORE_THRESHOLD}}" — and nothing else
 * fails, so this is the only thing standing between that and a release.
 */
class ViewerTemplateTest {

    private static final Set<String> SUBSTITUTED =
            Set.of("FAMILY_BUTTONS", "MOOD_OPTIONS", "SCORE_THRESHOLD", "DIMENSIONS_HTML");

    private static String template() throws Exception {
        try (InputStream in = ViewerPage.class.getResourceAsStream("/seed-viewer/template.html")) {
            assertNotNull(in, "seed-viewer/template.html missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyPlaceholderIsWired() throws Exception {
        Set<String> found = new TreeSet<>();
        Matcher m = Pattern.compile("\\{\\{([A-Z_]+)}}").matcher(template());
        while (m.find()) {
            found.add(m.group(1));
        }
        assertEquals(new TreeSet<>(SUBSTITUTED), found,
                "template placeholders and ViewerPage.render must name the same set");
    }

    /**
     * The score filter and the flag are separate controls answering the same
     * question at different bars, so the select must offer the flag's bar.
     */
    @Test
    void theScoreSelectOffersTheFlaggedThreshold() throws Exception {
        String html = template();
        assertTrue(html.contains("id=\"f-maxscore\""), "the max-score select is missing");
        long threshold = (long) RollPipeline.SCORE_THRESHOLD;
        assertTrue(html.contains("<option value=\"" + threshold + "\">"),
                "no option matches SCORE_THRESHOLD (" + threshold + ")");
    }
}
