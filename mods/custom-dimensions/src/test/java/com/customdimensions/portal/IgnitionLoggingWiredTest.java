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
import java.util.LinkedHashSet;
import java.util.Set;

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

    private static final class Uses extends ClassVisitor {
        final Set<String> refusals = new LinkedHashSet<>();
        final Set<String> logCalls = new LinkedHashSet<>();
        final Set<String> scanCalls = new LinkedHashSet<>();
        final Set<String> loggerCalls = new LinkedHashSet<>();

        Uses() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    if (opcode == Opcodes.GETSTATIC && REFUSAL.equals(owner)) {
                        Uses.this.refusals.add(field);
                    }
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String method,
                        String desc, boolean itf) {
                    if (LOG.equals(owner)) {
                        Uses.this.logCalls.add(method);
                    } else if (SCAN.equals(owner)) {
                        Uses.this.scanCalls.add(method);
                    } else if (SLF4J.equals(owner)) {
                        Uses.this.loggerCalls.add(method);
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
