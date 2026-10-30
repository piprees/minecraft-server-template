package com.customdimensions.core.definition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionReaderTest {

    private static Definition read(String text) {
        return DefinitionReader.read(text.replace('\'', '"'), "the_x.json");
    }

    private static DefinitionFormatException refused(String text) {
        return assertThrows(DefinitionFormatException.class, () -> read(text));
    }

    @Test
    void aFileWithoutSchemaVersionIsRefusedNamingTheConverter() {
        DefinitionFormatException e = refused("{'type': 'multi_biome', 'seed': 1}");
        assertTrue(e.getMessage().contains("the_x.json"), e.getMessage());
        assertTrue(e.getMessage().contains(":compiler:convertDefinitions"), e.getMessage());
    }

    @Test
    void anotherSchemaVersionIsRefused() {
        assertTrue(refused("{'schemaVersion': 1, 'generator': {'type': 'void'}}").getMessage()
                .contains(":compiler:convertDefinitions"));
        refused("{'schemaVersion': '2', 'generator': {'type': 'void'}}");
    }

    @Test
    void readsAFullDefinition() {
        Definition d = read("{'schemaVersion': 2, 'description': 'd', 'seed': 7826077224469180302,"
                + " 'spawn': [0, 79, -408], 'generator': {'type': 'noise', 'preset': 'end', 'endIsland': false},"
                + " 'terrain': {'base': null}, 'biomes': ['minecraft:plains', {'id': 'minecraft:desert',"
                + " 'climate': {'temperature': [0.5, 1.0]}}], 'liquids': {'seaLevel': -60, 'defaultFluid': 'minecraft:lava'},"
                + " 'materials': {'defaultBlock': 'minecraft:netherrack'}, 'zones': [{'shape': 'circle'}],"
                + " 'dimensionType': {'hasCeiling': false, 'monsterSpawnLightLevel': 0},"
                + " 'atmosphere': {'fogColor': '#000000', 'ambientLight': 0.1, 'fixedTime': 18000},"
                + " 'rules': {'piglinsZombify': false}, 'borders': {'player': 4096, 'generation': 2048},"
                + " 'difficulty': {'mobMultiplier': 0.8}, 'structures': {'density': 'dense'},"
                + " 'portals': [{'frameBlock': 'minecraft:glowstone', 'preview': false}], 'exitPortal': {},"
                + " 'exits': {}, 'exitShrines': {}, 'roll': {'mood': 'scenic'}}");
        assertEquals(7826077224469180302L, d.seed().value());
        assertFalse(d.seed().fromEnv());
        assertEquals(-408, d.spawn().z());
        assertEquals(new Generator("noise", "end", null, null, null, null, false), d.generator());
        assertTrue(d.terrain().get("base").isJsonNull());
        assertEquals(2, d.biomes().size());
        assertNull(d.biomes().get(0).climate());
        assertEquals("minecraft:desert", d.biomes().get(1).id());
        assertEquals(new Liquids(-60, "minecraft:lava"), d.liquids());
        assertEquals(1, d.zones().size());
        assertEquals(Boolean.FALSE, d.dimensionType().hasCeiling());
        assertEquals(18000L, d.atmosphere().fixedTime());
        assertEquals(Boolean.FALSE, d.rules().piglinsZombify());
        assertEquals(new Borders(4096, 2048), d.borders());
        assertEquals("dense", d.structures().get("density").getAsString());
        assertFalse(d.portals().get(0).get("preview").getAsBoolean());
        assertEquals("scenic", d.roll().get("mood").getAsString());
    }

    @Test
    void liquidsCarryLavaLevelAndAquifersAndOresStayJson() {
        Definition d = read("{'schemaVersion': 2, 'generator': {'type': 'noise', 'preset': 'overworld'},"
                + " 'liquids': {'seaLevel': 40, 'lavaLevel': -30, 'defaultFluid': 'minecraft:water', 'aquifers': 'dry'},"
                + " 'ores': {'veins': false, 'remove': ['minecraft:ore_diamond']}}");
        assertEquals(new Liquids(40, -30, "minecraft:water", "dry"), d.liquids());
        assertFalse(d.ores().get("veins").getAsBoolean());
        assertNull(read("{'schemaVersion': 2, 'generator': {'type': 'void'}}").ores());
        String message = refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'liquids': {'lavaLevl': 1}}").getMessage();
        assertTrue(message.contains("lavaLevl"), message);
    }

    @Test
    void absentListsAreEmptyAndAbsentLayersNull() {
        Definition d = read("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'seed': 'env'}");
        assertTrue(d.seed().fromEnv());
        assertEquals(List.of(), d.biomes());
        assertEquals(List.of(), d.portals());
        assertEquals(List.of(), d.zones());
        assertNull(d.liquids());
        assertNull(d.spawn());
    }

    @Test
    void generatorIsRequired() {
        assertTrue(refused("{'schemaVersion': 2}").getMessage().contains("generator"));
    }

    @Test
    void unknownFieldsAreRefusedAtTheTopAndInClosedObjects() {
        assertTrue(refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'seedRoll': {}}")
                .getMessage().contains("seedRoll"));
        assertTrue(refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'liquids': {'aquifers': true}}")
                .getMessage().contains("liquids.aquifers"));
        refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'rules': {'bedWorks': true}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'void', 'scale': 1}}");
    }

    @Test
    void openLayersAcceptFieldsLaterPackagesAdd() {
        Definition d = read("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'structures': {'bridged': true},"
                + " 'terrain': {'base': 'adventure:continents', 'jitter': 4}, 'roll': {'newCriterion': 1}}");
        assertTrue(d.structures().get("bridged").getAsBoolean());
    }

    @Test
    void generatorFieldsMustSuitTheType() {
        refused("{'schemaVersion': 2, 'generator': {'type': 'noise'}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'noise', 'preset': 'tall'}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'nether'}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'void', 'preset': 'end'}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'clone'}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'void', 'of': 'minecraft:overworld'}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'noise', 'preset': 'overworld', 'cellScale': 2}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'checkerboard', 'layers': []}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'noise', 'preset': 'floating_islands', 'endIsland': false}}");
        refused("{'schemaVersion': 2, 'generator': {'type': 'void', 'biome': 'minecraft:plains'}}");
        assertEquals("minecraft:paradise", read("{'schemaVersion': 2, 'generator': {'type': 'clone',"
                + " 'of': 'minecraft:paradise'}}").generator().of());
        assertEquals(3, read("{'schemaVersion': 2, 'generator': {'type': 'checkerboard', 'cellScale': 3}}")
                .generator().cellScale());
    }

    @Test
    void wrongValueTypesNameTheField() {
        assertTrue(refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'spawn': [0, 64]}")
                .getMessage().contains("spawn"));
        assertTrue(refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'seed': 'random'}")
                .getMessage().contains("seed"));
        refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'portals': {'frameBlock': 'minecraft:stone'}}");
        assertTrue(refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'rules': {'natural': 'no'}}")
                .getMessage().contains("rules.natural"));
    }

    @Test
    void settingsNeedTheSchemaVersionToo() {
        assertThrows(DefinitionFormatException.class,
                () -> DefinitionReader.readSettings("{\"namespace\": \"adventure\"}", "settings.json"));
        DefinitionSettings settings = DefinitionReader.readSettings(
                "{\"schemaVersion\": 2, \"namespace\": \"adventure\", \"idleUnloadMinutes\": 5}", "settings.json");
        assertEquals("adventure", settings.namespace());
        assertEquals(5, settings.idleUnloadMinutes());
        assertTrue(settings.defaults().isEmpty());
    }

    @Test
    void readsDeathsAndWins() {
        Definition d = read("{'schemaVersion': 2, 'generator': {'type': 'void'},"
                + " 'deaths': {'inventory': 'keep', 'experience': 'drop'},"
                + " 'wins': [{'id': 'summit', 'title': 'Summit', 'trigger': {'type': 'reach', 'pos': [1, 2, 3], 'radius': 5},"
                + " 'exit': {'dimension': 'adventure:the_canvas', 'arrival': 'spawn'}},"
                + " {'id': 'cull', 'title': 'Cull', 'frame': 'challenge', 'trigger': {'type': 'kill', 'entity': '#minecraft:undead', 'count': 4}},"
                + " {'id': 'endure', 'title': 'Endure', 'trigger': {'type': 'survive', 'ticks': 1200}}]}");
        assertEquals(Deaths.Rule.KEEP, d.deaths().inventory());
        assertEquals(Deaths.Rule.DROP, d.deaths().experience());
        assertEquals(3, d.wins().size());
        Win summit = d.wins().get(0);
        assertEquals(Win.Type.REACH, summit.trigger().type());
        assertEquals(new com.customdimensions.core.BlockPos(1, 2, 3), summit.trigger().pos());
        assertEquals(5, summit.trigger().radius());
        assertTrue(summit.exit().isJsonObject());
        assertEquals("#minecraft:undead", d.wins().get(1).trigger().target());
        assertEquals(4, d.wins().get(1).trigger().count());
        assertEquals(1200, d.wins().get(2).trigger().ticks());
        assertEquals(java.util.List.of(), read("{'schemaVersion': 2, 'generator': {'type': 'void'}}").wins());
    }

    @Test
    void refusesMalformedDeathsAndWins() {
        assertTrue(refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'deaths': {'inventory': 'maybe'}}")
                .getMessage().contains("deaths.inventory"));
        refused("{'schemaVersion': 2, 'generator': {'type': 'void'}, 'deaths': {'armour': 'keep'}}");
        String head = "{'schemaVersion': 2, 'generator': {'type': 'void'}, 'wins': [";
        assertTrue(refused(head + "{'id': 'a', 'title': 'A', 'trigger': {'type': 'fly'}}]}").getMessage().contains("wins[0].trigger.type"));
        refused(head + "{'id': 'a', 'title': 'A', 'trigger': {'type': 'reach'}}]}");
        refused(head + "{'id': 'a', 'title': 'A', 'trigger': {'type': 'kill'}}]}");
        refused(head + "{'id': 'a', 'title': 'A', 'trigger': {'type': 'survive'}}]}");
        refused(head + "{'id': 'a', 'title': 'A', 'trigger': {'type': 'enter'}}, {'id': 'a', 'title': 'B', 'trigger': {'type': 'enter'}}]}");
        refused(head + "{'id': 'Bad', 'title': 'A', 'trigger': {'type': 'enter'}}]}");
        refused(head + "{'id': 'a', 'trigger': {'type': 'enter'}}]}");
        refused(head + "{'id': 'a', 'title': 'A', 'frame': 'gold', 'trigger': {'type': 'enter'}}]}");
        refused(head + "{'id': 'a', 'title': 'A', 'exit': 'nowhere', 'trigger': {'type': 'enter'}}]}");
        refused(head + "{'id': 'a', 'title': 'A', 'trigger': {'type': 'obtain', 'item': 'minecraft:diamond', 'count': 0}}]}");
    }
}
