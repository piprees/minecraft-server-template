package com.customdimensions.compiler.datapack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.definition.GameRuleValues;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameRulesCompilerTest {

    private static GameRuleValues rules(String json) {
        return GameRuleValues.parse(JsonParser.parseString(json), "t: gameRules");
    }

    private static String refusal(String json) {
        return assertThrows(IllegalArgumentException.class,
                () -> GameRulesCompiler.check("trial", rules(json), Fixtures.vanilla())).getMessage();
    }

    @Test
    void vanillaRulesAreTypedFromTheCommandReport() {
        var known = Fixtures.vanilla().gameRules();
        assertEquals(59, known.size(), known.keySet().toString());
        assertEquals(new VanillaData.GameRuleType(true, null, null), known.get("minecraft:keep_inventory"));
        VanillaData.GameRuleType tick = known.get("minecraft:random_tick_speed");
        assertFalse(tick.bool());
        assertEquals(0, tick.min());
        assertTrue(known.keySet().stream().allMatch(id -> id.startsWith("minecraft:")));
    }

    @Test
    void acceptsRulesOfTheirTypeAndModRules() {
        assertDoesNotThrow(() -> GameRulesCompiler.check("trial",
                rules("{\"keep_inventory\": true, \"random_tick_speed\": 0, \"spawn_mobs\": false, \"somemod:frost\": 3}"),
                Fixtures.vanilla()));
    }

    @Test
    void refusesAnUnknownRuleNamingTheDefinitionAndTheRule() {
        String m = refusal("{\"keep_inventroy\": true}");
        assertTrue(m.startsWith("trial: gameRules.minecraft:keep_inventroy is not a game rule"), m);
    }

    @Test
    void refusesAValueOfTheWrongType() {
        assertTrue(refusal("{\"keep_inventory\": 1}").contains("takes true or false"));
        assertTrue(refusal("{\"random_tick_speed\": true}").contains("takes a whole number"));
    }

    @Test
    void refusesAValueOutsideTheRulesBounds() {
        String m = refusal("{\"random_tick_speed\": -1}");
        assertTrue(m.contains("outside 0.."), m);
    }

    @Test
    void refusesAdvanceTimeAndSaysWhatToWriteInstead() {
        assertEquals(java.util.Set.of("minecraft:advance_time"), GameRuleValues.SERVER_WIDE);
        String m = refusal("{\"advance_time\": false}");
        assertTrue(m.startsWith("trial: gameRules.minecraft:advance_time is server-wide"), m);
        assertTrue(m.contains("atmosphere.fixedTime") && m.contains("atmosphere.dayLength") && m.contains("/gamerule"), m);
    }

    @Test
    void acceptsTheRulesAClientHoldsAndAdvanceWeather() {
        assertDoesNotThrow(() -> GameRulesCompiler.check("trial", rules("{\"advance_weather\": false, \"immediate_respawn\": true,"
                + " \"limited_crafting\": true, \"reduced_debug_info\": true}"), Fixtures.vanilla()));
    }

    @Test
    void aDefinitionsBadRuleFailsItsCompile() {
        String m = assertThrows(IllegalArgumentException.class, () -> Fixtures.compile("trial",
                "\"generator\": {\"type\": \"void\"}, \"gameRules\": {\"pvp\": 2}")).getMessage();
        assertTrue(m.startsWith("trial: gameRules.minecraft:pvp"), m);
        var ok = Fixtures.compile("trial", "\"generator\": {\"type\": \"void\"}, \"gameRules\": {\"pvp\": false}");
        assertTrue(ok.report().entries().get("trial").unsupported().stream().anyMatch(u -> u.startsWith("`gameRules`")));
    }

    @Test
    void theDefaultsAreCheckedWithTheSettings(@TempDir Path dir) throws IOException {
        Path definitions = dir.resolve("definitions");
        Files.createDirectories(definitions);
        try (Stream<Path> files = Files.list(Fixtures.definitions())) {
            for (Path f : files.filter(f -> f.getFileName().toString().equals("settings.json")).toList()) {
                String text = Files.readString(f);
                Files.writeString(definitions.resolve("settings.json"),
                        text.replaceFirst("\"defaults\"\\s*:\\s*\\{", "\"defaults\": {\"gameRules\": {\"advance_time\": false},"));
            }
        }
        String m = assertThrows(IllegalArgumentException.class,
                () -> DatapackCompiler.compile(definitions, Fixtures.vanilla(), VersionTable.V26_3)).getMessage();
        assertTrue(m.startsWith("settings.json defaults: gameRules.minecraft:advance_time is server-wide"), m);
    }
}
