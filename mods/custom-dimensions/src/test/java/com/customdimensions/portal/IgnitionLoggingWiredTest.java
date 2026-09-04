package com.customdimensions.portal;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reason has to reach the log, and nothing inside a mixin can be reached
 * by a unit test — so this reads the compiled class instead. A reason that is
 * computed and dropped is the failure this whole change exists to end, and
 * dropping it is a one-line edit that no assertion over pure logic can see.
 */
class IgnitionLoggingWiredTest {

    private static final Path MIXIN = Path.of("build", "classes", "java", "main",
            "com", "customdimensions", "mixin", "PortalIgnitionMixin.class");

    private static final String REFUSAL = "com/customdimensions/portal/IgnitionRefusal";
    private static final String LOG = "com/customdimensions/portal/IgnitionLog";
    private static final String SCAN = "com/customdimensions/portal/IgnitionScan";
    private static final String SLF4J = "org/slf4j/Logger";
    private static final Path LOG_CLASS = Path.of("build", "classes", "java", "main",
            "com", "customdimensions", "portal", "IgnitionLog.class");

    private static final String HELPER = "com/customdimensions/portal/PortalHelper";

    private static final class Uses extends ClassVisitor {
        final Set<String> refusals = new LinkedHashSet<>();
        final Set<String> logCalls = new LinkedHashSet<>();
        final Set<String> scanCalls = new LinkedHashSet<>();
        final Set<String> loggerCalls = new LinkedHashSet<>();
        final Set<String> decrementCalls = new LinkedHashSet<>();
        /** Call SITES. A set of names collapses N of them into one entry. */
        int decrementSites;
        /** Opcodes seen immediately after a PortalHelper.registerZone call. */
        final List<Integer> afterRegisterZone = new ArrayList<>();

        Uses() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                private boolean justRegisteredZone;

                private void settle(int opcode) {
                    if (this.justRegisteredZone) {
                        Uses.this.afterRegisterZone.add(opcode);
                        this.justRegisteredZone = false;
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    settle(opcode);
                    if (opcode == Opcodes.GETSTATIC && REFUSAL.equals(owner)) {
                        Uses.this.refusals.add(field);
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
                public void visitMethodInsn(int opcode, String owner, String method,
                        String desc, boolean itf) {
                    settle(opcode);
                    if (LOG.equals(owner)) {
                        Uses.this.logCalls.add(method);
                    } else if (SCAN.equals(owner)) {
                        Uses.this.scanCalls.add(method);
                    } else if (SLF4J.equals(owner)) {
                        Uses.this.loggerCalls.add(method);
                    } else if ("decrement".equals(method)) {
                        Uses.this.decrementCalls.add(owner + "." + method);
                        Uses.this.decrementSites++;
                    }
                    if (HELPER.equals(owner) && "registerZone".equals(method)) {
                        this.justRegisteredZone = true;
                    }
                }
            };
        }
    }

    private static Uses read(Path classFile) throws IOException {
        assertTrue(Files.exists(classFile),
                "compile the mod before running this: " + classFile.toAbsolutePath());
        Uses uses = new Uses();
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(uses, ClassReader.SKIP_FRAMES);
        }
        return uses;
    }

    private static Uses readMixin() throws IOException {
        return read(MIXIN);
    }

    @Test
    void everyExitTheMixinOwnsNamesItsReason() throws IOException {
        Uses uses = readMixin();

        for (String reason : new String[]{"CLIENT_WORLD", "NOT_SERVER_WORLD", "NO_IGNITER_MATCH"}) {
            assertTrue(uses.refusals.contains(reason),
                    "PortalIgnitionMixin returns without naming " + reason
                    + "; it names " + uses.refusals);
        }
    }

    @Test
    void theMixinHandsEveryRefusalToTheLog() throws IOException {
        Uses uses = readMixin();

        assertTrue(uses.logCalls.contains("refusedEarly"),
                "the three exits before any candidate is tried log nothing; calls: " + uses.logCalls);
        assertTrue(uses.logCalls.contains("refused"),
                "a candidate that declines logs nothing; calls: " + uses.logCalls);
    }

    @Test
    void theMixinAsksForTheReasonRatherThanJustTheSite() throws IOException {
        Uses uses = readMixin();

        assertTrue(uses.scanCalls.contains("sweepDetailed"),
                "the mixin must take the sweep's reason, not only its site; calls: " + uses.scanCalls);
        assertFalse(uses.scanCalls.contains("sweep"),
                "plain sweep() throws the reason away — that is the defect, not the fix");
    }

    @Test
    void theMixinReadsWhetherTheZoneWasActuallyRegistered() throws IOException {
        // registerZone answers false for a frame that is already a portal.
        // Discarding that answer is what let a re-light report SUCCESS, play
        // the sound and eat the igniter while registering nothing — and in
        // bytecode, discarding a boolean return is exactly a POP.
        Uses uses = readMixin();

        assertFalse(uses.afterRegisterZone.isEmpty(),
                "PortalIgnitionMixin no longer calls PortalHelper.registerZone at all");
        for (int opcode : uses.afterRegisterZone) {
            assertTrue(opcode != Opcodes.POP && opcode != Opcodes.POP2,
                    "registerZone's answer is thrown away; the mixin must branch on it");
        }
    }

    @Test
    void relightingAnAlreadyLitFrameNamesItsReason() throws IOException {
        Uses uses = readMixin();

        assertTrue(uses.refusals.contains("ALREADY_LIT"),
                "a re-light registers nothing and must say so; the mixin names " + uses.refusals);
    }

    @Test
    void theIgniterIsSpentInExactlyOnePlace() throws IOException {
        // The one decrement belongs to IgniterSpend.CONSUME, behind the
        // registered-or-not branch. A second one is how destroying a
        // damageable igniter creeps back in, so count SITES: a set of callee
        // names collapses two decrements on ItemStack into a single entry and
        // reports the regression as a pass.
        Uses uses = readMixin();

        assertEquals(1, uses.decrementSites,
                "expected one ItemStack.decrement call site in the ignition path, found "
                + uses.decrementSites + " across " + uses.decrementCalls);
    }

    @Test
    void theLogActuallyEmitsAtBothLevels() throws IOException {
        // Building a line and dropping it is the same silence with more code
        // in front of it, and it is a one-line edit no assertion over the
        // line's TEXT can see.
        Uses uses = read(LOG_CLASS);

        assertTrue(uses.loggerCalls.contains("info"),
                "a deliberate refusal never reaches a default log; slf4j calls: " + uses.loggerCalls);
        assertTrue(uses.loggerCalls.contains("debug"),
                "a passing click's refusal never reaches a debug log; slf4j calls: " + uses.loggerCalls);
        assertTrue(uses.loggerCalls.contains("isDebugEnabled"),
                "every right-click would pay for a line nobody reads; slf4j calls: " + uses.loggerCalls);
    }
}
