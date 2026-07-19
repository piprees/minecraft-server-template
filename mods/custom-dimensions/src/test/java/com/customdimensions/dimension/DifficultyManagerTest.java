package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DifficultyManagerTest {
    private static final Gson GSON = new Gson();

    private DimensionConfig parse(String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName("test");
        return config;
    }

    @Test
    void depthFactorIsLinearBetweenStartAndEnd() {
        DimensionConfig config = parse("""
                {"difficulty":{"depthScaling":{"enabled":true,"startY":64,"endY":-64,
                 "minMultiplier":1.0,"maxMultiplier":1.5}}}
                """);
        DimensionConfig.DepthScaling scaling = config.getDifficulty().depthScaling;
        assertEquals(1.0, DifficultyManager.depthFactor(scaling, 100));
        assertEquals(1.0, DifficultyManager.depthFactor(scaling, 64));
        assertEquals(1.25, DifficultyManager.depthFactor(scaling, 0), 1e-9);
        assertEquals(1.5, DifficultyManager.depthFactor(scaling, -64));
        assertEquals(1.5, DifficultyManager.depthFactor(scaling, -200));
    }

    @Test
    void depthFactorDisabledOrAbsentIsNeutral() {
        assertEquals(1.0, DifficultyManager.depthFactor(null, 0));
        DimensionConfig config = parse("""
                {"difficulty":{"depthScaling":{"enabled":false,"startY":64,"endY":-64,
                 "minMultiplier":1.0,"maxMultiplier":1.5}}}
                """);
        assertEquals(1.0, DifficultyManager.depthFactor(config.getDifficulty().depthScaling, -64));
    }

    @Test
    void effectiveMultiplierCombinesBaseAndDepth() {
        DimensionConfig config = parse("""
                {"difficulty":{"mobMultiplier":2.0,
                 "depthScaling":{"enabled":true,"startY":64,"endY":-64,
                 "minMultiplier":1.0,"maxMultiplier":1.5}}}
                """);
        assertEquals(2.0, DifficultyManager.effectiveMultiplier(config, 100), 1e-9);
        assertEquals(3.0, DifficultyManager.effectiveMultiplier(config, -64), 1e-9);
    }

    @Test
    void peacefulZeroMultiplierIsNeutralNotLethal() {
        DimensionConfig config = parse("{\"difficulty\":{\"mobMultiplier\":0.0}}");
        assertEquals(1.0, DifficultyManager.effectiveMultiplier(config, 64));
    }

    @Test
    void missingDifficultyBlockIsNeutral() {
        assertEquals(1.0, DifficultyManager.effectiveMultiplier(parse("{}"), 64));
        assertEquals(1.0, DifficultyManager.effectiveMultiplier(null, 64));
    }
}
