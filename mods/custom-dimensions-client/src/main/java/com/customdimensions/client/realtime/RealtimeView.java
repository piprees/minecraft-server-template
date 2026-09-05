package com.customdimensions.client.realtime;

import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.config.RealtimeControls;
import com.customdimensions.client.render.ProjectionStore;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the far side from the destination world this client holds, instead of
 * from a slab the server described.
 *
 * <h2>Where the camera is</h2>
 * The geometry is read at {@code destination = source + offset} and drawn at
 * the SOURCE position, so the player's own eye is already the camera on the
 * far side — a rigid translation and nothing else. The portal's scale was
 * spent server-side deriving that offset ({@link PortalCamera}); spending it
 * again here would be right at scale 1 and wrong at every other.
 *
 * <h2>The clock</h2>
 * A destination {@code ClientWorld} is built with no time in it and vanilla's
 * time packet updates the player's own world only, so an unfed destination
 * renders at time 0 forever. One save-wide time serves every dimension
 * server-side, so the player's own world is the clock.
 */
public final class RealtimeView {

    /** Grepped in the client log to prove a local projection was built. */
    public static final String BUILD_MARKER = "companion-client:local-projection";

    /** How far past the opening the local view reaches, in blocks. */
    public static final int DEPTH = 16;

    /** How far the box is widened on the two in-plane axes. */
    public static final int RADIUS = 8;

    /** Chunks held at the last build, per opening. A rebuild needs new ones. */
    private static final Map<BlockPos, Integer> BUILT_AT = new HashMap<>();

    private RealtimeView() {}

    public static void clear() {
        BUILT_AT.clear();
    }

    /** Forces one opening's next tick to rebuild, its held view having gone. */
    public static void forget(BlockPos apertureOrigin) {
        if (apertureOrigin != null) {
            BUILT_AT.remove(apertureOrigin);
        }
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null
                || !RealtimeControls.settings().renderClientSidePortals()) {
            return;
        }
        for (Identifier destination : DestinationWorlds.loadedCounts().keySet()) {
            syncClock(client.world, DestinationWorlds.get(destination));
        }
        for (CompanionPayloads.PortalFrame frame : PortalFrames.all()) {
            rebuildIfFed(frame);
        }
    }

    /** The destination runs the player's own clock and weather. */
    static void syncClock(ClientWorld source, ClientWorld destination) {
        if (source == null || destination == null) {
            return;
        }
        destination.setTime(source.getTime());
        destination.setTimeOfDay(source.getTimeOfDay());
        destination.setRainGradient(source.getRainGradient(1.0f));
        destination.setThunderGradient(source.getThunderGradient(1.0f));
    }

    /**
     * Rebuilds one opening's view when its destination has gained chunks.
     * Rebuilding on a tick that gained nothing would walk the whole box to
     * produce the payload already held.
     */
    private static void rebuildIfFed(CompanionPayloads.PortalFrame frame) {
        int held = DestinationChunks.count(frame.destination());
        if (held == 0) {
            return;
        }
        Integer builtAt = BUILT_AT.get(frame.apertureOrigin());
        if (builtAt != null && builtAt == held) {
            return;
        }
        CompanionPayloads.Projection built = build(frame);
        if (built == null) {
            return;
        }
        BUILT_AT.put(frame.apertureOrigin(), held);
        ProjectionStore.accept(built);
        CustomDimensionsClient.LOGGER.info("{} dimension={} aperture={} cells={} chunks={}",
                BUILD_MARKER, frame.destination(), frame.apertureOrigin().toShortString(),
                built.states().length, held);
    }

    /**
     * One opening's destination box, read out of the world this client holds
     * and expressed in source coordinates — the shape the render path already
     * takes from the server.
     */
    static CompanionPayloads.Projection build(CompanionPayloads.PortalFrame frame) {
        ClientWorld destination = DestinationWorlds.get(frame.destination());
        if (destination == null || frame.aperture().isEmpty()) {
            return null;
        }
        Direction normal = Direction.values()[frame.normal()];
        Direction.Axis normalAxis = normal.getAxis();
        Direction.Axis axisA = normalAxis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis axisB = normalAxis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;

        int minA = Integer.MAX_VALUE;
        int maxA = Integer.MIN_VALUE;
        int minB = Integer.MAX_VALUE;
        int maxB = Integer.MIN_VALUE;
        int plane = 0;
        for (BlockPos cell : frame.aperture()) {
            minA = Math.min(minA, on(cell, axisA));
            maxA = Math.max(maxA, on(cell, axisA));
            minB = Math.min(minB, on(cell, axisB));
            maxB = Math.max(maxB, on(cell, axisB));
            plane = on(cell, normalAxis);
        }
        boolean towardsHigh = normal.getOffsetX() + normal.getOffsetY() + normal.getOffsetZ() > 0;
        LocalVolume volume = LocalVolume.of(minA, maxA, minB, maxB, plane, towardsHigh,
                DEPTH, RADIUS);

        int[] origin = new int[3];
        int[] size = new int[3];
        origin[axisA.ordinal()] = volume.originA();
        size[axisA.ordinal()] = volume.sizeA();
        origin[axisB.ordinal()] = volume.originB();
        size[axisB.ordinal()] = volume.sizeB();
        origin[normalAxis.ordinal()] = volume.originN();
        size[normalAxis.ordinal()] = volume.sizeN();

        int sizeX = size[0];
        int sizeY = size[1];
        int sizeZ = size[2];
        int[] states = new int[sizeX * sizeY * sizeZ];
        byte[] light = new byte[states.length];
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int lx = 0; lx < sizeX; lx++) {
            for (int ly = 0; ly < sizeY; ly++) {
                for (int lz = 0; lz < sizeZ; lz++) {
                    at.set(origin[0] + lx + frame.dx(),
                            origin[1] + ly + frame.dy(),
                            origin[2] + lz + frame.dz());
                    int index = ((lx * sizeZ) + lz) * sizeY + ly;
                    states[index] = Block.getRawIdFromState(destination.getBlockState(at));
                    light[index] = (byte) ((destination.getLightLevel(LightType.SKY, at) << 4)
                            | destination.getLightLevel(LightType.BLOCK, at));
                }
            }
        }
        return new CompanionPayloads.Projection(
                frame.destination(), frame.apertureOrigin(), frame.aperture(),
                frame.portalAxis(), frame.normal(),
                new BlockPos(origin[0], origin[1], origin[2]), sizeX, sizeY, sizeZ,
                states, light,
                frame.skyColor(), frame.fogColor(), -1, -1, -1,
                destination.getDimension().ambientLight());
    }

    private static int on(BlockPos pos, Direction.Axis axis) {
        switch (axis) {
            case X:
                return pos.getX();
            case Y:
                return pos.getY();
            default:
                return pos.getZ();
        }
    }
}
