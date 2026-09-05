package com.customdimensions.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handshake version is the release that built this jar, and nobody types
 * it.
 *
 * <p>A hand-maintained constant here has to be edited in two source trees at
 * once or every player is refused, and nothing except a person remembering
 * enforces that. So the number is not written down at all: both sides read
 * their own {@code fabric.mod.json} version, which {@code release.yml} stamps
 * from one release tag.
 *
 * <p>This side also owns the value that goes on the wire, so the constant it
 * must never be is checked at the point of construction: the string handed to
 * {@code Hello} has to arrive from a call, never from a literal or a field.
 */
class HandshakeVersionTest {

    private static final Path CLASSES = Path.of("build", "classes", "java", "main");

    /** Where a typed constant would go, and where none may exist. */
    private static final Set<String> HANDSHAKE_CLASSES = Set.of(
            "com/customdimensions/client/CompanionPayloads",
            "com/customdimensions/client/CompanionPayloads$Hello",
            "com/customdimensions/client/CompanionVersion",
            "com/customdimensions/client/CustomDimensionsClient");

    private static final String SUPPLIER = "com/customdimensions/client/CompanionVersion.current";
    private static final String HANDSHAKE_SITE =
            "com/customdimensions/client/CustomDimensionsClient#sendHelloWhenReady";
    private static final String HELLO = "com/customdimensions/client/CompanionPayloads$Hello";
    private static final String MOD_METADATA = "net/fabricmc/loader/api/metadata/ModMetadata";

    /** {@code owner#method -> callOwner.callName}, one entry per call site. */
    private record Edge(String from, String to) {}

    private record Scan(List<String> versionFields, List<Edge> edges, List<String> typedHellos) {
        boolean calls(String from, String to) {
            return this.edges.stream().anyMatch(e -> e.from().equals(from) && e.to().equals(to));
        }

        boolean anyMethodOf(String owner, String to) {
            return this.edges.stream()
                    .anyMatch(e -> e.from().startsWith(owner + "#") && e.to().equals(to));
        }
    }

    private static Scan scan() throws IOException {
        List<String> versionFields = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<String> typedHellos = new ArrayList<>();
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
                        public FieldVisitor visitField(int access, String name, String descriptor,
                                String signature, Object value) {
                            if ((access & Opcodes.ACC_STATIC) != 0
                                    && (access & Opcodes.ACC_FINAL) != 0
                                    && HANDSHAKE_CLASSES.contains(this.owner)
                                    && name.endsWith("VERSION")) {
                                versionFields.add(this.owner + "." + name + " = " + value);
                            }
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            String from = this.owner + "#" + name;
                            return new MethodVisitor(Opcodes.ASM9) {
                                private String pushed = "nothing";
                                private int line = -1;

                                @Override
                                public void visitLineNumber(int number, org.objectweb.asm.Label at) {
                                    this.line = number;
                                }

                                @Override
                                public void visitLdcInsn(Object value) {
                                    this.pushed = "the constant " + value;
                                }

                                @Override
                                public void visitFieldInsn(int opcode, String fieldOwner,
                                        String fieldName, String fieldDesc) {
                                    this.pushed = "the field " + fieldOwner + "." + fieldName;
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String callOwner,
                                        String callName, String callDesc, boolean isInterface) {
                                    String call = callOwner + "." + callName;
                                    edges.add(new Edge(from, call));
                                    if (HELLO.equals(callOwner) && "<init>".equals(callName)
                                            && !SUPPLIER.equals(this.pushed)) {
                                        typedHellos.add(from + " (line " + this.line + ") builds a "
                                                + "handshake from " + this.pushed);
                                    }
                                    this.pushed = call;
                                }
                            };
                        }
                    }, ClassReader.SKIP_FRAMES);
                }
            }
        }
        return new Scan(versionFields, edges, typedHellos);
    }

    private static Scan compiled() throws IOException {
        assertTrue(Files.isDirectory(CLASSES),
                "compiled classes not found at " + CLASSES.toAbsolutePath()
                        + " — this test reads bytecode, it must never silently skip");
        return scan();
    }

    @Test
    void nothingInTheHandshakeDeclaresAVersionOfItsOwn() throws IOException {
        assertEquals(List.of(), compiled().versionFields(),
                "a hand-maintained version constant is back in the handshake. It exists once per "
                        + "Gradle project, the two copies must agree or every player is refused, "
                        + "and only a person remembering keeps them in step. Read the jar's own "
                        + "version instead: " + SUPPLIER + "()");
    }

    @Test
    void everyHandshakeCarriesTheReleaseThatBuiltThisJar() throws IOException {
        Scan compiled = compiled();
        assertTrue(compiled.calls(HANDSHAKE_SITE, HELLO + ".<init>"),
                HANDSHAKE_SITE + " no longer builds the handshake, so this guard is watching "
                        + "nothing — point it at whatever does");
        assertEquals(List.of(), compiled.typedHellos(),
                "the handshake version was written by hand. It must come straight from "
                        + SUPPLIER + "(), which reads what release.yml stamped into this jar");
    }

    @Test
    void thatVersionComesFromTheJarsOwnMetadata() throws IOException {
        assertTrue(compiled().anyMethodOf("com/customdimensions/client/CompanionVersion",
                        MOD_METADATA + ".getVersion"),
                "CompanionVersion no longer reads the version out of the loader, so what it "
                        + "returns is no longer the release that built this jar");
    }
}
