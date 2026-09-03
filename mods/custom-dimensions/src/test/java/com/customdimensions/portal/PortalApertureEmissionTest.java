package com.customdimensions.portal;

import net.minecraft.particle.ParticleEffect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an opening actually SPAWNS: the position, the velocity triple and the
 * player each particle went to.
 *
 * <p>{@link PortalAperture} plans a pass and is asserted separately; nothing
 * there can say which way a particle left, or whether it left at all. An
 * immersive portal's projection slab always stands on the viewer's far side,
 * so dust drifting that way is behind it and correctly occluded — it has to
 * come TOWARDS whoever is looking, and "towards" only exists once there is a
 * viewer.
 */
class PortalApertureEmissionTest {

    /** Spread within a cell, across the plane, and the drift — PortalHelper's own numbers. */
    private static final double CELL_JITTER = 0.42;
    private static final double PLANE_JITTER = 0.12;
    private static final double DRIFT_SPEED = 0.25;
    private static final double DRIFT_LIFT = 0.35;

    private static final ParticleEffect EFFECT = () -> null;

    /** One particle as it left the server, including who it left for. */
    private record Emission(String viewer, ParticleEffect effect, double x, double y, double z,
            int count, double dx, double dy, double dz, double speed) {
    }

    /** A viewer that records instead of sending. */
    private static final class Recorder implements PortalHelper.ApertureViewer {
        private final String name;
        private final BlockPos position;
        private final List<Emission> got = new ArrayList<>();

        Recorder(String name, BlockPos position) {
            this.name = name;
            this.position = position;
        }

        @Override
        public BlockPos position() {
            return position;
        }

        @Override
        public void send(ParticleEffect effect, double x, double y, double z, int count,
                double dx, double dy, double dz, double speed) {
            got.add(new Emission(name, effect, x, y, z, count, dx, dy, dz, speed));
        }
    }

    /** A vertical plane on the X axis at z=0: width along X, height along Y, normal Z. */
    private static Set<BlockPos> planeX(int width, int height) {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                cells.add(new BlockPos(x, 64 + y, 0));
            }
        }
        return cells;
    }

    /** A vertical plane on the Z axis at x=0: width along Z, height along Y, normal X. */
    private static Set<BlockPos> planeZ(int width, int height) {
        Set<BlockPos> cells = new HashSet<>();
        for (int z = 0; z < width; z++) {
            for (int y = 0; y < height; y++) {
                cells.add(new BlockPos(0, 64 + y, z));
            }
        }
        return cells;
    }

    /** A horizontal plane at y=70: normal Y. */
    private static Set<BlockPos> planeY(int width, int depth) {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                cells.add(new BlockPos(x, 70, z));
            }
        }
        return cells;
    }

    /** One pass at full density, planned and emitted exactly as {@code emitAperture} runs it. */
    private static List<BlockPos> emit(Set<BlockPos> interior, Direction.Axis axis, long tick,
            List<Recorder> recorders) {
        List<BlockPos> cells = PortalAperture.emittingCells(interior, axis, tick, 1.0, 1.0);
        PortalHelper.emitToViewers(new ArrayList<>(recorders), cells, axis, EFFECT, tick);
        return cells;
    }

    private static double driftOn(Emission e, Direction.Axis normal) {
        return switch (normal) {
            case X -> e.dx();
            case Y -> e.dy();
            case Z -> e.dz();
        };
    }

    // ------------------------------------------------------------------
    // Which way the dust goes, and for whom
    // ------------------------------------------------------------------

    @Test
    void everyParticleDriftsTowardsTheViewerItWasSentTo() {
        Recorder south = new Recorder("south", new BlockPos(0, 64, -4));
        Recorder north = new Recorder("north", new BlockPos(0, 64, 4));
        Set<BlockPos> interior = planeX(2, 3);
        for (long tick = 0; tick < 200; tick++) {
            emit(interior, Direction.Axis.X, tick, List.of(south, north));
        }
        assertFalse(south.got.isEmpty(), "the pass emitted nothing at all");
        for (Emission e : south.got) {
            assertEquals(-1.0, e.dz(), 1e-9,
                    "a viewer at z=-4 must get dust coming at them, not going into the "
                            + "projection slab behind the plane: " + e);
        }
        for (Emission e : north.got) {
            assertEquals(1.0, e.dz(), 1e-9, "a viewer at z=+4 must get dust coming at them: " + e);
        }
    }

    @Test
    void twoPlayersEitherSideOfOneFrameGetOppositeDrift() {
        Recorder west = new Recorder("west", new BlockPos(-9, 64, 1));
        Recorder east = new Recorder("east", new BlockPos(9, 64, 1));
        Set<BlockPos> interior = planeZ(2, 3);
        for (long tick = 0; tick < 120; tick++) {
            emit(interior, Direction.Axis.Z, tick, List.of(west, east));
        }
        assertFalse(west.got.isEmpty(), "the pass emitted nothing at all");
        assertEquals(west.got.size(), east.got.size(), "both viewers get the whole pass");
        for (Emission e : west.got) {
            assertEquals(-1.0, e.dx(), 1e-9, "outward for the viewer at x=-9: " + e);
        }
        for (Emission e : east.got) {
            assertEquals(1.0, e.dx(), 1e-9, "outward for the viewer at x=+9: " + e);
        }
    }

    @Test
    void aHorizontalOpeningDriftsUpOrDownToMatchTheViewer() {
        Recorder below = new Recorder("below", new BlockPos(0, 60, 0));
        Recorder above = new Recorder("above", new BlockPos(0, 80, 0));
        Set<BlockPos> interior = planeY(3, 3);
        for (long tick = 0; tick < 120; tick++) {
            emit(interior, Direction.Axis.Y, tick, List.of(below, above));
        }
        assertFalse(below.got.isEmpty(), "the pass emitted nothing at all");
        for (Emission e : below.got) {
            assertEquals(-1.0, e.dy(), 1e-9, "outward is DOWN for a viewer under the plane: " + e);
            assertEquals(0.0, e.dx(), 1e-9, "no sideways drift on a horizontal plane");
            assertEquals(0.0, e.dz(), 1e-9, "no sideways drift on a horizontal plane");
        }
        for (Emission e : above.got) {
            assertEquals(1.0, e.dy(), 1e-9, "outward is UP for a viewer over the plane: " + e);
        }
    }

    @Test
    void aViewerStandingInTheDoorwayIsFedBothWays() {
        // Level with the plane there is no near side, so the deterministic
        // split is the only honest answer and it must survive.
        Recorder inside = new Recorder("inside", new BlockPos(1, 65, 0));
        Set<BlockPos> interior = planeX(2, 3);
        for (long tick = 0; tick < 200; tick++) {
            emit(interior, Direction.Axis.X, tick, List.of(inside));
        }
        boolean positive = false;
        boolean negative = false;
        for (Emission e : inside.got) {
            positive |= e.dz() > 0;
            negative |= e.dz() < 0;
        }
        assertTrue(positive && negative,
                "standing in the opening, the dust must still leave both ways");
    }

    @Test
    void driftIsAlwaysAUnitSignFollowingTheViewersSide() {
        // A sign is a sign: the client multiplies the offset triple by the
        // speed, so anything but +/-1 changes how far the dust travels.
        Set<BlockPos> interior = planeX(2, 3);
        for (int offset : new int[]{-32, -1, 1, 32}) {
            Recorder viewer = new Recorder("v" + offset, new BlockPos(0, 64, offset));
            for (long tick = 0; tick < 40; tick++) {
                emit(interior, Direction.Axis.X, tick, List.of(viewer));
            }
            assertFalse(viewer.got.isEmpty());
            for (Emission e : viewer.got) {
                assertEquals(1.0, Math.abs(driftOn(e, Direction.Axis.Z)), 1e-9,
                        "drift must be a unit sign: " + e);
                assertEquals(offset < 0 ? -1.0 : 1.0, e.dz(), 1e-9,
                        "sign must follow the side the viewer is on: " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    // The packet itself
    // ------------------------------------------------------------------

    @Test
    void eachViewerGetsTheWholePassAndNothingIsBroadcast() {
        Recorder a = new Recorder("a", new BlockPos(0, 64, -3));
        Recorder b = new Recorder("b", new BlockPos(0, 64, -5));
        Set<BlockPos> interior = planeX(4, 5);
        int cells = 0;
        for (long tick = 0; tick < 30; tick++) {
            cells += emit(interior, Direction.Axis.X, tick, List.of(a, b)).size();
        }
        assertTrue(cells > 0, "the fixture must plan something");
        assertEquals(cells, a.got.size(), "one particle per planned cell, per viewer");
        assertEquals(cells, b.got.size(), "one particle per planned cell, per viewer");
    }

    @Test
    void aParticleCarriesTheDriftAndNothingElse() {
        Recorder viewer = new Recorder("v", new BlockPos(0, 64, -6));
        Set<BlockPos> interior = planeX(3, 4);
        for (long tick = 0; tick < 60; tick++) {
            emit(interior, Direction.Axis.X, tick, List.of(viewer));
        }
        assertFalse(viewer.got.isEmpty());
        for (Emission e : viewer.got) {
            assertSame(EFFECT, e.effect(), "the configured effect must be the one sent");
            assertEquals(0, e.count(),
                    "count must stay 0 — it is what makes the offset triple a velocity");
            assertEquals(DRIFT_SPEED, e.speed(), 1e-9);
            assertEquals(DRIFT_LIFT, e.dy(), 1e-9, "a vertical opening lifts its dust");
            assertEquals(0.0, e.dx(), 1e-9, "an X-axis plane drifts along Z only");
            assertTrue(Math.abs(e.z() - 0.5) <= PLANE_JITTER + 1e-9,
                    "the spawn must sit in the plane, not out in front of it: " + e);
            assertTrue(inSomeCell(interior, e), "spawned outside the opening: " + e);
        }
    }

    /** In-plane jitter may cross a cell boundary; it may not leave the opening. */
    private static boolean inSomeCell(Set<BlockPos> interior, Emission e) {
        for (BlockPos cell : interior) {
            if (Math.abs(e.x() - (cell.getX() + 0.5)) <= CELL_JITTER + 1e-9
                    && Math.abs(e.y() - (cell.getY() + 0.5)) <= CELL_JITTER + 1e-9) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theSpawnIsItsCellCentrePlusJitterOnEveryAxis() {
        // The exact arithmetic, so a dropped jitter channel or a lost half
        // block reddens here rather than only looking slightly wrong in game.
        Recorder viewer = new Recorder("v", new BlockPos(0, 64, -6));
        Set<BlockPos> interior = planeX(3, 4);
        long tick = 41;
        List<BlockPos> cells = emit(interior, Direction.Axis.X, tick, List.of(viewer));
        assertEquals(cells.size(), viewer.got.size());
        for (int i = 0; i < cells.size(); i++) {
            BlockPos cell = cells.get(i);
            Emission e = viewer.got.get(i);
            assertEquals(cell.getX() + 0.5 + PortalAperture.jitter(tick, cell, 0, CELL_JITTER),
                    e.x(), 1e-9, "x");
            assertEquals(cell.getY() + 0.5 + PortalAperture.jitter(tick, cell, 1, CELL_JITTER),
                    e.y(), 1e-9, "y");
            assertEquals(cell.getZ() + 0.5 + PortalAperture.jitter(tick, cell, 2, PLANE_JITTER),
                    e.z(), 1e-9, "z takes the SMALL jitter — it is the axis across the plane");
        }
    }

    @Test
    void nobodyNearMeansNoParticlesAtAll() {
        Set<BlockPos> interior = planeX(4, 5);
        List<BlockPos> cells = PortalAperture.emittingCells(interior, Direction.Axis.X, 7, 1.0, 1.0);
        assertFalse(cells.isEmpty(), "the fixture must plan something to skip");
        PortalHelper.emitToViewers(List.of(), cells, Direction.Axis.X, EFFECT, 7);
    }

    @Test
    void anEmptyPassSendsNothing() {
        Recorder viewer = new Recorder("v", new BlockPos(0, 64, -6));
        PortalHelper.emitToViewers(List.of(viewer), List.of(), Direction.Axis.X, EFFECT, 3);
        assertTrue(viewer.got.isEmpty(), "no planned cells must mean no packets");
    }

    /**
     * The one line a {@link Recorder} cannot stand in for: the live player's
     * send. Read from the compiled class, because driving it needs a
     * ServerWorld — and a drift chosen per viewer means nothing if the packet
     * that carries it goes to everybody.
     */
    @Test
    void theLivePlayerSendUsesThePerPlayerOverloadAndNeverBroadcasts() throws IOException {
        Path clazz = Path.of("build", "classes", "java", "main", "com", "customdimensions",
                "portal", "PortalHelper$PlayerViewer.class");
        assertTrue(Files.isRegularFile(clazz),
                "compiled sender not found at " + clazz.toAbsolutePath()
                        + " — this test reads bytecode, it must never silently skip");
        List<String> spawnCalls = new ArrayList<>();
        try (InputStream in = Files.newInputStream(clazz)) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!name.equals("send")) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String callName,
                                String callDesc, boolean isInterface) {
                            if (callName.equals("spawnParticles")) {
                                spawnCalls.add(callDesc);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        assertEquals(1, spawnCalls.size(), "the sender must spawn exactly once: " + spawnCalls);
        assertTrue(spawnCalls.get(0).startsWith("(Lnet/minecraft/server/network/ServerPlayerEntity;"),
                "the broadcast overload feeds every nearby player one packet with one drift, "
                        + "which is the defect this pass exists to fix: " + spawnCalls.get(0));
    }
}
