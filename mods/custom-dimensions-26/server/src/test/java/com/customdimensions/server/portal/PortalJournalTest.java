package com.customdimensions.server.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.BlockPos;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PortalJournalTest {
    private static final BlockPos CELL = new BlockPos(4, -60, 4);

    @AfterEach
    void clear() {
        PortalJournal.clear();
    }

    @Test
    void recentHistoryIsBoundedAndEachPlayersLastIsKept() {
        for (int i = 0; i < PortalJournal.RECENT + 5; i++) {
            PortalJournal.ignited(new PortalJournal.Ignition(i % 2 == 0 ? "Ann" : null, "d" + i, "w", "t", "X", CELL, 6, i));
        }
        assertEquals(PortalJournal.RECENT, PortalJournal.ignitions().size());
        assertEquals("d5", PortalJournal.ignitions().get(0).definition());
        assertEquals("d36", PortalJournal.lastIgnition("Ann").definition());
    }

    @Test
    void particleBurstsCountAndKeepTheLastRecipients() {
        String key = PortalJournal.key("adventure:e2e_one", CELL);
        assertEquals("adventure:e2e_one@4,-60,4", key);
        PortalJournal.particles(key, 10, "minecraft:dust", 0x123456, Set.of("Ann"));
        PortalJournal.particles(key, 20, "minecraft:dust", 0x123456, Set.of());
        PortalJournal.Particles p = PortalJournal.particlesOf(key);
        assertEquals(2, p.bursts());
        assertEquals(20, p.lastTick());
        assertTrue(p.recipients().isEmpty());
    }

    @Test
    void auraPassesSumAcrossPasses() {
        PortalJournal.auraPass("k", 4, 1, 100);
        PortalJournal.auraPass("k", 0, 0, 200);
        PortalJournal.AuraPasses a = PortalJournal.auraOf("k");
        assertEquals(2, a.passes());
        assertEquals(4, a.spent());
        assertEquals(1, a.vetoed());
        assertEquals(200, a.lastTick());
    }

    @Test
    void aPreloadedTellIsConsumedOnceAndOnlyForItsDestination() {
        UUID id = UUID.randomUUID();
        PortalJournal.preloadedSent(id, "adventure:e2e_two");
        assertFalse(PortalJournal.takePreloaded(id, "minecraft:overworld"));
        PortalJournal.preloadedSent(id, "adventure:e2e_two");
        assertTrue(PortalJournal.takePreloaded(id, "adventure:e2e_two"));
        assertFalse(PortalJournal.takePreloaded(id, "adventure:e2e_two"));
    }

    @Test
    void companionPayloadsAreAbsentUntilOneIsSentAndGoWithTheConnection() {
        UUID id = UUID.randomUUID();
        assertNull(PortalJournal.companionPayloads(id));
        PortalJournal.companionPayload(id);
        PortalJournal.companionPayload(id);
        assertEquals(2L, PortalJournal.companionPayloads(id));
        PortalJournal.forget(id);
        assertNull(PortalJournal.companionPayloads(id));
    }

    @Test
    void aDestinationSlugReadsAsAName() {
        assertEquals("The Nether", PortalPresentation.displayName("the_nether"));
        assertEquals("Overworld", PortalPresentation.displayName("overworld"));
        assertEquals("E2e Two", PortalPresentation.displayName("e2e_two"));
        assertEquals("__", PortalPresentation.displayName("__"));
    }
}
