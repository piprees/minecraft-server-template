package com.customdimensions.client.render;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layer the destination is drawn on, and the light it carries there.
 *
 * <p>The choice is tested as a pure function: a test that touches {@code
 * RenderLayer} dies in its static initialiser. That it is actually WIRED is
 * read out of the compiled classes — a switch computed and dropped looks like
 * a fix in the diff and draws exactly the same pixels.
 */
class UnshadedDestinationTest {

    private static final Path CLASSES = Path.of("build", "classes", "java", "main");
    private static final String LAYERS = "com/customdimensions/client/render/PortalRenderLayers";
    private static final String RENDERER = "com/customdimensions/client/render/ProjectionRenderer";

    // ------------------------------------------------------------------
    // The choice
    // ------------------------------------------------------------------

    @Test
    void offEveryCapturedFamilyKeepsItsEntityLayer() {
        assertEquals(UnshadedDestination.Target.ENTITY_SOLID,
                UnshadedDestination.of(UnshadedDestination.Captured.SOLID, false));
        assertEquals(UnshadedDestination.Target.ENTITY_CUTOUT_NO_CULL,
                UnshadedDestination.of(UnshadedDestination.Captured.CUTOUT, false));
        assertEquals(UnshadedDestination.Target.ENTITY_TRANSLUCENT_CULL,
                UnshadedDestination.of(UnshadedDestination.Captured.TRANSLUCENT, false));
    }

    @Test
    void onNothingIsLeftOnAnEntityLayer() {
        for (UnshadedDestination.Captured captured : UnshadedDestination.Captured.values()) {
            UnshadedDestination.Target target = UnshadedDestination.of(captured, true);
            assertNotEquals(UnshadedDestination.Target.ENTITY_SOLID, target, captured.toString());
            assertNotEquals(UnshadedDestination.Target.ENTITY_CUTOUT_NO_CULL, target,
                    captured.toString());
            assertNotEquals(UnshadedDestination.Target.ENTITY_TRANSLUCENT_CULL, target,
                    captured.toString());
        }
    }

    @Test
    void onSolidIsOpaqueAndTheAlphaFamiliesAreBlended() {
        assertEquals(UnshadedDestination.Target.UNSHADED_OPAQUE,
                UnshadedDestination.of(UnshadedDestination.Captured.SOLID, true));
        assertEquals(UnshadedDestination.Target.UNSHADED_BLENDED,
                UnshadedDestination.of(UnshadedDestination.Captured.CUTOUT, true));
        assertEquals(UnshadedDestination.Target.UNSHADED_BLENDED,
                UnshadedDestination.of(UnshadedDestination.Captured.TRANSLUCENT, true));
    }

    @Test
    void theBackdropFollowsTheSameSwitch() {
        assertEquals(UnshadedDestination.Target.ENTITY_BACKDROP,
                UnshadedDestination.backdrop(false));
        assertEquals(UnshadedDestination.Target.UNSHADED_BACKDROP,
                UnshadedDestination.backdrop(true));
    }

    /** One flip has to move both halves of the opening, or an A/B measures a mixture. */
    @Test
    void neitherHalfIsLeftOnAnEntityLayerWhenTheSwitchIsOn() {
        Set<UnshadedDestination.Target> shaded = Set.of(
                UnshadedDestination.Target.ENTITY_SOLID,
                UnshadedDestination.Target.ENTITY_CUTOUT_NO_CULL,
                UnshadedDestination.Target.ENTITY_TRANSLUCENT_CULL,
                UnshadedDestination.Target.ENTITY_BACKDROP);
        for (UnshadedDestination.Captured captured : UnshadedDestination.Captured.values()) {
            assertFalse(shaded.contains(UnshadedDestination.of(captured, true)),
                    captured.toString());
        }
        assertFalse(shaded.contains(UnshadedDestination.backdrop(true)));
    }

    // ------------------------------------------------------------------
    // The light it carries instead of the lightmap
    // ------------------------------------------------------------------

    /** The whole point: an unlit layer that draws everything full-bright is a different defect. */
    @Test
    void aDarkCellReadsDarkAndALitCellReadsBright() {
        float dark = UnshadedDestination.scale(0, 0, 0.0f);
        float lit = UnshadedDestination.scale(15, 15, 0.0f);
        assertTrue(dark < 0.05f, "a cave with no light must not read full-bright: " + dark);
        assertEquals(1.0f, lit, 0.0001f);
        assertTrue(lit > dark * 4.0f, "level 15 must be far brighter than level 0");
    }

    @Test
    void theBrighterChannelWins() {
        assertEquals(UnshadedDestination.scale(15, 0, 0.0f),
                UnshadedDestination.scale(0, 15, 0.0f), 0.0001f);
        assertEquals(UnshadedDestination.scale(15, 15, 0.0f),
                UnshadedDestination.scale(15, 0, 0.0f), 0.0001f);
    }

    @Test
    void itIsMonotonicAndBoundedAcrossEveryLevelAndAmbient() {
        for (float ambient : new float[] {0.0f, 0.1f, 0.5f, 1.0f}) {
            float previous = -1.0f;
            for (int level = 0; level <= 15; level++) {
                float value = UnshadedDestination.scale(level, 0, ambient);
                assertTrue(value >= previous, "level " + level + " at ambient " + ambient);
                assertTrue(value >= 0.0f && value <= 1.0f, "out of range at level " + level);
                previous = value;
            }
        }
    }

    /** A level off either end is the end, not a colour multiplier outside 0..1. */
    @Test
    void levelsOutsideTheRangeClamp() {
        assertEquals(UnshadedDestination.scale(0, 0, 0.0f),
                UnshadedDestination.scale(-4, -4, 0.0f), 0.0001f);
        assertEquals(UnshadedDestination.scale(15, 15, 0.0f),
                UnshadedDestination.scale(99, 99, 0.0f), 0.0001f);
    }

    /** The lift already put the levels in source space, so the ambient floor still applies. */
    @Test
    void anAmbientFloorLiftsTheDarkEnd() {
        assertTrue(UnshadedDestination.scale(0, 0, 0.15f) > UnshadedDestination.scale(0, 0, 0.0f));
    }

    // ------------------------------------------------------------------
    // Wired, not merely computed
    // ------------------------------------------------------------------

    @Test
    void theLayerChooserAsksForTheChoiceAndCanAnswerWithEitherUnshadedLayer() {
        Set<String> calls = calls(LAYERS);
        assertTrue(calls.contains("com/customdimensions/client/render/UnshadedDestination.of"),
                "forDestination must route through the choice: " + calls);
        assertTrue(calls.contains("com/customdimensions/client/render/UnshadedDestination.backdrop"),
                "backdrop must route through the choice too: " + calls);
        Set<String> fields = fields(LAYERS);
        assertTrue(fields.contains("UNSHADED_OPAQUE"), fields.toString());
        assertTrue(fields.contains("UNSHADED_BLENDED"), fields.toString());
        assertTrue(fields.contains("UNSHADED_BACKDROP"), fields.toString());
    }

    /** The flag has to REACH the backdrop's layer choice, not just exist beside it. */
    @Test
    void theBackdropDrawPassesTheSwitchToTheLayerChoice() {
        Set<String> calls = new LinkedHashSet<>();
        for (Path file : innerClasses(RENDERER)) {
            calls.addAll(callsIn(file));
        }
        assertTrue(calls.contains("com/customdimensions/client/render/PortalRenderLayers.backdrop"
                        + "(Z)Lnet/minecraft/client/render/RenderLayer;"),
                "the backdrop layer must be chosen from the switch: "
                        + calls.size() + " calls scanned");
    }

    @Test
    void theDrawPathReadsTheSwitchAndBakesTheLight() {
        Set<String> calls = new LinkedHashSet<>();
        for (Path file : innerClasses(RENDERER)) {
            calls.addAll(callsIn(file));
        }
        assertTrue(calls.stream().anyMatch(c -> c.endsWith(".apertureUnshadedDestination")),
                "the draw path must read the switch: " + calls.size() + " calls scanned");
        assertTrue(calls.contains("com/customdimensions/client/render/UnshadedDestination.scale"),
                "the unshaded emit must multiply the destination's own light in");
    }

    // ------------------------------------------------------------------
    // The fog the destination fades toward
    // ------------------------------------------------------------------

    /** Same fallback rule as the backdrop's own colour, or the two disagree. */
    @Test
    void theFogColourFollowsTheBackdropsOwnFallback() {
        assertArrayEquals(new float[] {1.0f, 0.5f, 0.0f},
                UnshadedDestination.fogColour(0xFF8000, 0x000000, 1.0), 0.004f);
        assertArrayEquals(new float[] {0.0f, 0.0f, 1.0f},
                UnshadedDestination.fogColour(-1, 0x0000FF, 1.0), 0.004f);
        assertNull(UnshadedDestination.fogColour(-1, -1, 1.0),
                "no declared colour must leave the source world's fog alone");
    }

    /** It converges with the backdrop, so it takes the same attenuation. */
    @Test
    void theFogColourTakesTheSameGainAndClampsWithIt() {
        assertArrayEquals(new float[] {0.5f, 0.25f, 0.0f},
                UnshadedDestination.fogColour(0xFF8000, -1, 0.5), 0.004f);
        assertArrayEquals(new float[] {0.0f, 0.0f, 0.0f},
                UnshadedDestination.fogColour(0xFF8000, -1, 0.0), 0.0f);
        assertArrayEquals(UnshadedDestination.fogColour(0xFF8000, -1, 1.0),
                UnshadedDestination.fogColour(0xFF8000, -1, 9.0), 0.0f);
    }

    /** Compensation is only compensation if the draw actually reads it. */
    @Test
    void theDrawPathBindsTheDestinationsOwnFogAndPutsTheSourcesBack() {
        Set<String> calls = new LinkedHashSet<>();
        for (Path file : innerClasses(RENDERER)) {
            calls.addAll(callsIn(file));
        }
        assertTrue(calls.contains("com/customdimensions/client/render/UnshadedDestination.fogColour"),
                "the pass must resolve the destination's own fog: " + calls.size() + " scanned");
        assertTrue(calls.contains("com/mojang/blaze3d/systems/RenderSystem.setShaderFogColor"),
                "and bind it");
        assertTrue(calls.contains("com/mojang/blaze3d/systems/RenderSystem.getShaderFogColor"),
                "reading the source's first is what allows it to be put back");
    }

    @Test
    void theBackdropDrawReadsTheGain() {
        Set<String> calls = new LinkedHashSet<>();
        for (Path file : innerClasses(RENDERER)) {
            calls.addAll(callsIn(file));
        }
        assertTrue(calls.stream().anyMatch(c -> c.endsWith(".apertureBackdropGain")),
                "the backdrop draw must read the gain: " + calls.size() + " calls scanned");
    }

    // ------------------------------------------------------------------

    private static Set<String> calls(String internalName) {
        return callsIn(CLASSES.resolve(internalName + ".class"));
    }

    private static Set<String> callsIn(Path file) {
        Set<String> found = new LinkedHashSet<>();
        read(file, new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                            String descriptor, boolean isInterface) {
                        found.add(owner + "." + method);
                        found.add(owner + "." + method + descriptor);
                    }
                };
            }
        });
        return found;
    }

    private static Set<String> fields(String internalName) {
        Set<String> found = new LinkedHashSet<>();
        read(CLASSES.resolve(internalName + ".class"), new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field,
                            String descriptor) {
                        found.add(field);
                    }
                };
            }
        });
        return found;
    }

    /** The class and every anonymous class inside it — the pass is one of those. */
    private static java.util.List<Path> innerClasses(String internalName) {
        Path directory = CLASSES.resolve(internalName).getParent();
        String prefix = Path.of(internalName).getFileName().toString();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().startsWith(prefix)
                    && file.getFileName().toString().endsWith(".class")).toList();
        } catch (IOException e) {
            throw new AssertionError("no compiled classes under " + directory, e);
        }
    }

    private static void read(Path file, ClassVisitor visitor) {
        assertTrue(Files.exists(file), "compile the mod first: " + file.toAbsolutePath());
        try (InputStream in = Files.newInputStream(file)) {
            new ClassReader(in).accept(visitor, ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new AssertionError("unreadable class " + file, e);
        }
    }
}
