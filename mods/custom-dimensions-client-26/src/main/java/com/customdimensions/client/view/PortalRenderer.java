package com.customdimensions.client.view;

import com.customdimensions.client.CompanionClient;
import com.customdimensions.client.CompanionPayloads;
import com.customdimensions.client.feed.DecodedChunk;
import com.customdimensions.client.feed.Feed;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.data.AtlasIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Draws each fed opening's far side: the destination's blocks one layer past
 * the opening, read at {@code source + offset} and drawn at the source
 * position (a rigid translation; the scale is already spent in the offset),
 * clipped on the CPU to the sightline cone through the opening, squashed onto
 * a thin slab just in front of the portal pane ({@link Clip#squashFactor}) and
 * backed by the destination's sky colour. Everything goes through one
 * {@code submitCustomGeometry} call per opening in vanilla's cutout block
 * render type, so the frame's own depth test hides the pane behind the far
 * side and keeps whatever stands in front of the portal.
 *
 * <p>Any failure switches the renderer off for the session with one log line,
 * and the portal draws as vanilla.
 */
public final class PortalRenderer {
    static final int DEPTH = 40;
    static final int RADIUS = 48;
    static final int MAX_FACES = 30_000;
    static final double RANGE = 24;
    static final double EPSILON = 0.08;
    static final int DEFAULT_SKY = 0x78A7FF;

    private record Face(double[][] verts, int rgb, int light, float nx, float ny, float nz) {
    }

    private record Built(long eyeCell, int version, List<Face> faces) {
    }

    private static final Map<BlockPos, Built> BUILT = new HashMap<>();
    private static PalettedContainerFactory factory;
    private static ClientLevel factoryLevel;
    private static boolean failed;

    public static long openingsDrawn;
    public static long facesDrawn;
    public static int lastFaces;

    private PortalRenderer() {
    }

    public static boolean failed() {
        return failed;
    }

    public static void reset() {
        BUILT.clear();
        factory = null;
        factoryLevel = null;
    }

    public static void submit(LevelRenderContext ctx) {
        if (failed || !CompanionClient.drawing()) {
            return;
        }
        try {
            draw(ctx);
        } catch (Throwable t) {
            failed = true;
            CompanionClient.LOG.error("companion-off: the portal renderer failed, portals draw as vanilla", t);
        }
    }

    private static void draw(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || Feed.frames().isEmpty()) {
            return;
        }
        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        double[] eye = {cam.x, cam.y, cam.z};
        for (CompanionPayloads.PortalFrame frame : Feed.frames().values()) {
            BlockPos o = frame.apertureOrigin();
            if (o.distToCenterSqr(cam) > RANGE * RANGE) {
                continue;
            }
            Feed.Destination d = Feed.destination(frame.destination());
            if (d == null || d.packets.isEmpty()) {
                continue;
            }
            List<int[]> cells = new ArrayList<>(frame.aperture().size());
            for (BlockPos p : frame.aperture()) {
                cells.add(new int[] {p.getX(), p.getY(), p.getZ()});
            }
            Opening op = Opening.of(cells, frame.portalAxis());
            if (op.contains(eye)) {
                continue;
            }
            int n = op.normalAxis();
            boolean high = op.towardsHigh(eye[n]);
            double d0 = Math.abs(op.surface(high) - eye[n]);
            if (d0 < 0.05) {
                continue;
            }
            List<Face> faces = faces(level, frame, d, op, eye);
            double[][] planes = op.clipPlanes(eye);
            int sky = frame.skyColor() >= 0 ? frame.skyColor() : DEFAULT_SKY;
            int fog = frame.fogColor() >= 0 ? frame.fogColor() : sky;
            TextureAtlasSprite white = sprite(mc, "block/white_concrete");
            openingsDrawn++;
            ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.cutoutMovingBlock(),
                    (pose, vc) -> emit(pose, vc, op, eye, d0, high, faces, planes, sky, fog, white));
        }
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer vc, Opening op, double[] eye, double d0, boolean high,
                             List<Face> faces, double[][] planes, int sky, int fog, TextureAtlasSprite white) {
        int n = op.normalAxis();
        double back = high ? op.surface(true) + EPSILON : op.surface(false) - EPSILON;
        double k = (d0 + EPSILON) / d0;
        double s = op.surface(high);
        double[][] corners = {op.point(op.a0(), op.b0(), s), op.point(op.a1(), op.b0(), s),
                op.point(op.a1(), op.b1(), s), op.point(op.a0(), op.b1(), s)};
        float u = (white.getU0() + white.getU1()) / 2;
        float v = (white.getV0() + white.getV1()) / 2;
        int backdrop = Clip.scale(sky, 255.0 / 210.0);
        float[][] bq = new float[4][];
        for (int i = 0; i < 4; i++) {
            double[] c = corners[i];
            bq[i] = new float[] {(float) ((c[0] - eye[0]) * k), (float) ((c[1] - eye[1]) * k), (float) ((c[2] - eye[2]) * k)};
        }
        for (int i = 0; i < 4; i++) {
            put(pose, vc, bq[i], u, v, backdrop, LightCoordsUtil.FULL_BRIGHT, 0, 1, 0);
        }
        for (int i = 3; i >= 0; i--) {
            put(pose, vc, bq[i], u, v, backdrop, LightCoordsUtil.FULL_BRIGHT, 0, 1, 0);
        }
        int drawn = 0;
        float[][] out = new float[16][];
        for (Face f : faces) {
            List<double[]> poly = Clip.clip(List.of(f.verts), planes);
            if (poly.size() < 3) {
                continue;
            }
            drawn++;
            int m = Math.min(poly.size(), out.length);
            int[] rgb = new int[m];
            for (int i = 0; i < m; i++) {
                double[] p = poly.get(i);
                double rx = p[0] - eye[0];
                double ry = p[1] - eye[1];
                double rz = p[2] - eye[2];
                double along = Math.abs(n == 0 ? rx : n == 1 ? ry : rz);
                double q = Clip.squashFactor(along, d0, EPSILON);
                out[i] = new float[] {(float) (rx * q), (float) (ry * q), (float) (rz * q), (float) p[3], (float) p[4]};
                double dist = Math.sqrt(rx * rx + ry * ry + rz * rz);
                rgb[i] = Clip.mix(f.rgb, fog, Clip.fog(dist, DEPTH * 0.55, DEPTH));
            }
            for (int i = 1; i + 1 < m; i += 2) {
                int c = Math.min(i + 2, m - 1);
                quadVertex(pose, vc, out[0], rgb[0], f);
                quadVertex(pose, vc, out[i], rgb[i], f);
                quadVertex(pose, vc, out[i + 1], rgb[i + 1], f);
                quadVertex(pose, vc, out[c], rgb[c], f);
            }
        }
        lastFaces = drawn;
        facesDrawn += drawn;
    }

    private static void quadVertex(PoseStack.Pose pose, VertexConsumer vc, float[] p, int rgb, Face f) {
        put(pose, vc, p, p[3], p[4], rgb, f.light, f.nx, f.ny, f.nz);
    }

    private static void put(PoseStack.Pose pose, VertexConsumer vc, float[] p, float u, float v, int rgb, int light,
                            float nx, float ny, float nz) {
        vc.addVertex(pose, p[0], p[1], p[2]).setColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    private static TextureAtlasSprite sprite(Minecraft mc, String path) {
        TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        return atlas.getSprite(Identifier.withDefaultNamespace(path));
    }

    private static List<Face> faces(ClientLevel level, CompanionPayloads.PortalFrame frame, Feed.Destination d,
                                    Opening op, double[] eye) {
        long eyeCell = BlockPos.asLong((int) Math.floor(eye[0]), (int) Math.floor(eye[1]), (int) Math.floor(eye[2]));
        Built built = BUILT.get(frame.apertureOrigin());
        if (built != null && built.eyeCell == eyeCell && built.version == d.version) {
            return built.faces;
        }
        List<Face> faces = build(level, frame, d, op, eye);
        BUILT.put(frame.apertureOrigin(), new Built(eyeCell, d.version, faces));
        return faces;
    }

    private static List<Face> build(ClientLevel level, CompanionPayloads.PortalFrame frame, Feed.Destination d,
                                    Opening op, double[] eye) {
        Minecraft mc = Minecraft.getInstance();
        var models = mc.getModelManager().getBlockStateModelSet();
        var colours = mc.getBlockColors();
        DimensionType type = level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
                .getValue(frame.dimensionType());
        if (type == null) {
            return List.of();
        }
        if (factory == null || factoryLevel != level) {
            factory = PalettedContainerFactory.create(level.registryAccess());
            factoryLevel = level;
        }
        RandomSource random = RandomSource.create();
        List<BlockStateModelPart> parts = new ArrayList<>();
        List<Face> faces = new ArrayList<>();
        Direction[] sides = Direction.values();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int[] cell : op.cellsInCone(eye, DEPTH, RADIUS)) {
            if (faces.size() >= MAX_FACES) {
                break;
            }
            int x = cell[0] + frame.dx();
            int y = cell[1] + frame.dy();
            int z = cell[2] + frame.dz();
            BlockState state = state(d, type, x, y, z);
            if (state.isAir()) {
                continue;
            }
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty() && state(d, type, x, y + 1, z).getFluidState().isEmpty()) {
                boolean lava = fluid.getType().isSame(Fluids.LAVA);
                TextureAtlasSprite s = sprite(mc, lava ? "block/lava_still" : "block/water_still");
                int light = light(d, type, x, y + 1, z, lava ? 15 : 0);
                faces.add(top(cell, s, lava ? 0xFFFFFF : 0x3F76E4, light, fluid.getOwnHeight()));
            }
            if (state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            BlockStateModel model = models.get(state);
            parts.clear();
            at.set(x, y, z);
            random.setSeed(state.getSeed(at));
            model.collectParts(random, parts);
            for (int side = 0; side <= sides.length; side++) {
                Direction dir = side < sides.length ? sides[side] : null;
                int lx = x;
                int ly = y;
                int lz = z;
                if (dir != null) {
                    lx += dir.getStepX();
                    ly += dir.getStepY();
                    lz += dir.getStepZ();
                    if (state(d, type, lx, ly, lz).isSolidRender()) {
                        continue;
                    }
                }
                for (BlockStateModelPart part : parts) {
                    for (BakedQuad quad : part.getQuads(dir)) {
                        int light = light(d, type, lx, ly, lz, quad.materialInfo().lightEmission());
                        int tint = 0xFFFFFF;
                        if (quad.materialInfo().isTinted()) {
                            BlockTintSource source = colours.getTintSource(state, quad.materialInfo().tintIndex());
                            if (source != null) {
                                tint = source.color(state) & 0xFFFFFF;
                            }
                        }
                        faces.add(face(cell, quad, Clip.scale(tint, shade(quad.direction())), light));
                    }
                }
            }
        }
        return faces;
    }

    private static Face face(int[] cell, BakedQuad quad, int rgb, int light) {
        double[][] v = new double[4][];
        for (int i = 0; i < 4; i++) {
            Vector3fc p = quad.position(i);
            long uv = quad.packedUV(i);
            v[i] = new double[] {cell[0] + p.x(), cell[1] + p.y(), cell[2] + p.z(), UVPair.unpackU(uv), UVPair.unpackV(uv)};
        }
        Direction dir = quad.direction();
        return new Face(v, rgb, light, dir.getStepX(), dir.getStepY(), dir.getStepZ());
    }

    private static Face top(int[] cell, TextureAtlasSprite s, int rgb, int light, float height) {
        double y = cell[1] + Math.max(0.1, height) * 0.89;
        double[][] v = {
                {cell[0], y, cell[2], s.getU0(), s.getV0()},
                {cell[0], y, cell[2] + 1, s.getU0(), s.getV1()},
                {cell[0] + 1, y, cell[2] + 1, s.getU1(), s.getV1()},
                {cell[0] + 1, y, cell[2], s.getU1(), s.getV0()}};
        return new Face(v, rgb, light, 0, 1, 0);
    }

    /** Vanilla's directional terrain shade. */
    static double shade(Direction dir) {
        return switch (dir) {
            case UP -> 1.0;
            case DOWN -> 0.5;
            case NORTH, SOUTH -> 0.8;
            case WEST, EAST -> 0.6;
        };
    }

    private static int light(Feed.Destination d, DimensionType type, int x, int y, int z, int emission) {
        DecodedChunk c = chunk(d, type, x, z);
        if (c == null) {
            return LightCoordsUtil.pack(Math.max(emission, 0), 15);
        }
        return LightCoordsUtil.pack(Math.max(emission, c.blockLight(x, y, z)), c.skyLight(x, y, z));
    }

    private static BlockState state(Feed.Destination d, DimensionType type, int x, int y, int z) {
        DecodedChunk c = chunk(d, type, x, z);
        return c == null ? Blocks.AIR.defaultBlockState() : c.state(x, y, z);
    }

    private static DecodedChunk chunk(Feed.Destination d, DimensionType type, int x, int z) {
        long key = ChunkPos.pack(x >> 4, z >> 4);
        DecodedChunk c = d.decoded.get(key);
        if (c == null) {
            CompanionPayloads.DestinationChunk p = d.packets.get(key);
            if (p == null) {
                return null;
            }
            c = DecodedChunk.decode(p.chunk(), factory, type.minY(), type.height());
            d.decoded.put(key, c);
        }
        return c;
    }
}
