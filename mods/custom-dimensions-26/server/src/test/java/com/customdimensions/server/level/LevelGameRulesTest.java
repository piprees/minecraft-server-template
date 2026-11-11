package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.compiler.datapack.VanillaData;
import com.customdimensions.compiler.datapack.VanillaSource;
import com.customdimensions.core.definition.GameRuleValues;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LevelGameRulesTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static GameRuleValues values(String json) {
        return GameRuleValues.parse(JsonParser.parseString(json), "t");
    }

    private static GameRules store() {
        return new GameRules(FeatureFlags.DEFAULT_FLAGS);
    }

    @Test
    void aViewAnswersItsOwnValuesAndTheStoreForTheRest() {
        GameRules store = store();
        List<String> problems = new ArrayList<>();
        Map<GameRule<?>, Object> own = LevelGameRules.typed("trial", values("{\"keep_inventory\": true, \"random_tick_speed\": 0}"), problems);
        assertEquals(List.of(), problems);
        LevelGameRules.View view = new LevelGameRules.View(store, own);
        assertTrue(view.get(GameRules.KEEP_INVENTORY));
        assertEquals(0, view.get(GameRules.RANDOM_TICK_SPEED));
        assertFalse(store.get(GameRules.KEEP_INVENTORY));
        assertEquals(store.get(GameRules.MOB_GRIEFING), view.get(GameRules.MOB_GRIEFING));
        assertEquals("true", view.getAsString(GameRules.KEEP_INVENTORY));
        assertTrue(view.holds(GameRules.KEEP_INVENTORY) && !view.holds(GameRules.MOB_GRIEFING));
    }

    @Test
    void aWriteThroughAViewIsServerWideAndTheOwnValueSurvivesIt() {
        GameRules store = store();
        LevelGameRules.View view = new LevelGameRules.View(store,
                LevelGameRules.typed("trial", values("{\"keep_inventory\": true}"), new ArrayList<>()));
        view.set(GameRules.KEEP_INVENTORY, false, null);
        view.set(GameRules.MOB_GRIEFING, false, null);
        assertFalse(store.get(GameRules.KEEP_INVENTORY));
        assertTrue(view.get(GameRules.KEEP_INVENTORY));
        assertFalse(store.get(GameRules.MOB_GRIEFING));
        assertFalse(view.get(GameRules.MOB_GRIEFING));
        assertEquals(store.availableRules().count(), view.availableRules().count());
    }

    @Test
    void aCopyCarriesTheOwnValuesOverTheStore() {
        GameRules store = store();
        LevelGameRules.View view = new LevelGameRules.View(store,
                LevelGameRules.typed("trial", values("{\"keep_inventory\": true}"), new ArrayList<>()));
        GameRules copy = view.copy(FeatureFlags.DEFAULT_FLAGS);
        assertTrue(copy.get(GameRules.KEEP_INVENTORY));
        assertFalse(store.get(GameRules.KEEP_INVENTORY));
    }

    @Test
    void theStartCheckRefusesUnknownServerWideAndMistypedRules() {
        List<String> problems = new ArrayList<>();
        Map<GameRule<?>, Object> own = LevelGameRules.typed("trial",
                values("{\"somemod:frost\": true, \"advance_time\": false, \"random_tick_speed\": -4, \"pvp\": 3, \"spawn_mobs\": false}"), problems);
        assertEquals(4, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("random_tick_speed")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("somemod:frost is not a game rule of this server")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("advance_time is server-wide")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("pvp")), problems.toString());
        assertEquals(Map.of(GameRules.SPAWN_MOBS, false), own);
    }

    @Test
    void aClientIsSentItsLevelsValuesOfTheThreeRulesItHolds() {
        GameRules store = store();
        store.set(GameRules.LIMITED_CRAFTING, true, null);
        LevelGameRules.View view = new LevelGameRules.View(store, LevelGameRules.typed("trial",
                values("{\"immediate_respawn\": true, \"reduced_debug_info\": true}"), new ArrayList<>()));
        assertEquals(new LevelClientRules.Values(true, true, true), LevelClientRules.Values.of(view));
        assertEquals(new LevelClientRules.Values(false, true, false), LevelClientRules.Values.of(store));
        for (GameRule<?> held : List.of(GameRules.IMMEDIATE_RESPAWN, GameRules.LIMITED_CRAFTING, GameRules.REDUCED_DEBUG_INFO)) {
            assertTrue(LevelClientRules.heldByClient(held), held.id());
        }
        assertFalse(LevelClientRules.heldByClient(GameRules.KEEP_INVENTORY));
        assertEquals(22, LevelClientRules.REDUCED_DEBUG_ON);
        assertEquals(23, LevelClientRules.REDUCED_DEBUG_OFF);
    }

    @Test
    void aLevelHoldingItsOwnAdvanceWeatherHasWeatherOfItsOwnStartingFromTheServers() {
        assertTrue(LevelGameRules.ownsWeather(LevelGameRules.typed("trial", values("{\"advance_weather\": false}"), new ArrayList<>())));
        assertTrue(LevelGameRules.ownsWeather(LevelGameRules.typed("trial", values("{\"advance_weather\": true}"), new ArrayList<>())));
        assertFalse(LevelGameRules.ownsWeather(LevelGameRules.typed("trial", values("{\"keep_inventory\": true}"), new ArrayList<>())));
        assertFalse(LevelGameRules.ownsWeather(null));
        var shared = new net.minecraft.world.level.saveddata.WeatherData(0, 1200, 300, true, false);
        var own = LevelGameRules.copy(shared);
        own.setRaining(false);
        assertTrue(shared.isRaining());
        assertEquals(List.of(0, 1200, 300), List.of(own.getClearWeatherTime(), own.getRainTime(), own.getThunderTime()));
    }

    @Test
    void noOwnValuesMeansNoView() {
        assertNull(LevelGameRules.viewOrNull(ResourceKey.create(Registries.DIMENSION, Identifier.parse("adventure:nowhere")), store()));
    }

    @Test
    void theRunningGamesRuleTypesMatchTheDataGeneratorsReport() {
        Path extracted = Path.of(System.getProperty("customdimensions.vanilla"));
        VanillaSource dir = VanillaSource.directory(extracted);
        String runtime = RuntimeVanilla.gameRuleCommands();
        VanillaSource live = new VanillaSource() {
            @Override
            public Optional<String> read(String path) {
                return path.equals(VanillaData.COMMANDS_REPORT) ? Optional.of(runtime) : dir.read(path);
            }

            @Override
            public List<String> list(String folder) {
                return dir.list(folder);
            }

            @Override
            public String describe() {
                return "runtime rules over " + dir.describe();
            }
        };
        Map<String, VanillaData.GameRuleType> expected = new VanillaData(dir).gameRules();
        assertEquals(expected, new VanillaData(live).gameRules());
        assertSame(Boolean.class, GameRules.KEEP_INVENTORY.valueClass());
    }
}
