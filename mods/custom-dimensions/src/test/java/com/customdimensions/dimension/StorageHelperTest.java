package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageHelperTest {

    @Test
    void shutdownDoesNotThrow() {
        // shutdown() must be idempotent and safe even if already shut down.
        assertDoesNotThrow(StorageHelper::shutdown);
    }
}
