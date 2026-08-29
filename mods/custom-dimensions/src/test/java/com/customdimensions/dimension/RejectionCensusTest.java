package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The empty-site census: one record per site, and earlier sessions' records
 * carried through a rewrite rather than read back and spliced per event.
 */
class RejectionCensusTest {

    private static RejectionCensus.EmptySite site(int cx, int cz) {
        return new RejectionCensus.EmptySite("settlements", "minecraft:village_plains",
                cx, cz, 8, 7);
    }

    @Test
    void rendersOneEntryPerSiteWithItsCandidateCounts() {
        String json = RejectionCensus.render("minecraft:overworld", "",
                List.of(site(4, 9), site(-2, 3)));
        assertTrue(json.contains("\"emptySites\""), json);
        assertTrue(json.contains("\"chunkX\": 4, \"chunkZ\": 9, "
                + "\"candidates\": 8, \"biomeRejections\": 7"), json);
        assertEquals(2, json.split("\"structure\"", -1).length - 1,
                "one entry per site, not one per candidate");
    }

    @Test
    void carriesEarlierSessionsEntriesVerbatim() {
        String earlier = "{\"group\": \"deco\", \"structure\": \"x\", \"chunkX\": 1, "
                + "\"chunkZ\": 1, \"candidates\": 3, \"biomeRejections\": 3}";
        String json = RejectionCensus.render("minecraft:overworld", earlier, List.of(site(4, 9)));
        assertTrue(json.contains(earlier), "a rewrite must not drop what a previous boot proved");
        assertEquals(2, json.split("\"structure\"", -1).length - 1);
    }

    @Test
    void readsBackWhatItWrote() {
        String first = RejectionCensus.render("minecraft:overworld", "", List.of(site(4, 9)));
        int open = first.indexOf('[');
        int close = first.lastIndexOf(']');
        String carried = first.substring(open + 1, close).trim();
        String second = RejectionCensus.render("minecraft:overworld", carried, List.of(site(-2, 3)));
        assertEquals(2, second.split("\"structure\"", -1).length - 1,
                "a second flush must hold both boots' entries exactly once");
    }

    @Test
    void anEmptyFileCarriesNothing() {
        String json = RejectionCensus.render("minecraft:overworld", "", List.of());
        assertTrue(json.contains("\"emptySites\": ["), json);
        assertEquals(0, json.split("\"structure\"", -1).length - 1);
    }
}
