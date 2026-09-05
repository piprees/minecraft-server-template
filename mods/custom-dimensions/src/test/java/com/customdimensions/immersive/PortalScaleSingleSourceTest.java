package com.customdimensions.immersive;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A portal's travel scale is read in exactly ONE place, and every teleport and
 * preview goes through {@link ImmersiveProjector#mappingFor}.
 *
 * <p>{@code PortalDefinition.getScale()} is one side's number. Applied in a
 * fixed direction it is right entering a scaled dimension and wrong leaving
 * one, and the two answers are {@code scale^2} apart — 3,882 blocks on the
 * measured Nether pair in {@link PortalScaleDirectionTest}. A second copy of
 * the expression is therefore not a duplicate but a divergence: the portal
 * shows one place and puts you in another.
 *
 * <p>{@link PortalScaleDirectionTest} pins what the transform computes; this
 * pins that nothing computes it twice. A behaviour test cannot see a copy
 * inside a mixin, which is where two of them lived.
 */
class PortalScaleSingleSourceTest {

    private static final Path CLASSES = Path.of("build", "classes", "java", "main");
    private static final String PORTAL_DEFINITION = "com/customdimensions/config/PortalDefinition";

    /**
     * The sanctioned reader, and why: it asks each SIDE for its own scale
     * rather than applying the definition's in a fixed direction. Adding an
     * entry here should be an argument, not a convenience.
     */
    private static final Map<String, String> ALLOWED_READERS = Map.of(
            "com/customdimensions/immersive/ImmersiveProjector#scaleOf",
            "one side's scale, chosen per side; mappingFor divides source by target");

    private record Caller(String owner, String method, int line) {
        String node() {
            return owner + "#" + method;
        }

        @Override
        public String toString() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "." + method + " (line " + line + ")";
        }
    }

    /** Every compiled method that invokes {@code PortalDefinition.getScale()}. */
    private static List<Caller> scaleReaders() throws IOException {
        List<Caller> found = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(CLASSES)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        private String owner;

                        @Override
                        public void visit(int version, int access, String name, String signature,
                                String superName, String[] interfaces) {
                            this.owner = name;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            String enclosing = this.owner;
                            return new MethodVisitor(Opcodes.ASM9) {
                                private int line = -1;

                                @Override
                                public void visitLineNumber(int lineNumber, org.objectweb.asm.Label start) {
                                    this.line = lineNumber;
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String callOwner,
                                        String callName, String callDesc, boolean isInterface) {
                                    if (PORTAL_DEFINITION.equals(callOwner)
                                            && "getScale".equals(callName)
                                            && "()D".equals(callDesc)) {
                                        found.add(new Caller(enclosing, name, this.line));
                                    }
                                }
                            };
                        }
                    }, ClassReader.SKIP_FRAMES);
                }
            }
        }
        return found;
    }

    @Test
    void theTravelScaleIsReadInExactlyOnePlace() throws IOException {
        assertTrue(Files.isDirectory(CLASSES),
                "compiled classes not found at " + CLASSES.toAbsolutePath()
                        + " — this test reads bytecode, it must never silently skip");

        List<Caller> readers = scaleReaders();
        assertTrue(!readers.isEmpty(),
                "no reader of PortalDefinition.getScale() at all — the method or its owner has moved, "
                        + "and this guard is now watching nothing");

        List<String> unsanctioned = readers.stream()
                .filter(c -> !ALLOWED_READERS.containsKey(c.node()))
                .map(Caller::toString)
                .distinct()
                .sorted()
                .toList();

        assertEquals(List.of(), unsanctioned,
                "a second copy of the travel-scale transform. Every teleport and preview must call "
                        + "ImmersiveProjector.mappingFor, which asks each side for its own scale; "
                        + "reading def.getScale() here applies one side's number in a fixed "
                        + "direction and is wrong leaving a scaled dimension. Offenders: "
                        + unsanctioned);
    }
}
