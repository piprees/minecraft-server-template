package com.customdimensions.companion;

import com.customdimensions.command.Artefacts;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player changing world leaves behind on the server.
 *
 * <p>A client clears every destination world it holds the moment it changes
 * dimension. The two feeds skip what they believe that client already has, so
 * a record kept across the change silences them for the rest of the session
 * and the far side of every portal back draws sky.
 *
 * <p>Fixtures are the live rig: source opening at x 3464..3466 on the z=2592
 * plane, destination offset (-1732, -1296), arrival column 1732,1296 — chunk
 * 108,81.
 */
class DestinationRecycleTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Identifier FAR_SIDE = Identifier.of("adventure", "the_amplified_reaches");
    private static final Identifier NEAR_SIDE = Identifier.of("minecraft", "overworld");

    private static final double PLANE = 2592.0;
    private static final double A0 = 3464.0;
    private static final double A1 = 3466.0;
    private static final double EYE_A = 3465.0;
    private static final double EYE_N = 2597.0;
    private static final int DX = -1732;
    private static final int DZ = -1296;
    private static final int ARRIVAL_CHUNK_X = 108;
    private static final int ARRIVAL_CHUNK_Z = 81;

    @BeforeEach
    void emptyRegistry() {
        CompanionNetwork.clear();
    }

    @AfterEach
    void leaveNothingBehind() {
        CompanionNetwork.clear();
    }

    @Test
    void aWorldChangeDropsTheChunksTheClientDroppedWithIt() {
        DestinationFeed.remember(PLAYER, FAR_SIDE, keys(29));

        CompanionNetwork.forgetDestinations(PLAYER);

        assertEquals(0, DestinationFeed.sentCount(PLAYER, FAR_SIDE),
                "the server still believes this client holds chunks it threw away");
    }

    /** The client clears every destination it holds, not just the one entered. */
    @Test
    void everyDestinationGoesTogether() {
        DestinationFeed.remember(PLAYER, FAR_SIDE, keys(29));
        DestinationFeed.remember(PLAYER, NEAR_SIDE, keys(11));

        CompanionNetwork.forgetDestinations(PLAYER);

        assertTrue(DestinationFeed.heldFor(PLAYER).isEmpty(),
                "a destination's record outlived the client's copy of it");
    }

    @Test
    void aWorldChangeDropsTheEntitySnapshotToo() {
        DestinationEntityFeed.remember(PLAYER, FAR_SIDE, 100L,
                List.of(new DestinationEntityFeed.Seen(502, 1732.0, 63.0, 1296.0, 0f, 0f, 0f)));

        CompanionNetwork.forgetDestinations(PLAYER);

        assertEquals(0, DestinationEntityFeed.sentCount(PLAYER, FAR_SIDE),
                "a still far side would send nothing against this stale baseline");
    }

    /**
     * The control that stops this degenerating into {@code forget}: a player
     * who walks through a portal is the same client in a new dimension, and a
     * dropped handshake would put them back on the server-drawn slab.
     */
    @Test
    void theHandshakeAndTheDeclarationSurviveAWorldChange() {
        CompanionNetwork.onHello(PLAYER, "Tester", Artefacts.stackVersion());
        CompanionNetwork.onPortalView(PLAYER, "Tester",
                new CompanionPayloads.PortalView(true, false, 16, 64));
        DestinationFeed.remember(PLAYER, FAR_SIDE, keys(29));

        CompanionNetwork.forgetDestinations(PLAYER);

        assertTrue(CompanionNetwork.isCompanion(PLAYER), "the handshake was dropped too");
        assertFalse(CompanionNetwork.streamsSlab(PLAYER),
                "the declaration was dropped, so the server went back to drawing the far side");
    }

    @Test
    void onePlayersWorldChangeLeavesEveryOtherViewerAlone() {
        DestinationFeed.remember(PLAYER, FAR_SIDE, keys(29));
        DestinationFeed.remember(OTHER, FAR_SIDE, keys(29));

        CompanionNetwork.forgetDestinations(PLAYER);

        assertEquals(29, DestinationFeed.sentCount(OTHER, FAR_SIDE),
                "a bystander was re-fed a destination they never left");
    }

    /**
     * The record is what silences the feed, and dropping it is what makes a
     * second approach fill again — the whole symptom, in one assertion pair.
     */
    @Test
    void aSecondApproachIsFedAgainOnlyOnceTheRecordIsDropped() {
        List<Long> firstApproach = wedge(Set.of());
        assertFalse(firstApproach.isEmpty(), "the first approach fed nothing");
        DestinationFeed.remember(PLAYER, FAR_SIDE, unbox(firstApproach));

        assertTrue(wedge(held()).isEmpty(), "a chunk the client already holds was fed again");

        CompanionNetwork.forgetDestinations(PLAYER);

        assertEquals(firstApproach, wedge(held()),
                "a viewer who came back through the portal was fed nothing");
    }

    private static List<Long> wedge(Set<Long> sent) {
        return DestinationFeed.nextChunks(ARRIVAL_CHUNK_X, ARRIVAL_CHUNK_Z, 16,
                EYE_A, EYE_N, A0, A1, PLANE, DX, DZ, sent, Integer.MAX_VALUE,
                DestinationFeed.Normal.Z, false,
                DestinationFeed.coreDepth(PortalViewPreference.DEFAULT_VIEW_DEPTH));
    }

    private static Set<Long> held() {
        return DestinationFeed.heldFor(PLAYER).getOrDefault(FAR_SIDE, new HashSet<>());
    }

    private static long[] unbox(List<Long> keys) {
        long[] out = new long[keys.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = keys.get(i);
        }
        return out;
    }

    /** {@code count} distinct chunk keys around the arrival. */
    private static long[] keys(int count) {
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = DestinationFeed.chunkKey(ARRIVAL_CHUNK_X + i, ARRIVAL_CHUNK_Z);
        }
        return out;
    }
}
