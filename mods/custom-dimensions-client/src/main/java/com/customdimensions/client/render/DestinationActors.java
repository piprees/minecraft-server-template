package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.realtime.DestinationWorlds;
import com.customdimensions.client.realtime.PortalFrames;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.WorldChunk;

/**
 * The far side's living contents, drawn through the same opening the terrain is.
 *
 * <p>{@link ProjectionMesh} captures block models and fluids and nothing else,
 * so a mob, a player, an arrow, a chest or a sign is invisible through a portal
 * however well the terrain draws. Those come from the destination
 * {@code ClientWorld} this client already holds, through the two vanilla
 * dispatchers, as ordinary geometry in the source frame's own pass — which is
 * what makes a shader pack shade them.
 *
 * <p>Positions are the frame's offset SUBTRACTED: the server spent the portal's
 * scale deriving that offset, and destination is source plus offset. The mesh's
 * own surface shift is applied on top so an actor stands on the same plane as
 * the terrain it is standing on.
 *
 * <p>The lightmap texture belongs to the source world. Both worlds run the
 * source's clock ({@code RealtimeView.syncClock}), so the ramp is the right one;
 * the per-actor light level is read from the destination.
 */
public final class DestinationActors {

    /** Grepped in the client log for what one opening's far side drew. */
    public static final String MARKER = "companion-client:destination-actors";

    /** Eight cone planes and the portal surface. */
    private static final AperturePlanes PLANES = new AperturePlanes(9, QuadCapture.STRIDE);

    /** How far outside the captured volume an actor may stand and still draw. */
    private static final double MARGIN = 2.0;

    private static int entities;
    private static int blockEntities;
    private static int quadsIn;
    private static int quadsOut;
    private static int light = -1;
    private static String failure = "";

    private DestinationActors() {}

    /** What the last {@link #draw} did, for the emit line. */
    public static String summary() {
        return "entities=" + entities + " blockEntities=" + blockEntities
                + " actorQuads=" + quadsOut + "/" + quadsIn
                + " light=[" + lightLabel(light) + "]"
                + (failure.isEmpty() ? "" : " failed=" + failure);
    }

    /** Entities the last draw submitted. */
    public static int entities() {
        return entities;
    }

    /** Block entities the last draw submitted. */
    public static int blockEntities() {
        return blockEntities;
    }

    /** Quads the last draw offered the opening. */
    public static int quadsIn() {
        return quadsIn;
    }

    /** Quads the opening let through, whole or trimmed. */
    public static int quadsOut() {
        return quadsOut;
    }

    /**
     * The light the last entity was drawn at, as the destination reported it.
     * A silhouette where the destination is bright is this reading low.
     */
    public static String lastLight() {
        return lightLabel(light);
    }

    /** A packed lightmap coordinate as {@code sky/block}, or {@code none}. */
    static String lightLabel(int packed) {
        if (packed < 0) {
            return "none";
        }
        return "sky=" + ((packed >> 20) & 0xF) + " block=" + ((packed >> 4) & 0xF);
    }

    /**
     * Draws one opening's destination actors under a matrix stack already
     * translated by {@code origin - camera}, clipped to the same tunnel the mesh
     * is. Returns the number of actors drawn.
     *
     * <p>{@code tunnel} and the camera are in the volume's own space, as
     * {@link ProjectionRenderer} builds them; the clip planes are rebuilt here
     * in camera-relative space because that is the space a dispatcher's
     * vertices arrive in.
     */
    static int draw(ClientProjection projection, MatrixStack matrices,
            VertexConsumerProvider.Immediate immediate, double[] tunnel, int faces,
            double camX, double camY, double camZ, float tickDelta) {
        entities = 0;
        blockEntities = 0;
        quadsIn = 0;
        quadsOut = 0;
        light = -1;
        failure = "";

        CompanionPayloads.PortalFrame frame = PortalFrames.get(projection.apertureOrigin());
        if (frame == null) {
            // A server-described slab: there is no destination world to read.
            return 0;
        }
        ClientWorld destination = DestinationWorlds.get(frame.destination());
        MinecraftClient client = MinecraftClient.getInstance();
        if (destination == null || client == null || client.world == null) {
            return 0;
        }
        if (!buildPlanes(projection, tunnel, faces, camX, camY, camZ)) {
            return 0;
        }

        double[] shift = ProjectionRenderer.meshShift(projection);
        ClientWorld source = client.world;
        ClippedConsumers consumers = new ClippedConsumers(immediate, PLANES);
        client.getEntityRenderDispatcher().setWorld(destination);
        client.getBlockEntityRenderDispatcher().setWorld(destination);
        try {
            drawEntities(projection, frame, destination, client, matrices, consumers,
                    shift, tickDelta);
            drawBlockEntities(projection, frame, destination, client, matrices, consumers,
                    shift, tickDelta);
        } catch (Throwable thrown) {
            failure = String.valueOf(thrown);
            CustomDimensionsClient.LOGGER.warn("{} dimension={} aperture={} stopped after a throw",
                    MARKER, frame.destination(), projection.apertureOrigin().toShortString(),
                    thrown);
        } finally {
            consumers.flush();
            immediate.draw();
            client.getEntityRenderDispatcher().setWorld(source);
            client.getBlockEntityRenderDispatcher().setWorld(source);
            quadsIn = consumers.quadsIn();
            quadsOut = consumers.quadsOut();
        }
        return entities + blockEntities;
    }

    private static void drawEntities(ClientProjection projection,
            CompanionPayloads.PortalFrame frame, ClientWorld destination, MinecraftClient client,
            MatrixStack matrices, ClippedConsumers consumers, double[] shift, float tickDelta) {
        BlockPos origin = projection.origin();
        for (Entity entity : destination.getEntities()) {
            if (entity.isRemoved()) {
                continue;
            }
            double x = local(MathHelper.lerp(tickDelta, entity.lastRenderX, entity.getX()),
                    frame.dx(), origin.getX(), shift[0]);
            double y = local(MathHelper.lerp(tickDelta, entity.lastRenderY, entity.getY()),
                    frame.dy(), origin.getY(), shift[1]);
            double z = local(MathHelper.lerp(tickDelta, entity.lastRenderZ, entity.getZ()),
                    frame.dz(), origin.getZ(), shift[2]);
            if (outsideVolume(projection, x, y, z)) {
                continue;
            }
            float yaw = MathHelper.lerp(tickDelta, entity.prevYaw, entity.getYaw());
            int packed = WorldRenderer.getLightmapCoordinates(destination, entity.getBlockPos());
            DestinationActors.light = packed;
            client.getEntityRenderDispatcher().render(entity, x, y, z, yaw, tickDelta,
                    matrices, consumers, packed);
            entities++;
        }
    }

    /**
     * Every block entity in the chunks the captured volume covers. They are
     * skipped by the mesh — {@code ProjectionMesh} takes only
     * {@code BlockRenderType.MODEL} — so a chest, sign, bed or banner is an
     * empty space through the opening without this.
     */
    private static void drawBlockEntities(ClientProjection projection,
            CompanionPayloads.PortalFrame frame, ClientWorld destination, MinecraftClient client,
            MatrixStack matrices, ClippedConsumers consumers, double[] shift, float tickDelta) {
        BlockPos origin = projection.origin();
        int minX = origin.getX() + frame.dx();
        int minZ = origin.getZ() + frame.dz();
        int maxX = minX + projection.sizeX() - 1;
        int maxZ = minZ + projection.sizeZ() - 1;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                WorldChunk chunk = destination.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos at = blockEntity.getPos();
                    double x = local(at.getX(), frame.dx(), origin.getX(), shift[0]);
                    double y = local(at.getY(), frame.dy(), origin.getY(), shift[1]);
                    double z = local(at.getZ(), frame.dz(), origin.getZ(), shift[2]);
                    if (outsideVolume(projection, x, y, z)) {
                        continue;
                    }
                    matrices.push();
                    matrices.translate(x, y, z);
                    client.getBlockEntityRenderDispatcher()
                            .render(blockEntity, tickDelta, matrices, consumers);
                    matrices.pop();
                    blockEntities++;
                }
            }
        }
    }

    /**
     * A destination coordinate on one axis, in the volume's own space.
     *
     * <p>The offset is SUBTRACTED and never divided: destination is source plus
     * offset, and the server already spent the portal's scale deriving it.
     * {@code shift} is the mesh's own move onto the portal surface, so an actor
     * lands on the same plane as the terrain under it.
     */
    static double local(double destination, int offset, int origin, double shift) {
        return destination - offset - origin + shift;
    }

    /**
     * Whether a position in the volume's own space is past what was captured.
     * The clip would cut anything the opening does not frame anyway; this bounds
     * the cost instead, so a mob at the far end of the destination world is not
     * submitted every frame.
     */
    static boolean outsideVolume(ClientProjection projection, double x, double y, double z) {
        return x < -MARGIN || y < -MARGIN || z < -MARGIN
                || x > projection.sizeX() + MARGIN
                || y > projection.sizeY() + MARGIN
                || z > projection.sizeZ() + MARGIN;
    }

    /**
     * The tunnel and the portal surface, in camera-relative space. The volume's
     * own space maps to it by adding {@code origin - camera}, which is the
     * negated camera the renderer already holds.
     */
    private static boolean buildPlanes(ClientProjection projection, double[] tunnel, int faces,
            double camX, double camY, double camZ) {
        double[] rects = new double[faces * 12];
        for (int corner = 0; corner < faces * 4; corner++) {
            rects[corner * 3] = tunnel[corner * 3] - camX;
            rects[corner * 3 + 1] = tunnel[corner * 3 + 1] - camY;
            rects[corner * 3 + 2] = tunnel[corner * 3 + 2] - camZ;
        }
        if (!PLANES.build(rects, faces, 0.0, 0.0, 0.0)) {
            return false;
        }
        Direction.Axis axis = projection.normalAxis();
        double surface = ProjectionRenderer.planeLocal(projection, projection.origin())
                - onAxis(camX, camY, camZ, axis);
        double facing = ClientProjection.isPositive(projection.normal()) ? 1.0 : -1.0;
        // Every cone plane runs through the camera, so the cone reaches back to
        // the eye. An actor standing short of the surface is inside it and is
        // not something the opening shows.
        return PLANES.addAxisPlane(axis.ordinal(), surface, facing);
    }

    private static double onAxis(double x, double y, double z, Direction.Axis axis) {
        switch (axis) {
            case X:
                return x;
            case Y:
                return y;
            default:
                return z;
        }
    }
}
