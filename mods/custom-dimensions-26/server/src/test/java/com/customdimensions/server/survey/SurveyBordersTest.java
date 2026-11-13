package com.customdimensions.server.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The area a survey samples and the dossier's {@code borders}: the definition's player and generation borders beside it. */
class SurveyBordersTest {

    private static final String BASE = "{\"schemaVersion\": 2, \"seed\": 1, \"generator\": {\"type\": \"noise\", \"preset\": \"overworld\"}";
    private static final String PLACES = ", \"biomes\": [\"minecraft:plains\", \"minecraft:forest\", \"minecraft:meadow\"], \"biomeLayout\": "
            + "{\"mode\": \"regions\", \"places\": {\"groups\": {\"green\": [\"minecraft:forest\", \"minecraft:meadow\"]}}}";

    private static Definition read(String body) {
        return DefinitionReader.read(BASE + body + "}", "t.json");
    }

    @Test
    void bothBordersAndTheSurveyedArea() {
        JsonObject o = SurveyJob.borders(read(", \"borders\": {\"player\": 683, \"generation\": 1024}"), new SurveyJob.Area(1024, "player border"));
        assertEquals(683, o.get("player").getAsInt());
        assertEquals(1024, o.get("generation").getAsInt());
        assertEquals(1024, o.get("surveyed").getAsInt());
        assertEquals("player border", o.get("surveyedOver").getAsString());
        assertEquals(419, o.get("surveyedHectares").getAsLong(), "2048 blocks square");
    }

    @Test
    void anAbsentBorderIsLeftOut() {
        JsonObject o = SurveyJob.borders(read(""), new SurveyJob.Area(1024, "default"));
        assertFalse(o.has("player"));
        assertFalse(o.has("generation"));
        assertEquals(1024, o.get("surveyed").getAsInt());
        assertEquals(512, SurveyJob.borders(null, new SurveyJob.Area(512, "player border")).get("surveyed").getAsInt());
    }

    @Test
    void placesAreSurveyedWhereTheyAreLaid() {
        Definition sky = read(", \"borders\": {\"player\": 4096, \"generation\": 2048}" + PLACES);
        assertEquals(new SurveyJob.Area(2048, "places"), SurveyJob.area(sky, 4096), "the smaller border, which the compiler lays places over");
        assertEquals(new SurveyJob.Area(4096, "player border"), SurveyJob.area(sky, 4096, false), "the census keeps the player border");
        Definition plain = read(", \"borders\": {\"player\": 4096, \"generation\": 2048}");
        assertEquals(new SurveyJob.Area(4096, "player border"), SurveyJob.area(plain, 4096), "without places, the player border");
        assertEquals(new SurveyJob.Area(2048, "generation border"), SurveyJob.area(plain, null));
        assertEquals(new SurveyJob.Area(1024, "default"), SurveyJob.area(read(""), null));
        assertEquals(new SurveyJob.Area(1024, "places"), SurveyJob.area(read(PLACES), null), "places over no border take the compiler's default");
        assertEquals(new SurveyJob.Area(4096, "player border"), SurveyJob.area(plain, 8192), "held to 4096");
    }

    @Test
    void groupsAreReadFromThePlaces() {
        assertEquals(List.of("minecraft:forest", "minecraft:meadow"), SurveyJob.groups(read(PLACES).biomeLayout()).get("green"));
        assertTrue(SurveyJob.groups(read(", \"biomes\": [\"minecraft:plains\"], \"biomeLayout\": {\"mode\": \"regions\"}").biomeLayout()).isEmpty());
    }
}
