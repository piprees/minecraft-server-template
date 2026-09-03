package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.customdimensions.client.dev.PlayerFacts.Flag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player record a test reports against. Every field is a fact or an
 * explicit absence: an empty hotbar slot is present and null, never missing,
 * so {@code .hotbar[3]} answers the same way whether the slot is empty or the
 * field was never written.
 *
 * <p>Read back through {@link JsonReader}, so a field that is right inside
 * output {@code jq} cannot parse still fails.
 */
class PlayerFactsTest {

    private static final PlayerFacts.Item DIAMOND =
            new PlayerFacts.Item("minecraft:diamond", 64, null, null);
    private static final PlayerFacts.Item PICKAXE =
            new PlayerFacts.Item("minecraft:diamond_pickaxe", 1, 12, 1561);

    private static Map<String, Object> read(PlayerFacts facts) {
        return JsonReader.object(facts.json());
    }

    /**
     * Deliberately NOT padded to nine. Padding here would make every
     * nine-slot assertion a statement about this helper rather than about the
     * code under test.
     */
    private static List<PlayerFacts.Item> hotbar(PlayerFacts.Item... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static PlayerFacts facts() {
        return new PlayerFacts(
                "minecraft:overworld",
                211.705, 130, 327.715,
                211, 130, 327,
                new PlayerFacts.Rotation(146.7, 6.6, 146.7, 140.2, "north"),
                new PlayerFacts.Vitals(20, 20, 18, 4.2, 300, 300, 7, 0.35),
                new PlayerFacts.Held(DIAMOND, null, 0, hotbar(DIAMOND, null, PICKAXE)),
                status());
    }

    private static PlayerFacts.Status status() {
        return new PlayerFacts.Status("STANDING", Set.of(Flag.SNEAKING), true, 0);
    }

    // ------------------------------------------------------------- position

    @Test
    void thePositionFieldsSurvive() {
        Map<String, Object> body = read(facts());
        assertEquals("minecraft:overworld", body.get("dimension"));
        assertEquals(List.of(211.705, 130.0, 327.715), body.get("pos"));
        assertEquals(List.of(211.0, 130.0, 327.0), body.get("blockPos"));
    }

    // ------------------------------------------------------------- rotation

    @Test
    void rotationCarriesEveryAngleAndTheFacing() {
        Map<?, ?> rotation = (Map<?, ?>) read(facts()).get("rotation");
        assertEquals(146.7, (Double) rotation.get("yaw"));
        assertEquals(6.6, (Double) rotation.get("pitch"));
        assertEquals(146.7, (Double) rotation.get("headYaw"));
        assertEquals(140.2, (Double) rotation.get("bodyYaw"));
        assertEquals("north", rotation.get("facing"));
    }

    // --------------------------------------------------------------- vitals

    @Test
    void vitalsCarryEveryGauge() {
        Map<?, ?> vitals = (Map<?, ?>) read(facts()).get("vitals");
        assertEquals(20.0, (Double) vitals.get("health"));
        assertEquals(20.0, (Double) vitals.get("maxHealth"));
        assertEquals(18.0, (Double) vitals.get("food"));
        assertEquals(4.2, (Double) vitals.get("saturation"));
        assertEquals(300.0, (Double) vitals.get("air"));
        assertEquals(300.0, (Double) vitals.get("maxAir"));
        assertEquals(7.0, (Double) vitals.get("xpLevel"));
        assertEquals(0.35, (Double) vitals.get("xpProgress"));
    }

    // ----------------------------------------------------------------- held

    @Test
    void anItemCarriesItsIdAndCount() {
        Map<?, ?> main = (Map<?, ?>) ((Map<?, ?>) read(facts()).get("held")).get("mainHand");
        assertEquals("minecraft:diamond", main.get("id"));
        assertEquals(64.0, (Double) main.get("count"));
    }

    /** Durability is meaningless for a diamond, so it is null rather than invented. */
    @Test
    void anItemThatCannotBeDamagedHasNullDurability() {
        Map<?, ?> main = (Map<?, ?>) ((Map<?, ?>) read(facts()).get("held")).get("mainHand");
        assertNull(main.get("damage"));
        assertNull(main.get("maxDamage"));
        assertTrue(main.containsKey("damage"));
        assertTrue(main.containsKey("maxDamage"));
    }

    @Test
    void aDamageableItemCarriesItsWear() {
        Map<?, ?> held = (Map<?, ?>) read(facts()).get("held");
        List<?> hotbar = (List<?>) held.get("hotbar");
        Map<?, ?> pickaxe = (Map<?, ?>) hotbar.get(2);
        assertEquals("minecraft:diamond_pickaxe", pickaxe.get("id"));
        assertEquals(12.0, (Double) pickaxe.get("damage"));
        assertEquals(1561.0, (Double) pickaxe.get("maxDamage"));
    }

    @Test
    void anEmptyOffhandIsPresentAndNull() {
        Map<?, ?> held = (Map<?, ?>) read(facts()).get("held");
        assertNull(held.get("offHand"));
        assertTrue(held.containsKey("offHand"));
    }

    /** Nine slots, always, so an index means the same thing on every call. */
    @Test
    void theHotbarAlwaysHasNineSlots() {
        List<?> hotbar = (List<?>) ((Map<?, ?>) read(facts()).get("held")).get("hotbar");
        assertEquals(9, hotbar.size());
    }

    @Test
    void anEmptyHotbarSlotIsNullRatherThanOmitted() {
        List<?> hotbar = (List<?>) ((Map<?, ?>) read(facts()).get("held")).get("hotbar");
        assertNull(hotbar.get(1));
        assertNull(hotbar.get(8));
    }

    @Test
    void aShortHotbarIsPaddedToNine() {
        PlayerFacts facts = new PlayerFacts(
                "minecraft:overworld", 0, 0, 0, 0, 0, 0,
                new PlayerFacts.Rotation(0, 0, 0, 0, "north"),
                new PlayerFacts.Vitals(20, 20, 20, 5, 300, 300, 0, 0),
                new PlayerFacts.Held(null, null, 0, List.of(DIAMOND)),
                status());
        List<?> hotbar = (List<?>) ((Map<?, ?>) read(facts).get("held")).get("hotbar");
        assertEquals(9, hotbar.size());
        assertNull(hotbar.get(8));
    }

    @Test
    void theSelectedSlotIsCalledOut() {
        PlayerFacts facts = new PlayerFacts(
                "minecraft:overworld", 0, 0, 0, 0, 0, 0,
                new PlayerFacts.Rotation(0, 0, 0, 0, "north"),
                new PlayerFacts.Vitals(20, 20, 20, 5, 300, 300, 0, 0),
                new PlayerFacts.Held(PICKAXE, DIAMOND, 4, hotbar()),
                status());
        assertEquals(4.0, (Double) ((Map<?, ?>) read(facts).get("held")).get("selectedSlot"));
    }

    // --------------------------------------------------------------- status

    @Test
    void statusCarriesTheRawPoseAndTheFlagsBesideIt() {
        Map<?, ?> status = (Map<?, ?>) read(facts()).get("status");
        assertEquals("STANDING", status.get("pose"));
        assertEquals(Boolean.TRUE, status.get("sneaking"));
        assertEquals(Boolean.TRUE, status.get("onGround"));
        assertEquals(Boolean.FALSE, status.get("sprinting"));
        assertEquals(Boolean.FALSE, status.get("onFire"));
        assertEquals(Boolean.FALSE, status.get("inWater"));
    }

    @Test
    void everyStatusFlagIsPresentEvenWhenFalse() {
        Map<?, ?> status = (Map<?, ?>) read(facts()).get("status");
        for (String flag : List.of("sneaking", "sprinting", "swimming", "crawling", "gliding",
                "sleeping", "riding", "onFire", "inLava", "inWater", "submerged", "climbing",
                "blocking", "spectator", "onGround", "falling", "drowning")) {
            assertTrue(status.containsKey(flag), "missing flag: " + flag);
        }
    }

    // ------------------------------------------------------------ derivation

    @Test
    void fallingMeansOffTheGroundAndHavingFallen() {
        assertEquals(true, PlayerFacts.falling(3.5, false));
    }

    @Test
    void standingOnTheGroundIsNotFalling() {
        assertEquals(false, PlayerFacts.falling(3.5, true));
    }

    @Test
    void airborneWithNoFallDistanceYetIsNotFalling() {
        assertEquals(false, PlayerFacts.falling(0, false));
    }

    @Test
    void aJumpIsNotAFallUntilTheDistanceIsNonZero() {
        assertEquals(false, PlayerFacts.falling(0.0, false));
        assertEquals(true, PlayerFacts.falling(0.001, false));
    }

    @Test
    void drowningStartsWhenTheAirRunsOut() {
        assertEquals(true, PlayerFacts.drowning(0));
        assertEquals(true, PlayerFacts.drowning(-20));
    }

    @Test
    void oneTickOfAirLeftIsNotDrowning() {
        assertEquals(false, PlayerFacts.drowning(1));
    }

    @Test
    void afullAirSupplyIsNotDrowning() {
        assertEquals(false, PlayerFacts.drowning(300));
    }

    @Test
    void theDerivedFlagsReachTheJson() {
        PlayerFacts facts = new PlayerFacts(
                "minecraft:overworld", 0, 0, 0, 0, 0, 0,
                new PlayerFacts.Rotation(0, 0, 0, 0, "north"),
                new PlayerFacts.Vitals(20, 20, 20, 5, 0, 300, 0, 0),
                new PlayerFacts.Held(null, null, 0, hotbar()),
                new PlayerFacts.Status("SWIMMING",
                        Set.of(Flag.SWIMMING, Flag.IN_WATER, Flag.SUBMERGED), false, 7.25));
        Map<?, ?> status = (Map<?, ?>) read(facts).get("status");
        assertEquals(Boolean.TRUE, status.get("drowning"));
        assertEquals(Boolean.TRUE, status.get("falling"));
        assertEquals(7.25, (Double) status.get("fallDistance"));
    }

    // ------------------------------------------------------------- absence

    /** A field the render thread cannot answer says why, rather than guessing. */
    @Test
    void anAbsentFieldSaysWhyItIsAbsent() {
        Map<String, Object> absent = JsonReader.object(PlayerFacts.absent("no player in the world"));
        assertEquals("no player in the world", absent.get("absent"));
    }

    // ------------------------------------------------------------ flag names

    /** A typo in a flag name would silently never appear, so the mapping is pinned. */
    @Test
    void aSingleWordFlagIsLowercased() {
        assertEquals("sneaking", PlayerFacts.flagName(Flag.SNEAKING));
        assertEquals("spectator", PlayerFacts.flagName(Flag.SPECTATOR));
    }

    @Test
    void aTwoWordFlagBecomesCamelCase() {
        assertEquals("onFire", PlayerFacts.flagName(Flag.ON_FIRE));
        assertEquals("inLava", PlayerFacts.flagName(Flag.IN_LAVA));
        assertEquals("inWater", PlayerFacts.flagName(Flag.IN_WATER));
    }

    @Test
    void everyFlagHasADistinctName() {
        assertEquals(Flag.values().length,
                Arrays.stream(Flag.values()).map(PlayerFacts::flagName).distinct().count());
    }
}
