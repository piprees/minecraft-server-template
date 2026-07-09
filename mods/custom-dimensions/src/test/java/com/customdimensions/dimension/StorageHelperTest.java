package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageHelperTest {

    @Test
    void shutdownDoesNotThrow() {
        // StorageHelper.shutdown() should be idempotent and safe to call
        // even if the pool has already been shut down.
        // Note: calling shutdown() in tests may affect other tests if they
        // use the IO pool, but getDimensionDirectory doesn't need the pool.
        assertDoesNotThrow(StorageHelper::shutdown);
    }
}
