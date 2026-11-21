package com.customdimensions.core.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GameRuleValuesTest {

    private static GameRuleValues parse(String json) {
        return GameRuleValues.parse(JsonParser.parseString(json), "test.json: gameRules");
    }

    @Test
    void shortIdsAreMinecraftRulesAndNamespacedIdsStay() {
        GameRuleValues v = parse("{\"keep_inventory\": true, \"minecraft:random_tick_speed\": 0, \"somemod:frost\": false}");
        assertEquals(Map.of("minecraft:keep_inventory", true, "minecraft:random_tick_speed", 0, "somemod:frost", false), v.values());
    }

    @Test
    void absentIsNone() {
        assertSame(GameRuleValues.NONE, GameRuleValues.parse(null, "x"));
        assertTrue(GameRuleValues.fromDefaults(new JsonObject(), "settings.json").isEmpty());
    }

    @Test
    void refusesValuesThatAreNeitherBooleanNorWholeNumber() {
        assertThrows(DefinitionFormatException.class, () -> parse("{\"keep_inventory\": \"true\"}"));
        assertThrows(DefinitionFormatException.class, () -> parse("{\"random_tick_speed\": 1.5}"));
        assertThrows(DefinitionFormatException.class, () -> parse("{\"random_tick_speed\": 3000000000}"));
        assertThrows(DefinitionFormatException.class, () -> parse("{\"keep_inventory\": null}"));
        assertThrows(DefinitionFormatException.class, () -> parse("[true]"));
    }

    @Test
    void refusesABadIdAndTheSameRuleTwice() {
        assertThrows(DefinitionFormatException.class, () -> parse("{\"Keep Inventory\": true}"));
        DefinitionFormatException twice = assertThrows(DefinitionFormatException.class,
                () -> parse("{\"keep_inventory\": true, \"minecraft:keep_inventory\": false}"));
        assertTrue(twice.getMessage().contains("minecraft:keep_inventory twice"), twice.getMessage());
    }

    @Test
    void theDefinitionWinsOverTheDefaults() {
        GameRuleValues defaults = parse("{\"keep_inventory\": true, \"spawn_phantoms\": false}");
        GameRuleValues own = parse("{\"keep_inventory\": false}");
        assertEquals(Map.of("minecraft:keep_inventory", false, "minecraft:spawn_phantoms", false),
                GameRuleValues.resolve(defaults, own).values());
        assertEquals(defaults.values(), GameRuleValues.resolve(defaults, null).values());
        assertEquals(own.values(), GameRuleValues.resolve(null, own).values());
    }

    @Test
    void theReaderCarriesGameRulesAndAbsentIsNone() {
        Definition with = DefinitionReader.read("{\"schemaVersion\": 2, \"generator\": {\"type\": \"void\"},"
                + " \"gameRules\": {\"keep_inventory\": true}}", "a.json");
        assertEquals(Map.of("minecraft:keep_inventory", true), with.gameRules().values());
        Definition without = DefinitionReader.read("{\"schemaVersion\": 2, \"generator\": {\"type\": \"void\"}}", "b.json");
        assertSame(GameRuleValues.NONE, without.gameRules());
        DefinitionFormatException bad = assertThrows(DefinitionFormatException.class, () -> DefinitionReader.read(
                "{\"schemaVersion\": 2, \"generator\": {\"type\": \"void\"}, \"gameRules\": {\"keep_inventory\": 1.5}}", "c.json"));
        assertTrue(bad.getMessage().startsWith("c.json: gameRules.keep_inventory"), bad.getMessage());
    }

    @Test
    void theSchemaTakesBooleansAndWholeNumbersAndRefusesTheRest() throws IOException {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.readString(Path.of("../schema/definition.schema.json")), InputFormat.JSON);
        String head = "{\"schemaVersion\": 2, \"generator\": {\"type\": \"void\"}, \"gameRules\": ";
        assertEquals(List.of(), schema.validate(head + "{\"keep_inventory\": true, \"minecraft:random_tick_speed\": 0, \"mod:x\": 2}}",
                InputFormat.JSON).stream().map(Object::toString).toList());
        for (String bad : List.of("{\"keep_inventory\": \"yes\"}", "{\"random_tick_speed\": 1.5}", "{\"Keep\": true}", "[]")) {
            assertTrue(!schema.validate(head + bad + "}", InputFormat.JSON).isEmpty(), bad);
        }
    }

    @Test
    void writesBackAsJson() {
        assertEquals("{\"minecraft:keep_inventory\":true,\"minecraft:random_tick_speed\":0}",
                parse("{\"random_tick_speed\": 0, \"keep_inventory\": true}").toJson().toString());
    }
}
