package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionManagerTest {

    static final class Seedable {
        long lastSeed = Long.MIN_VALUE;
        final String value;

        Seedable(String value) {
            this.value = value;
        }

        public Seedable withSeed(long seed) {
            this.lastSeed = seed;
            return this;
        }
    }

    static final class NotSeedable {
        final String value;

        NotSeedable(String value) {
            this.value = value;
        }
    }

    @Test
    void singletonInstanceIsConsistent() {
        DimensionManager a = DimensionManager.getInstance();
        DimensionManager b = DimensionManager.getInstance();
        assertSame(a, b);
    }

    @Test
    void dimensionExistsReturnsFalseForUnknown() {
        assertFalse(DimensionManager.getInstance().dimensionExists("nonexistent_dimension_xyz"));
    }

    @Test
    void getServerReturnsNullBeforeInit() {
        // Other tests may already have initialised a server, so this only
        // checks that a random dimension name is never reported as existing.
        DimensionManager dm = DimensionManager.getInstance();
        assertFalse(dm.dimensionExists("random_test_" + System.nanoTime()));
    }

    @Test
    void invokeWithSeedReflectivelyReturnsSeededObjectWhenMethodExists() {
        Seedable seedable = new Seedable("ok");

        Object result = DimensionManager.invokeWithSeedReflectively(seedable, 22222L);

        assertSame(seedable, result);
        assertEquals(22222L, seedable.lastSeed);
    }

    @Test
    void invokeWithSeedReflectivelyReturnsNullWhenMethodMissing() {
        NotSeedable notSeedable = new NotSeedable("nope");

        Object result = DimensionManager.invokeWithSeedReflectively(notSeedable, 11111L);

        assertNull(result);
    }

    @Test
    void invokeWithSeedReflectivelyReturnsNullWhenInputNull() {
        Object result = DimensionManager.invokeWithSeedReflectively(null, 42L);
        assertNull(result);
    }
}
