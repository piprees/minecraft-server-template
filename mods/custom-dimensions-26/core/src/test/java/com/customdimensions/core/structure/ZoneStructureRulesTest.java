package com.customdimensions.core.structure;

import com.customdimensions.core.zone.ZoneMask;
import com.customdimensions.core.zone.ZoneShape;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneStructureRulesTest {

    /** The test double for W2b's zone query: zone 0 is x >= 1000, zone 1 is everything. */
    private static final ZoneStructureRules.ZoneQuery HALF = (zone, x, z) -> zone == 0 ? (x >= 1000 ? 1 : 0) : 1;

    private static List<JsonObject> zones(String... json) {
        return java.util.Arrays.stream(json).map(j -> JsonParser.parseString(j).getAsJsonObject()).toList();
    }

    @Test
    void aZoneShunOnlyAppliesInsideItsZone() {
        ZoneStructureRules rules = ZoneStructureRules.read("t", zones("{\"structures\": {\"shuns\": {\"village\": {}}}}", "{}"));
        assertEquals(1, rules.rules().size());
        assertTrue(rules.shunned("#minecraft:village"::equals, 2000, 0, HALF));
        assertFalse(rules.shunned("#minecraft:village"::equals, 0, 0, HALF));
        assertFalse(rules.shunned("minecraft:igloo"::equals, 2000, 0, HALF));
    }

    @Test
    void aZoneWantAcceptsOnlyItsZoneAndAnUnscopedWantAcceptsAnywhere() {
        ZoneStructureRules rules = ZoneStructureRules.read("t", zones("{\"structures\": {\"wants\": [\"minecraft:igloo\"]}}"));
        assertTrue(rules.wantAccepts("minecraft:igloo"::equals, 1500, 0, HALF));
        assertFalse(rules.wantAccepts("minecraft:igloo"::equals, 10, 0, HALF));
        assertTrue(rules.wantAccepts("minecraft:mansion"::equals, 10, 0, HALF));
    }

    @Test
    void theBlendWeightDecidesInside() {
        ZoneStructureRules rules = ZoneStructureRules.read("t", zones("{\"structures\": {\"shuns\": [\"minecraft:igloo\"]}}"));
        ZoneStructureRules.ZoneQuery masks = ZoneStructureRules.ZoneQuery.of(List.of(
                new ZoneMask(0, 0, new ZoneShape.Circle(100), 40)));
        assertTrue(rules.shunned("minecraft:igloo"::equals, 0, 0, masks));
        assertFalse(rules.shunned("minecraft:igloo"::equals, 500, 0, masks));
        assertFalse(rules.shunned("minecraft:igloo"::equals, 0, 0, ZoneStructureRules.ZoneQuery.of(List.of())));
    }

    @Test
    void roundTripsAndRefusesBadBlocks() {
        ZoneStructureRules rules = ZoneStructureRules.read("t", zones("{}", "{\"structures\": {\"wants\": [\"bastion\"], \"shuns\": {\"monument\": {}}}}"));
        assertEquals(rules, ZoneStructureRules.fromJson(rules.toJson()));
        assertEquals(1, rules.rules().getFirst().zone());
        assertEquals(List.of("minecraft:bastion_remnant"), rules.rules().getFirst().wants());
        assertThrows(IllegalArgumentException.class, () -> ZoneStructureRules.read("t", zones("{\"structures\": 3}")));
        assertThrows(IllegalArgumentException.class, () -> ZoneStructureRules.read("t", zones("{\"structures\": {\"shuns\": [\"zzz_none\"]}}")));
        assertTrue(ZoneStructureRules.read("t", List.of()).rules().isEmpty());
    }
}
