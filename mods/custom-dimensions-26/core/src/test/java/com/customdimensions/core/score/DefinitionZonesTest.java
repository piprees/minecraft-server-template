package com.customdimensions.core.score;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefinitionZonesTest {

    private static Definition def(String zones) {
        return DefinitionReader.read(("{'schemaVersion': 2, 'generator': {'type': 'noise', 'preset': 'overworld'}, "
                + "'spawn': [100, 64, 0], 'zones': " + zones + "}").replace('\'', '"'), "test.json");
    }

    @Test
    void aCircleWithBiomesIsAZoneInsideItsRadiusLessHalfItsBlend() {
        DefinitionZones z = DefinitionZones.of(def("[{'centre': [0, 0], 'radius': 100, 'blend': 40, 'biomes': ['minecraft:desert']}]"));
        assertEquals(List.of(new ZoneMap.Zone("zone0", Set.of("minecraft:desert"))), z.zones());
        assertEquals(0, z.zoneAt(79, 0));
        assertEquals(-1, z.zoneAt(81, 0));
    }

    @Test
    void aCircleWithoutACentreSitsOnSpawnAndLaterZonesWin() {
        DefinitionZones z = DefinitionZones.of(def("[{'id': 'big', 'radius': 200, 'biomes': ['minecraft:plains']},"
                + " {'id': 'small', 'radius': 50, 'biomes': [{'id': 'minecraft:desert'}]}]"));
        assertEquals(1, z.zoneAt(100, 0));
        assertEquals(0, z.zoneAt(200, 0));
        assertEquals(-1, z.zoneAt(-150, 0));
    }

    @Test
    void zonesWithoutBiomesAreLeftOutAndOtherShapesAreReported() {
        DefinitionZones z = DefinitionZones.of(def("[{'radius': 100}, {'id': 'r', 'shape': 'ring', 'inner': 10, 'outer': 20,"
                + " 'biomes': ['minecraft:desert']}]"));
        assertEquals(List.of(), z.zones());
        assertEquals(List.of("r (ring)"), z.skipped());
        assertEquals(0, DefinitionZones.of(null).zones().size());
    }
}
