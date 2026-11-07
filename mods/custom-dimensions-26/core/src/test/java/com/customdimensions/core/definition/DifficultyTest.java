package com.customdimensions.core.definition;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifficultyTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text.replace('\'', '"')).getAsJsonObject();
    }

    private static final String GAUNTLET = "{'mobMultiplier': 3.0, 'playerLuck': 2.5, 'depthScaling': {'enabled': true,"
            + " 'startY': 128, 'endY': -64, 'minMultiplier': 1.0, 'maxMultiplier': 2.0},"
            + " 'attributes': {'health': true, 'damage': true, 'armor': true, 'speed': true, 'knockback': true}}";

    @Test
    void anAbsentBlockIsUnscaledWithHostileSpawning() {
        Difficulty d = Difficulty.of(null, null);
        assertEquals(Difficulty.NONE, d);
        assertFalse(d.scales(-64));
        assertEquals(0.0, d.luckBonus());
    }

    @Test
    void depthIsLinearBetweenStartAndEndAndClampedOutside() {
        Difficulty d = Difficulty.of(json(GAUNTLET), null);
        assertEquals(1.0, d.depthFactor(128), 1e-12);
        assertEquals(1.0, d.depthFactor(300), 1e-12);
        assertEquals(2.0, d.depthFactor(-64), 1e-12);
        assertEquals(2.0, d.depthFactor(-200), 1e-12);
        assertEquals(1.5, d.depthFactor(32), 1e-12);
        assertEquals(3.0 * (1.0 + 160.0 / 192.0), d.mobFactor(-32), 1e-12);
    }

    @Test
    void depthScalingDefaultsToSixtyFourDownToMinusSixtyFourAndOneToOneAndAHalf() {
        Difficulty d = Difficulty.of(json("{'depthScaling': {'enabled': true}}"), null);
        assertEquals(1.0, d.mobFactor(64), 1e-12);
        assertEquals(1.25, d.mobFactor(0), 1e-12);
        assertEquals(1.5, d.mobFactor(-64), 1e-12);
    }

    @Test
    void aDisabledOrEqualBoundDepthScalingIsFlat() {
        assertNull(Difficulty.of(json("{'depthScaling': {'enabled': false, 'maxMultiplier': 9}}"), null).depthScaling());
        Difficulty flat = Difficulty.of(json("{'depthScaling': {'enabled': true, 'startY': 0, 'endY': 0,"
                + " 'minMultiplier': 1.2, 'maxMultiplier': 3}}"), null);
        assertEquals(1.2, flat.depthFactor(-100), 1e-12);
    }

    @Test
    void aZeroMultiplierLeavesMobsUnscaled() {
        Difficulty peaceful = Difficulty.of(json("{'mobMultiplier': 0.0, 'playerLuck': 2.0, 'hostileSpawning': false}"), null);
        assertFalse(peaceful.hostileSpawning());
        assertEquals(1.0, peaceful.mobFactor(0));
        assertFalse(peaceful.scales(0));
        assertEquals(1.0, peaceful.luckBonus(), 1e-12);
    }

    @Test
    void attributesDefaultToHealthDamageArmourAndDefinitionsOverrideSettings() {
        Difficulty plain = Difficulty.of(json("{'mobMultiplier': 2}"), null);
        assertTrue(plain.health() && plain.damage() && plain.armor());
        assertFalse(plain.speed() || plain.knockback());
        JsonObject defaults = json("{'mobMultiplier': 1.5, 'attributes': {'armor': false, 'speed': true}}");
        Difficulty merged = Difficulty.of(json("{'attributes': {'speed': false}}"), defaults);
        assertEquals(1.5, merged.mobMultiplier());
        assertFalse(merged.armor());
        assertFalse(merged.speed());
        Difficulty gauntlet = Difficulty.of(json(GAUNTLET), defaults);
        assertTrue(gauntlet.speed() && gauntlet.knockback() && gauntlet.armor());
    }

    @Test
    void speedScalesByAQuarterOfTheExcess() {
        assertEquals(1.5, Difficulty.speedFactor(3.0), 1e-12);
        assertEquals(1.0, Difficulty.speedFactor(1.0), 1e-12);
        assertEquals(0.875, Difficulty.speedFactor(0.5), 1e-12);
    }

    @Test
    void everyShippedDefinitionReadsAndPeacefulOnesNeverScaleMobs() throws Exception {
        Path dir = Path.of(System.getProperty("user.dir")).getParent().resolve("definitions");
        JsonObject defaults = json(Files.readString(dir.resolve("settings.json")))
                .getAsJsonObject("defaults").getAsJsonObject("difficulty");
        int peaceful = 0;
        int read = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("the_")
                    || p.getFileName().toString().equals("overworld.json")).toList()) {
                Definition def = DefinitionReader.read(file);
                Difficulty d = Difficulty.of(def.difficulty(), defaults);
                read++;
                if (!d.hostileSpawning()) {
                    peaceful++;
                    assertFalse(d.mobMultiplier() > 1.0, file + " is peaceful yet scales mobs");
                }
                assertTrue(d.mobFactor(-64) >= 1.0 || d.mobMultiplier() < 1.0, file.toString());
            }
        }
        assertEquals(7, peaceful);
        assertTrue(read >= 80, "read " + read);
    }
}
