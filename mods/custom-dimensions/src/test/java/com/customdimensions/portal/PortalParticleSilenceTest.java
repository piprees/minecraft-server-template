package com.customdimensions.portal;

import com.customdimensions.config.ImmersiveSettings;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code particleDensity: 0} means a silent portal on EVERY emitter, not just
 * the two that plan an aperture. Three of the five spawn from a tick path with
 * a {@code ServerWorld} in hand, so their gate is read out of the compiled
 * class — a density fetched and dropped looks like a fix in the diff and puts
 * exactly the same dust on screen. TROUBLESHOOTING.md#t101.
 */
class PortalParticleSilenceTest {

    private static final Path CLASSES = Path.of("build", "classes", "java", "main");
    private static final String APERTURE = "com/customdimensions/portal/PortalAperture";
    private static final String HELPER = "com/customdimensions/portal/PortalHelper";
    private static final String CONFIG = "com/customdimensions/config/MultiverseConfig";
    private static final String SETTINGS = "com/customdimensions/config/ImmersiveSettings";

    private static final ImmersiveSettings SILENT = new ImmersiveSettings(
            true, 12, 6, 4, 24, true, true, 0.0, 0.45, 0.5, true);

    // ------------------------------------------------------------------
    // The switch itself
    // ------------------------------------------------------------------

    @Test
    void zeroDensityEmitsNothingAndTheShippedDefaultStillDoes() {
        assertFalse(PortalAperture.emitsAtAll(0.0), "0 must be silence");
        assertFalse(PortalAperture.emitsAtAll(-1.0), "a negative density is silence, not an error");
        assertFalse(PortalAperture.emitsAtAll(Double.NaN), "NaN clamps to silence");
        assertTrue(PortalAperture.emitsAtAll(PortalAperture.DEFAULT_DENSITY),
                "the stock default must keep emitting");
        assertTrue(PortalAperture.emitsAtAll(ImmersiveSettings.DEFAULT_PARTICLE_DENSITY),
                "the stock default must keep emitting");
        assertTrue(PortalAperture.emitsAtAll(1.0));
    }

    @Test
    void anOpeningPlansNoCellsAtZeroAndPlansSomeAtTheDefault() {
        // Paths 1 and 2 — the aperture pass, source side and arrival side.
        Set<BlockPos> interior = new HashSet<>();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 5; y++) {
                interior.add(new BlockPos(x, 64 + y, 0));
            }
        }
        int atDefault = 0;
        for (long tick = 0; tick < 60; tick++) {
            assertTrue(PortalAperture.emittingCells(interior, Direction.Axis.X, tick,
                            SILENT.particleDensity(), SILENT.edgeBias()).isEmpty(),
                    "a zero-density opening planned cells on tick " + tick);
            atDefault += PortalAperture.emittingCells(interior, Direction.Axis.X, tick,
                    ImmersiveSettings.DEFAULTS.particleDensity(),
                    ImmersiveSettings.DEFAULTS.edgeBias()).size();
        }
        assertTrue(atDefault > 0, "the stock default stopped emitting");
    }

    @Test
    void theDefaultsRecordStillCarriesTheShippedDensity() {
        assertEquals(0.35, ImmersiveSettings.DEFAULTS.particleDensity(), 1e-9,
                "a stock server must look exactly as it did");
        assertEquals(0.35, PortalAperture.DEFAULT_DENSITY, 1e-9);
    }

    // ------------------------------------------------------------------
    // The three tick-path emitters, read out of the compiled classes
    // ------------------------------------------------------------------

    @Test
    void arrivalCellsAndTheirFramesAskTheStandingDimensionsDensity() throws IOException {
        // Paths 3 and 4: one dust per registered arrival cell, and one per
        // frame block of an arrival this mod built — both every tick, both
        // reading no settings at all before this gate existed.
        Calls calls = read(HELPER + ".class");

        String gate = "looseArrivalParticles";
        Set<String> made = calls.byMethod.get(gate);
        assertFalse(made == null, "PortalHelper has no " + gate + " at all");
        assertTrue(made.contains(CONFIG + ".getImmersiveFor"),
                gate + " must read the standing dimension's live config: " + made);
        assertTrue(made.contains(SETTINGS + ".particleDensity"),
                gate + " never reads particleDensity: " + made);
        assertTrue(made.contains(APERTURE + ".emitsAtAll"),
                gate + " must use the same switch as the opening: " + made);

        Set<String> pass = calls.byMethod.get("spawnTargetPortalParticles");
        assertTrue(pass != null && pass.contains(HELPER + "." + gate),
                "the loose particle pass never consults " + gate + ": " + pass);
        assertBranchedOn(calls.after.get(HELPER + "." + gate), gate);
    }

    @Test
    void theFrameRingAsksTheDensityBeforeItDrawsAnything() throws IOException {
        // Path 5: the ring on a source zone's frame, gated only on `projecting`
        // before this — so a still frame and a live projection could not be had
        // separately, and a silenced opening kept its border.
        Calls calls = read("com/customdimensions/immersive/ImmersiveProjector.class");

        Set<String> made = calls.byMethod.get("spawnEdgeParticles");
        assertTrue(made != null && made.contains(SETTINGS + ".particleDensity"),
                "spawnEdgeParticles never reads particleDensity: " + made);
        assertTrue(made.contains(APERTURE + ".emitsAtAll"),
                "spawnEdgeParticles must use the same switch as the opening: " + made);
        assertBranchedOn(calls.after.get(APERTURE + ".emitsAtAll"), "spawnEdgeParticles");
    }

    /** A verdict computed and discarded reads as a fix and behaves like the bug. */
    private static void assertBranchedOn(List<Integer> opcodes, String where) {
        assertFalse(opcodes == null || opcodes.isEmpty(),
                where + "'s verdict is never reached at all");
        for (int opcode : opcodes) {
            assertTrue(opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE,
                    where + "'s verdict is thrown away rather than branched on (opcode "
                            + opcode + ")");
        }
    }

    // ------------------------------------------------------------------

    /** Which calls each method makes, and what happens to each call's result. */
    private static final class Calls extends ClassVisitor {
        final Map<String, Set<String>> byMethod = new LinkedHashMap<>();
        final Map<String, List<Integer>> after = new LinkedHashMap<>();

        Calls() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            Set<String> calls = this.byMethod.computeIfAbsent(name, k -> new LinkedHashSet<>());
            return new MethodVisitor(Opcodes.ASM9) {
                private String pending;

                private void settle(int opcode) {
                    if (this.pending != null) {
                        Calls.this.after.computeIfAbsent(this.pending, k -> new ArrayList<>())
                                .add(opcode);
                        this.pending = null;
                    }
                }

                @Override
                public void visitInsn(int opcode) {
                    settle(opcode);
                }

                @Override
                public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                    settle(opcode);
                }

                @Override
                public void visitVarInsn(int opcode, int slot) {
                    settle(opcode);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    settle(opcode);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String method,
                        String desc, boolean itf) {
                    settle(opcode);
                    calls.add(owner + "." + method);
                    if (APERTURE.equals(owner) && "emitsAtAll".equals(method)) {
                        this.pending = APERTURE + ".emitsAtAll";
                    } else if (HELPER.equals(owner) && desc.endsWith(")Z")) {
                        this.pending = HELPER + "." + method;
                    }
                }
            };
        }
    }

    private static Calls read(String classFile) throws IOException {
        Path path = CLASSES.resolve(classFile);
        assertTrue(Files.isRegularFile(path),
                "compile the mod before running this: " + path.toAbsolutePath());
        Calls calls = new Calls();
        try (InputStream in = Files.newInputStream(path)) {
            new ClassReader(in).accept(calls, ClassReader.SKIP_FRAMES);
        }
        return calls;
    }
}
