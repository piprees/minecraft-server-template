package com.customdimensions.server.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.customdimensions.core.definition.DefinitionReader;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** The dossier's {@code borders}: the definition's player and generation borders beside the radius surveyed. */
class SurveyBordersTest {

    private static final String BASE = "{\"schemaVersion\": 2, \"seed\": 1, \"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";

    @Test
    void bothBordersAndTheSurveyedRadius() {
        JsonObject o = SurveyJob.borders(DefinitionReader.read(BASE + ", \"borders\": {\"player\": 683, \"generation\": 1024}}", "t.json"), 1024);
        assertEquals(683, o.get("player").getAsInt());
        assertEquals(1024, o.get("generation").getAsInt());
        assertEquals(1024, o.get("surveyed").getAsInt());
    }

    @Test
    void anAbsentBorderIsLeftOut() {
        JsonObject o = SurveyJob.borders(DefinitionReader.read(BASE + "}", "t.json"), 1024);
        assertFalse(o.has("player"));
        assertFalse(o.has("generation"));
        assertEquals(1024, o.get("surveyed").getAsInt());
        assertEquals(512, SurveyJob.borders(null, 512).get("surveyed").getAsInt());
    }
}
