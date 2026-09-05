package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The predicate the store keeps a projection on. A resend that describes the
 * same view must be recognised as one, because the projection it would replace
 * owns the built mesh — replacing it wholesale rebuilt several thousand cells
 * on the next frame, every refresh, forever.
 *
 * <p>{@code accept}'s object identity is not asserted here: constructing a
 * {@link ClientProjection} needs {@code Blocks.AIR}, and {@code
 * Bootstrap.initialize()} throws {@code IllegalAccessError} on this module's
 * test classpath. The e2e script covers it from the client log instead.
 */
class ProjectionStoreTest {

    private static final Identifier DESTINATION = Identifier.of("adventure", "the_crucible");
    private static final BlockPos APERTURE_ORIGIN = new BlockPos(228, 131, 297);
    private static final BlockPos ORIGIN = new BlockPos(224, 127, 293);
    private static final int SIZE_X = 4;
    private static final int SIZE_Y = 5;
    private static final int SIZE_Z = 6;
    private static final int CELLS = SIZE_X * SIZE_Y * SIZE_Z;
    private static final float AMBIENT = 0.15f;

    private static int[] states() {
        int[] states = new int[CELLS];
        for (int i = 0; i < CELLS; i++) {
            states[i] = i % 3 == 0 ? 0 : 1;
        }
        return states;
    }

    private static byte[] light() {
        byte[] light = new byte[CELLS];
        for (int i = 0; i < CELLS; i++) {
            light[i] = (byte) ((15 << 4) | (i % 16));
        }
        return light;
    }

    private static List<BlockPos> aperture() {
        return List.of(APERTURE_ORIGIN, APERTURE_ORIGIN.up(), APERTURE_ORIGIN.up(2));
    }

    /** A fresh payload each call, so nothing is shared by reference. */
    private static CompanionPayloads.Projection payload() {
        return new CompanionPayloads.Projection(
                DESTINATION, APERTURE_ORIGIN, aperture(), 0, 2, ORIGIN,
                SIZE_X, SIZE_Y, SIZE_Z, states(), light(),
                0x78A7FF, 0xC0D8FF, 0x79C05A, 0x59AE30, 0x3F76E4, AMBIENT);
    }

    @Test
    void anUnchangedResendIsTheSameView() {
        assertTrue(ProjectionStore.sameContent(payload(), payload()),
                "an unchanged resend was read as a new view, discarding the built mesh");
    }

    /**
     * The arrays are decoded fresh from the wire every time, so a comparison
     * that leans on the record's own equals answers false for every resend and
     * the mesh is rebuilt anyway.
     */
    @Test
    void equalContentInDistinctArraysIsTheSameView() {
        CompanionPayloads.Projection first = payload();
        CompanionPayloads.Projection second = payload();
        assertNotSame(first.states(), second.states(), "the fixture shares arrays; the test proves nothing");
        assertNotSame(first.light(), second.light(), "the fixture shares arrays; the test proves nothing");
        assertFalse(first.equals(second), "the record now compares arrays by value; this test is obsolete");

        assertTrue(ProjectionStore.sameContent(first, second),
                "equal-but-distinct state arrays were read as a changed view");
    }

    @Test
    void aChangedBlockIsADifferentView() {
        int[] changed = states();
        changed[CELLS / 2] = 42;
        assertFalse(ProjectionStore.sameContent(payload(), new CompanionPayloads.Projection(
                DESTINATION, APERTURE_ORIGIN, aperture(), 0, 2, ORIGIN,
                SIZE_X, SIZE_Y, SIZE_Z, changed, light(),
                0x78A7FF, 0xC0D8FF, 0x79C05A, 0x59AE30, 0x3F76E4, AMBIENT)));
    }

    @Test
    void changedLightIsADifferentView() {
        byte[] darker = light();
        darker[0] = 0;
        assertFalse(ProjectionStore.sameContent(payload(), new CompanionPayloads.Projection(
                DESTINATION, APERTURE_ORIGIN, aperture(), 0, 2, ORIGIN,
                SIZE_X, SIZE_Y, SIZE_Z, states(), darker,
                0x78A7FF, 0xC0D8FF, 0x79C05A, 0x59AE30, 0x3F76E4, AMBIENT)));
    }

    /** The three tints are meshed into the vertices, so a change must rebuild. */
    @Test
    void aChangedTintIsADifferentView() {
        assertFalse(ProjectionStore.sameContent(payload(), new CompanionPayloads.Projection(
                DESTINATION, APERTURE_ORIGIN, aperture(), 0, 2, ORIGIN,
                SIZE_X, SIZE_Y, SIZE_Z, states(), light(),
                0x78A7FF, 0xC0D8FF, 0x79C05A, 0x59AE30, 0x123456, AMBIENT)));
    }

    /** The backdrop is drawn from these, not from the mesh. */
    @Test
    void aChangedSkyOrFogIsADifferentView() {
        assertFalse(ProjectionStore.sameContent(payload(), new CompanionPayloads.Projection(
                DESTINATION, APERTURE_ORIGIN, aperture(), 0, 2, ORIGIN,
                SIZE_X, SIZE_Y, SIZE_Z, states(), light(),
                0x78A7FF, 0x010203, 0x79C05A, 0x59AE30, 0x3F76E4, AMBIENT)));
    }

    /** The opening grew: the clip rectangle the mesh is cut against is stale. */
    @Test
    void aChangedApertureIsADifferentView() {
        assertFalse(ProjectionStore.sameContent(payload(), new CompanionPayloads.Projection(
                DESTINATION, APERTURE_ORIGIN,
                List.of(APERTURE_ORIGIN, APERTURE_ORIGIN.up(), APERTURE_ORIGIN.up(2), APERTURE_ORIGIN.up(3)),
                0, 2, ORIGIN, SIZE_X, SIZE_Y, SIZE_Z, states(), light(),
                0x78A7FF, 0xC0D8FF, 0x79C05A, 0x59AE30, 0x3F76E4, AMBIENT)));
    }

    @Test
    void aMovedVolumeIsADifferentView() {
        assertFalse(ProjectionStore.sameContent(payload(), new CompanionPayloads.Projection(
                DESTINATION, APERTURE_ORIGIN, aperture(), 0, 2, ORIGIN.east(),
                SIZE_X, SIZE_Y, SIZE_Z, states(), light(),
                0x78A7FF, 0xC0D8FF, 0x79C05A, 0x59AE30, 0x3F76E4, AMBIENT)));
    }

    @Test
    void aFlippedNormalIsADifferentView() {
        assertFalse(ProjectionStore.sameContent(payload(), new CompanionPayloads.Projection(
                DESTINATION, APERTURE_ORIGIN, aperture(), 0, 3, ORIGIN,
                SIZE_X, SIZE_Y, SIZE_Z, states(), light(),
                0x78A7FF, 0xC0D8FF, 0x79C05A, 0x59AE30, 0x3F76E4, AMBIENT)));
    }

    @Test
    void aDifferentDestinationIsADifferentView() {
        assertFalse(ProjectionStore.sameContent(payload(), new CompanionPayloads.Projection(
                Identifier.of("adventure", "the_violet_spire"), APERTURE_ORIGIN, aperture(), 0, 2, ORIGIN,
                SIZE_X, SIZE_Y, SIZE_Z, states(), light(),
                0x78A7FF, 0xC0D8FF, 0x79C05A, 0x59AE30, 0x3F76E4, AMBIENT)));
    }

    /** The lift is baked into the mesh's light levels, so a change must rebuild. */
    @Test
    void aChangedAmbientLightIsADifferentView() {
        assertFalse(ProjectionStore.sameContent(payload(), new CompanionPayloads.Projection(
                DESTINATION, APERTURE_ORIGIN, aperture(), 0, 2, ORIGIN,
                SIZE_X, SIZE_Y, SIZE_Z, states(), light(),
                0x78A7FF, 0xC0D8FF, 0x79C05A, 0x59AE30, 0x3F76E4, 0.4f)));
    }

    /** Nothing held yet is not a match; the first payload must be stored. */
    @Test
    void nothingHeldIsNotAMatch() {
        assertFalse(ProjectionStore.sameContent(null, payload()));
        assertFalse(ProjectionStore.sameContent(payload(), null));
    }
}
