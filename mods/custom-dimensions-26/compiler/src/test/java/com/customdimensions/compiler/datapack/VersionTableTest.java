package com.customdimensions.compiler.datapack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTableTest {

    @Test
    void baselineIs263() {
        VersionTable.Version v = VersionTable.forId("26.3");
        assertEquals(121, v.packFormat());
        assertTrue(v.noiseSettingsDefaultBlock());
        assertFalse(v.multiNoiseDisjoint());
    }

    @Test
    void release264DropsDefaultBlockAndRequiresDisjointEntries() {
        VersionTable.Version v = VersionTable.forId("26.4");
        assertEquals(125, v.packFormat());
        assertFalse(v.noiseSettingsDefaultBlock());
        assertTrue(v.multiNoiseDisjoint());
    }

    @Test
    void snapshotResolvesToItsRelease() {
        assertSame(VersionTable.V26_4, VersionTable.forId("26.4-snapshot-5"));
    }

    @Test
    void unknownVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> VersionTable.forId("1.21.1"));
    }
}
