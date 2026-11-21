package com.customdimensions.core.structure;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Site filters on synthetic terrain: a cone (summit), a ridge (spur), a bowl (valley), a lake, a step (cliff). */
class SiteFilterTest {

    /** Terrain from a height function, with water where {@code water} is below {@code waterLevel}, biomes by a name. */
    record Synthetic(IntBinaryOperator h, int waterLevel, String biome, boolean roof) implements SiteFilter.Terrain {
        Synthetic(IntBinaryOperator h) {
            this(h, Integer.MIN_VALUE, "minecraft:plains", false);
        }

        public int height(int x, int z) {
            return Math.max(h.applyAsInt(x, z), waterLevel);
        }

        public int floor(int x, int z) {
            return h.applyAsInt(x, z);
        }

        public boolean inBiomes(int x, int y, int z, List<String> biomes) {
            return biomes.contains(biome) || biomes.contains("#minecraft:is_forest") && biome.equals("minecraft:forest");
        }

        public boolean overhang(int x, int z, int y) {
            return roof;
        }
    }

    static SiteFilter filter(String json) {
        return SiteFilter.read("t", JsonParser.parseString(json).getAsJsonObject());
    }

    static final SiteFilter.Terrain FLAT = new Synthetic((x, z) -> 64);
    static final SiteFilter.Terrain CONE = new Synthetic((x, z) -> 120 - (int) Math.hypot(x, z) / 2);
    static final SiteFilter.Terrain BOWL = new Synthetic((x, z) -> 60 + (int) Math.hypot(x, z) / 2);
    /** A ridge along +x falling away to the sides and to -x: its end at the origin is a spur. */
    static final SiteFilter.Terrain RIDGE = new Synthetic((x, z) -> 100 - Math.abs(z) / 2 - (x < 0 ? -x / 2 : 0));
    /** Land rising from a lake: below 62 at x under 40. */
    static final SiteFilter.Terrain LAKE = new Synthetic((x, z) -> x < 40 ? 50 : 66, 62, "minecraft:plains", false);
    /** A 20-block step at x = 30. */
    static final SiteFilter.Terrain STEP = new Synthetic((x, z) -> x < 30 ? 64 : 84);

    @Test
    void anEmptyFilterAcceptsEverywhere() {
        assertTrue(SiteFilter.NONE.isEmpty());
        assertNull(SiteFilter.NONE.refusal(CONE, 0, 0));
    }

    @Test
    void slopeIsTheSpreadOverTheFootprint() {
        SiteFilter f = filter("{\"slope\": 4, \"radius\": 8}");
        assertNull(f.refusal(FLAT, 0, 0));
        String why = f.refusal(CONE, 40, 0);
        assertNotNull(why);
        assertTrue(why.startsWith("slope"), why);
    }

    @Test
    void heightWindowReadsTheSurface() {
        SiteFilter f = filter("{\"height\": {\"min\": 100}}");
        assertNull(f.refusal(CONE, 0, 0));
        assertNotNull(f.refusal(CONE, 60, 0));
        assertNotNull(filter("{\"height\": {\"max\": 63}}").refusal(FLAT, 0, 0));
    }

    @Test
    void summitSpurAndValley() {
        SiteFilter summit = filter("{\"relative\": \"summit\"}");
        assertNull(summit.refusal(CONE, 0, 0));
        assertNotNull(summit.refusal(CONE, 100, 0));
        assertNotNull(summit.refusal(FLAT, 0, 0));
        SiteFilter spur = filter("{\"relative\": {\"kind\": \"spur\", \"rise\": 8, \"reach\": 48}}");
        assertNull(spur.refusal(RIDGE, 0, 0));
        assertNotNull(spur.refusal(CONE, 0, 0), "a summit falls away on every side, so it is not a spur");
        SiteFilter valley = filter("{\"relative\": \"valley\"}");
        assertNull(valley.refusal(BOWL, 0, 0));
        assertNotNull(valley.refusal(CONE, 0, 0));
    }

    @Test
    void waterDistanceWindow() {
        SiteFilter near = filter("{\"water\": {\"max\": 24}}");
        assertNull(near.refusal(LAKE, 56, 0), "16 blocks from the shore");
        assertNotNull(near.refusal(LAKE, 120, 0));
        SiteFilter away = filter("{\"water\": {\"min\": 48}}");
        assertNotNull(away.refusal(LAKE, 56, 0));
        assertNull(away.refusal(LAKE, 120, 0));
        assertNotNull(filter("{\"water\": {\"kind\": \"river\", \"max\": 24}}").refusal(LAKE, 56, 0), "a lake in plains is not a river");
        assertEquals(0, SiteFilter.nearestWater(LAKE, 20, 0, new SiteFilter.Distance("shore", null, 64, 0)) % 8);
        assertTrue(SiteFilter.nearestWater(LAKE, 20, 0, new SiteFilter.Distance("shore", null, 64, 0)) >= 16,
                "from the water, the shore is the nearest land");
    }

    @Test
    void cliffDistanceFindsTheStep() {
        SiteFilter near = filter("{\"cliff\": {\"max\": 16}}");
        assertNull(near.refusal(STEP, 20, 0));
        assertNotNull(near.refusal(STEP, -40, 0));
        assertEquals(8, SiteFilter.nearestCliff(STEP, 20, 0, new SiteFilter.Distance("cliff", null, 32, 10)));
        assertNotNull(filter("{\"cliff\": {\"min\": 16}}").refusal(STEP, 20, 0));
    }

    @Test
    void shelterKinds() {
        assertNull(filter("{\"shelter\": \"hollow\"}").refusal(BOWL, 0, 0));
        assertNotNull(filter("{\"shelter\": \"hollow\"}").refusal(FLAT, 0, 0));
        assertNull(filter("{\"shelter\": \"lee\"}").refusal(STEP, 20, 0));
        assertNull(filter("{\"shelter\": [\"overhang\"]}").refusal(new Synthetic((x, z) -> 64, Integer.MIN_VALUE, "x", true), 0, 0));
        assertNull(filter("{\"shelter\": \"canopy\"}").refusal(new Synthetic((x, z) -> 64, Integer.MIN_VALUE, "minecraft:forest", false), 0, 0));
        assertNotNull(filter("{\"shelter\": \"canopy\"}").refusal(FLAT, 0, 0));
    }

    @Test
    void biomesAtTheSurface() {
        assertNull(filter("{\"biomes\": [\"minecraft:plains\"]}").refusal(FLAT, 0, 0));
        assertNotNull(filter("{\"biomes\": [\"minecraft:desert\"]}").refusal(FLAT, 0, 0));
    }

    @Test
    void badFieldsStopWithTheirPath() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> filter("{\"steep\": 3}")).getMessage().contains("t.steep"));
        assertThrows(IllegalArgumentException.class, () -> filter("{\"relative\": \"plateau\"}"));
        assertThrows(IllegalArgumentException.class, () -> filter("{\"water\": {}}"));
        assertThrows(IllegalArgumentException.class, () -> filter("{\"water\": {\"max\": 500}}"));
        assertThrows(IllegalArgumentException.class, () -> filter("{\"height\": {\"min\": 90, \"max\": 80}}"));
        assertThrows(IllegalArgumentException.class, () -> SiteFilter.readAll("s", JsonParser.parseString("{\"igloo\": {}}")));
    }

    @Test
    void roundTrips() {
        SiteFilter f = filter("{\"slope\": 4, \"height\": {\"min\": 70, \"max\": 200}, \"relative\": \"summit\", \"water\": {\"kind\": \"shore\", "
                + "\"max\": 64}, \"cliff\": {\"min\": 8, \"rise\": 12}, \"shelter\": [\"lee\", \"hollow\"], \"biomes\": [\"#minecraft:is_hill\"]}");
        assertEquals(f, SiteFilter.fromJson(f.toJson()));
    }

    @Test
    void aStartsFloorOutsideTheHeightWindowIsRefused() {
        SiteFilter f = filter("{\"height\": {\"min\": 36, \"max\": 100}}");
        assertNotNull(f.floorRefusal(36 - SiteFilter.FOUNDATION_DEPTH - 1));
        assertNull(f.floorRefusal(36 - SiteFilter.FOUNDATION_DEPTH), "its foundations may reach below the window");
        assertNotNull(f.floorRefusal(101));
        assertNull(f.floorRefusal(36));
        assertNull(filter("{\"slope\": 6}").floorRefusal(-40));
    }
}
