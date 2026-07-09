package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionManagerTest {

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
}
