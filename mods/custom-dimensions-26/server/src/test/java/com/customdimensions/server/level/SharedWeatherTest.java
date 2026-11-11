package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SharedWeatherTest {

    @Test
    void theSharedStoreAdvancesOncePerTickWhateverTheNumberOfLevels() {
        SharedWeather.reset();
        assertTrue(SharedWeather.claim(100), "the first level in a tick advances it");
        assertFalse(SharedWeather.claim(100), "a second level the same tick only reads it");
        assertFalse(SharedWeather.claim(100), "and a third");
        assertTrue(SharedWeather.claim(101), "the next tick advances it again");
        SharedWeather.reset();
        assertTrue(SharedWeather.claim(101), "a new server starts afresh");
    }
}
