package com.customdimensions.mixin.compat;

import com.customdimensions.compat.CarveBounds;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Better Caves carves to a {@code bottomY} its datapack states and nothing
 * clamps ([T84]). The carver configs ship -63; a dimension built on nether or
 * End settings has a floor of 0, so every dig below it indexes
 * {@code CarvingMask}'s BitSet negatively and throws, failing the chunk's whole
 * carver step.
 *
 * <h2>Measured from the shipped artefacts and one boot</h2>
 * <ul>
 *   <li>{@code YungsBetterCaves-1.21.1-Fabric-3.1.5.jar},
 *       {@code CaveCarver$Builder.fromConfig} offsets 202-215:
 *       {@code bottomY}/{@code topY} are read straight off the config record
 *       with no reference to the level.</li>
 *   <li>{@code AbstractCarver.carveBlock} offset 14 is the mask write, the
 *       method's first call — ahead of the replaceable test and the
 *       aquifer.</li>
 *   <li>{@code better_cave.json}: every one of the four carvers declares
 *       {@code bottom_y: -63}.</li>
 *   <li>One boot's {@code latest.log}: 260 throws over 22 distinct bit
 *       indices, decoding to 14-48 blocks below the chunk floor, and 130
 *       {@code Error upgrading chunk … to "minecraft:carvers"}.</li>
 * </ul>
 */
class BetterCavesCarveFloorTest {

    private static final String MIXIN_CLASS =
            "com/customdimensions/mixin/compat/BetterCavesCarveFloorMixin";
    private static final String TARGET =
            "com.yungnickyoung.minecraft.bettercaves.worldgen.carver.AbstractCarver";

    /**
     * The descriptor Mixin matches against the target. It is INTERMEDIARY and
     * unremapped on purpose — the owner is another mod, so nothing here goes
     * through the refmap, and a Yarn name would match nothing at runtime.
     */
    private static final String CARVE_BLOCK =
            "carveBlock(Lcom/yungnickyoung/minecraft/bettercaves/worldgen/"
                    + "BetterCavesWorldCarverConfig;Lnet/minecraft/class_2791;Lnet/minecraft/class_2338;"
                    + "Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;Lnet/minecraft/class_6643;"
                    + "Lnet/minecraft/class_6350;)V";

    // ------------------------------------------------------------- the rule

    @Test
    void aBlockInsideTheLevelIsCarvableAndTheEndsAreHalfOpen() {
        assertTrue(CarveBounds.carvable(-64, -64, 320), "the floor itself is a real block");
        assertTrue(CarveBounds.carvable(319, -64, 320), "topY - 1 is the highest real block");
        assertTrue(CarveBounds.carvable(0, -64, 320));

        assertFalse(CarveBounds.carvable(-65, -64, 320), "one below the floor is outside");
        assertFalse(CarveBounds.carvable(320, -64, 320),
                "topY is EXCLUSIVE — vanilla's height limit is [bottomY, topY), and treating it as "
                        + "inclusive walks one block past the top section");
    }

    @Test
    void everyDepthMeasuredInTheLogIsRefused() {
        // A nether/End-settings dimension: floor 0, height 128. The carver
        // descends toward its configured -63, and these are the offsets the
        // 22 distinct bit indices decoded to.
        int bottomY = 0;
        int topY = 128;
        for (int depth : new int[] {14, 15, 16, 18, 22, 23, 24, 27, 28, 29, 30, 31, 33, 35, 40, 41,
                43, 47, 48}) {
            assertFalse(CarveBounds.carvable(bottomY - depth, bottomY, topY),
                    "y = " + (bottomY - depth) + " is " + depth + " blocks below the floor and was "
                            + "carved anyway — that is the negative bit index in T84");
        }
    }

    @Test
    void theOverworldCarverRangeIsUntouched() {
        // The whole configured range, against the floor the config was written
        // for. A guard that refused any of these would cost real caves.
        for (int y = -63; y <= 80; y++) {
            assertTrue(CarveBounds.carvable(y, -64, 320),
                    "y = " + y + " is inside a standard overworld and must still carve");
        }
    }

    // -------------------------------------------------------- the injection

    @Test
    void theMixinIsPseudoTargetsAbstractCarverAndDoesNotRemap() throws IOException {
        ClassNode mixin = read(MIXIN_CLASS);
        List<AnnotationNode> annotations = annotationsOf(mixin);

        assertTrue(annotations.stream()
                        .anyMatch(a -> "Lorg/spongepowered/asm/mixin/Pseudo;".equals(a.desc)),
                "the mixin must be @Pseudo: AbstractCarver belongs to Better Caves and is not on "
                        + "the compile classpath");

        AnnotationNode mixinAnnotation = annotations.stream()
                .filter(a -> "Lorg/spongepowered/asm/mixin/Mixin;".equals(a.desc))
                .findFirst().orElse(null);
        assertNotNull(mixinAnnotation, "no @Mixin annotation");
        assertEquals(List.of(TARGET), value(mixinAnnotation, "targets"),
                "the mixin must target exactly " + TARGET + " — the one class both CaveCarver and "
                        + "CavernCarver route their block writes through");
        assertEquals(Boolean.FALSE, value(mixinAnnotation, "remap"),
                "remap must be false; a third-party target has no refmap entry");
    }

    @Test
    void theInjectNamesTheSevenArgCarveBlockAtHeadAndIsRequired() throws IOException {
        MethodNode handler = null;
        for (MethodNode m : read(MIXIN_CLASS).methods) {
            if (m.name.startsWith("customdimensions$")) {
                handler = m;
            }
        }
        assertNotNull(handler, "no injector handler on the mixin");

        List<AnnotationNode> onHandler = new ArrayList<>();
        if (handler.invisibleAnnotations != null) {
            onHandler.addAll(handler.invisibleAnnotations);
        }
        if (handler.visibleAnnotations != null) {
            onHandler.addAll(handler.visibleAnnotations);
        }
        AnnotationNode inject = onHandler.stream()
                .filter(a -> "Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc))
                .findFirst().orElse(null);
        assertNotNull(inject, "the handler carries no @Inject");
        assertEquals(List.of(CARVE_BLOCK), value(inject, "method"),
                "the injector must name the SEVEN-argument carveBlock by its full intermediary "
                        + "descriptor. Targeting by bare name matches the six-argument overload "
                        + "too, which only delegates and carries no mask write");
        assertEquals(Boolean.TRUE, value(inject, "cancellable"),
                "the handler cancels the call; without this the mask write still runs and throws");
        assertEquals(Boolean.FALSE, value(inject, "remap"), "remap must be false");
        assertEquals(1, value(inject, "require"),
                "require must be 1 so a Better Caves update that moves carveBlock fails the boot "
                        + "loudly instead of silently dropping the guard");

        @SuppressWarnings("unchecked")
        List<AnnotationNode> at = (List<AnnotationNode>) value(inject, "at");
        assertNotNull(at, "@Inject has no @At");
        assertEquals("HEAD", value(at.get(0), "value"),
                "the guard must run at HEAD — the mask write is carveBlock's first instruction, so "
                        + "any later injection point is already too late");
    }

    // ---------------------------------------------------------------- helpers

    private static ClassNode read(String internalName) throws IOException {
        try (InputStream in = BetterCavesCarveFloorTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, internalName + ".class is not on the test classpath");
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static List<AnnotationNode> annotationsOf(ClassNode node) {
        List<AnnotationNode> all = new ArrayList<>();
        if (node.invisibleAnnotations != null) {
            all.addAll(node.invisibleAnnotations);
        }
        if (node.visibleAnnotations != null) {
            all.addAll(node.visibleAnnotations);
        }
        return all;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }
}
