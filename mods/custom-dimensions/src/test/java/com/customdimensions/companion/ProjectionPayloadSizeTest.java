package com.customdimensions.companion;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the per-column tints cost on the wire, against a full-size volume with
 * terrain in it: the widest box {@code radiusFor} allows, the deepest
 * {@code depthFor} allows, and a surface that rises and falls so the states
 * array run-length encodes the way a real one does.
 *
 * <p>The bound is stated as a share of the whole payload rather than an
 * absolute, so a change to the state encoding cannot quietly make the tints
 * look cheap by making everything else expensive.
 */
class ProjectionPayloadSizeTest {

    private static final int SIZE_X = 26;
    private static final int SIZE_Y = 27;
    private static final int SIZE_Z = 32;

    /** The tints may not cost more than this share of the encoded payload. */
    private static final double MAX_SHARE = 0.20;

    private static int[] terrain() {
        int[] states = new int[SIZE_X * SIZE_Y * SIZE_Z];
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                int height = 12 + (int) (3.0 * Math.sin(x * 0.4) + 3.0 * Math.cos(z * 0.3));
                for (int y = 0; y < height; y++) {
                    states[((x * SIZE_Z) + z) * SIZE_Y + y] = 1;
                }
            }
        }
        return states;
    }

    private static byte[] light(int[] states) {
        byte[] light = new byte[states.length];
        for (int i = 0; i < states.length; i++) {
            light[i] = (byte) (states[i] == 0 ? 0xF0 : 0x00);
        }
        return light;
    }

    /** Two biomes split across the box, which is what a real edge looks like. */
    private static CompanionPayloads.Projection.TintGrid twoBiomes() {
        CompanionPayloads.Projection.TintGrid tints =
                new CompanionPayloads.Projection.TintGrid(SIZE_X, SIZE_Z);
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                boolean far = x + z > (SIZE_X + SIZE_Z) / 2;
                tints.set(x, z, far ? 0x8AB689 : 0x79C05A, far ? 0x6DA36B : 0x59AE30,
                        far ? 0x4E7BC8 : 0x3F76E4);
            }
        }
        return tints;
    }

    private static int encodedBytes(int[] palette, int[] columns) {
        int[] states = terrain();
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        CompanionPayloads.Projection.CODEC.encode(buf, new CompanionPayloads.Projection(
                Identifier.of("adventure", "the_amplified_reaches"),
                new BlockPos(1730, 68, 1296), List.of(new BlockPos(1730, 68, 1296)),
                2, 5, new BlockPos(1718, 56, 1280), SIZE_X, SIZE_Y, SIZE_Z,
                states, light(states), 0xAF2B2B, 0x0E2A44, palette, columns, 0.0f));
        return buf.readableBytes();
    }

    @Test
    void perColumnTintsCostASmallShareOfTheStatesTheyRideBeside() {
        CompanionPayloads.Projection.TintGrid tints = twoBiomes();
        int withTints = encodedBytes(tints.palette(), tints.columns());
        int withoutTints = encodedBytes(new int[] {-1, -1, -1},
                new int[SIZE_X * SIZE_Z]);
        int cost = withTints - withoutTints;

        System.out.println("PROJECTION-BYTES cells=" + (SIZE_X * SIZE_Y * SIZE_Z)
                + " columns=" + (SIZE_X * SIZE_Z)
                + " one-tint=" + withoutTints + " per-column=" + withTints
                + " tint-cost=" + cost);

        assertTrue(cost < withTints * MAX_SHARE,
                "per-column tints cost " + cost + " bytes of " + withTints
                        + ", over the " + MAX_SHARE + " share they are allowed");
    }
}
