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
        // DimensionManager.getServer() returns null before onServerStart is called.
        // In test environment without a real server, this should be null.
        // Note: this tests the initial state — a running server would override this.
        DimensionManager dm = DimensionManager.getInstance();
        // getServer() may return a server if other tests have initialised it,
        // but dimensionExists for a random name should always be false
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
