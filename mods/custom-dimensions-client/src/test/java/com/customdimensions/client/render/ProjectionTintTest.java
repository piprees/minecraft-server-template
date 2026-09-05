package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A projection spanning two biomes tints each column with its own: sampled at
 * the column's top block, packed into a palette, run-length encoded, decoded,
 * and read back by source position.
 *
 * <p>One tint for the volume passes every codec test there is — it round-trips
 * perfectly and paints the arrival column's grass over a view that never
 * touches that column. Only a difference BETWEEN two columns can see that, so
 * that is what these assert.
 *
 * <p>{@link #lookup} carries no states: constructing a {@link ClientProjection}
 * over a non-empty grid needs {@code Blocks.AIR}, and {@code
 * Bootstrap.initialize()} throws {@code IllegalAccessError} on this module's
 * test classpath.
 */
class ProjectionTintTest {

    private static final Identifier DESTINATION = Identifier.of("adventure", "the_amplified_reaches");
    private static final BlockPos ORIGIN = new BlockPos(100, 60, 200);
    private static final int SIZE_X = 4;
    private static final int SIZE_Y = 8;
    private static final int SIZE_Z = 3;
    private static final int AIR = 0;

    /** Distinct per sampled height, so a tint names the block it was read at. */
    private static int grassAt(int worldY) {
        return 0x100000 + worldY;
    }

    /** Column (0,0) tops out three blocks up, column (3,2) six; the rest are air. */
    private static int[] states() {
        int[] states = new int[SIZE_X * SIZE_Y * SIZE_Z];
        for (int y = 0; y < 3; y++) {
            states[(0 * SIZE_Z) * SIZE_Y + y] = 1;
        }
        for (int y = 0; y < 6; y++) {
            states[((3 * SIZE_Z) + 2) * SIZE_Y + y] = 1;
        }
        return states;
    }

    /** The sampling loop both mods run, with the world replaced by a height. */
    private static CompanionPayloads.Projection.TintGrid sampled(int[] states) {
        CompanionPayloads.Projection.TintGrid tints =
                new CompanionPayloads.Projection.TintGrid(SIZE_X, SIZE_Z);
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                int top = CompanionPayloads.Projection.topSolid(states, SIZE_Y, SIZE_Z, x, z, AIR);
                if (top < 0) {
                    continue;
                }
                int worldY = ORIGIN.getY() + top;
                tints.set(x, z, grassAt(worldY), grassAt(worldY) + 1, grassAt(worldY) + 2);
            }
        }
        return tints;
    }

    private static CompanionPayloads.Projection payload(int[] states,
            CompanionPayloads.Projection.TintGrid tints) {
        return new CompanionPayloads.Projection(
                DESTINATION, ORIGIN, List.of(ORIGIN),
                Direction.Axis.X.ordinal(), Direction.SOUTH.ordinal(),
                ORIGIN, SIZE_X, SIZE_Y, SIZE_Z, states, new byte[states.length],
                -1, -1, tints.palette(), tints.columns(), -1.0f);
    }

    /** Everything the wire does to the tints, and nothing else. */
    private static CompanionPayloads.Projection overTheWire() {
        int[] states = states();
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
        CompanionPayloads.Projection.CODEC.encode(buf, payload(states, sampled(states)));
        CompanionPayloads.Projection decoded = CompanionPayloads.Projection.CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "decode did not consume everything encode wrote");
        return decoded;
    }

    /** The same tints behind the source-position lookup the renderer calls. */
    private static ClientProjection lookup() {
        return new ClientProjection(payload(new int[0], sampled(states())));
    }

    private static int column(int x, int z) {
        return (x * SIZE_Z) + z;
    }

    @Test
    void twoColumnsInDifferentBiomesReadDifferentTints() {
        CompanionPayloads.Projection decoded = overTheWire();

        assertEquals(grassAt(62),
                decoded.columnTint(column(0, 0), CompanionPayloads.Projection.TINT_GRASS));
        assertEquals(grassAt(65),
                decoded.columnTint(column(3, 2), CompanionPayloads.Projection.TINT_GRASS),
                "the far column was painted with the near column's tint");
    }

    @Test
    void eachChannelIsCarriedSeparately() {
        CompanionPayloads.Projection decoded = overTheWire();

        assertEquals(grassAt(65) + 1,
                decoded.columnTint(column(3, 2), CompanionPayloads.Projection.TINT_FOLIAGE));
        assertEquals(grassAt(65) + 2,
                decoded.columnTint(column(3, 2), CompanionPayloads.Projection.TINT_WATER));
    }

    /** A column with no blocks in it has no surface to have read a biome at. */
    @Test
    void aColumnOfAirCarriesNoTint() {
        assertEquals(-1, overTheWire()
                .columnTint(column(1, 1), CompanionPayloads.Projection.TINT_GRASS));
    }

    /** A source position resolves to its own column, not to a single sample. */
    @Test
    void aSourcePositionReadsTheTintOfItsOwnColumn() {
        ClientProjection projection = lookup();

        assertEquals(grassAt(62), projection.tintAt(100, 200,
                CompanionPayloads.Projection.TINT_GRASS));
        assertEquals(grassAt(65), projection.tintAt(103, 202,
                CompanionPayloads.Projection.TINT_GRASS));
        assertEquals(-1, projection.tintAt(101, 201,
                CompanionPayloads.Projection.TINT_GRASS));
    }

    /** Outside the box the client's own world answers, so the lookup declines. */
    @Test
    void aPositionOutsideTheBoxCarriesNoTint() {
        ClientProjection projection = lookup();

        assertEquals(-1, projection.tintAt(99, 200,
                CompanionPayloads.Projection.TINT_GRASS));
        assertEquals(-1, projection.tintAt(100, 203,
                CompanionPayloads.Projection.TINT_GRASS));
    }
}
