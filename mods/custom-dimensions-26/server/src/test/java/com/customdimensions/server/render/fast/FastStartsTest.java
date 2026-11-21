package com.customdimensions.server.render.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import java.util.List;
import org.junit.jupiter.api.Test;

class FastStartsTest {
    @Test
    void theSidecarListsEachStartsBoxWithItsHeights() {
        JsonArray starts = FastRender.starts(List.of(
                new FastLayers.Footprint("mns:crimson_forge", 40, -60, 70, -30, 31, 52),
                new FastLayers.Footprint("minecraft:fortress", -100, 20, -20, 140, 48, 90)));
        assertEquals(2, starts.size());
        assertEquals("minecraft:fortress", starts.get(0).getAsJsonObject().get("structure").getAsString());
        assertEquals("[40,31,-60,70,52,-30]", starts.get(1).getAsJsonObject().get("box").toString());
    }
}
