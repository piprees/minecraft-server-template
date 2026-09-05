package com.customdimensions.immersive;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of the crossing seam, each pinned where a behaviour test
 * cannot reach: an injection point, and one call inside a method that needs a
 * live server to run.
 *
 * <p>Measured before either was in place: an arrow was drawn by neither world
 * for 3.7 to 5.3 server ticks at the plane.
 *
 * <p><b>Half one, the scan's position.</b> {@code ServerWorld.tick} sends
 * tracker updates in its {@code chunkSource} block and moves entities near its
 * end, so from {@code @At("HEAD")} the swept test in
 * {@link EntityPassthrough#crossedInterior} reads the PREVIOUS tick's step and
 * every crossing teleports a tick after it happened.
 *
 * <p><b>Half two, the handover.</b> {@code Entity.teleportTo} destroys the
 * source entity and builds a different one with an unrelated network id, and
 * the destination feed does not see the new one for up to
 * {@code DestinationEntityFeed.INTERVAL} ticks. That correspondence stops
 * existing before the next snapshot, so it is named at the crossing or it is
 * lost — see {@code docs/design/immersive-portals.md} Part 3.
 */
class CrossingSeamTest {

    private static final Path CLASSES = Path.of("build", "classes", "java", "main");
    private static final String MIXIN = "com/customdimensions/mixin/ServerWorldMixin";
    private static final String PASSTHROUGH = "com/customdimensions/immersive/EntityPassthrough";
    private static final String FEED = "com/customdimensions/companion/DestinationEntityFeed";

    /** One compiled method: what it calls, and the injection points it declares. */
    private record Compiled(List<String> calls, List<String> injectionPoints) {}

    private static Map<String, Compiled> methodsOf(String internalName) throws IOException {
        Path file = CLASSES.resolve(internalName + ".class");
        assertTrue(Files.isRegularFile(file),
                "compiled class not found at " + file.toAbsolutePath()
                        + " — this test reads bytecode, it must never silently skip");
        Map<String, Compiled> out = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    List<String> calls = new ArrayList<>();
                    List<String> points = new ArrayList<>();
                    out.put(name, new Compiled(calls, points));
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String callName,
                                String callDesc, boolean isInterface) {
                            calls.add(owner + "#" + callName);
                        }

                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            if (!desc.endsWith("/injection/Inject;")) {
                                return null;
                            }
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public AnnotationVisitor visitArray(String member) {
                                    if (!"at".equals(member)) {
                                        return null;
                                    }
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public AnnotationVisitor visitAnnotation(String name, String at) {
                                            return new AnnotationVisitor(Opcodes.ASM9) {
                                                @Override
                                                public void visit(String key, Object value) {
                                                    if ("value".equals(key)) {
                                                        points.add(String.valueOf(value));
                                                    }
                                                }
                                            };
                                        }
                                    };
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return out;
    }

    @Test
    void theCrossingScanRunsAfterThisTicksEntityMovement() throws IOException {
        Map<String, Compiled> mixin = methodsOf(MIXIN);
        List<String> callers = mixin.entrySet().stream()
                .filter(e -> e.getValue().calls().contains(PASSTHROUGH + "#tick"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        assertEquals(1, callers.size(),
                "EntityPassthrough.tick must be driven from exactly one injection in "
                        + "ServerWorldMixin, and it is driven from " + callers);

        List<String> points = mixin.get(callers.get(0)).injectionPoints();
        assertEquals(List.of("TAIL"), points,
                "the crossing scan is injected at " + points + ". At HEAD the swept test reads "
                        + "prevX/getX from the PREVIOUS tick, because ServerWorld.tick moves "
                        + "entities near its end — so every crossing teleports a tick late and "
                        + "the entity is drawn flying on past the opening.");
    }

    @Test
    void everyCrossingNamesItselfToTheViewersDrawingTheFarSide() throws IOException {
        Map<String, Compiled> passthrough = methodsOf(PASSTHROUGH);
        List<String> namers = passthrough.entrySet().stream()
                .filter(e -> e.getValue().calls().contains(FEED + "#handover"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        assertEquals(List.of("passThrough"), namers,
                "the source-side crossing must name the old id, the new id and the tick to every "
                        + "viewer drawing that destination, and only it should. A snapshot cannot "
                        + "recover the correspondence: by the next pass the source entity is gone "
                        + "and the arrival carries an unrelated network id. Namers: " + namers);
    }
}
