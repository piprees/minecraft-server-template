package com.customdimensions.portal;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A portal must PUT you where it SHOWS you. The projection reads the
 * recorded source column ({@code ProjectionVolume.returnMapping}); both
 * teleport sites once copied the arrival aperture's own column instead, which
 * is the same answer only where the aperture happens to be its own
 * destination divided by the scale. Measured elsewhere: 57 blocks out at a
 * scale-4 site, 2280 at a scale-2 one.
 *
 * <p>{@link PortalHelper#returnColumn} is the single answer both sites now
 * ask for, so the arithmetic cannot diverge again — and the bytecode check
 * below is what holds them to asking.
 */
class PortalReturnColumnTest {

    private static final RegistryKey<World> SOURCE =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));

    private static PortalHelper.PortalReturnTarget target(Integer sourceX, Integer sourceZ) {
        PortalHelper.PortalReturnTarget target =
                new PortalHelper.PortalReturnTarget(SOURCE, 80, 0x8844FF, 40);
        target.sourceX = sourceX;
        target.sourceZ = sourceZ;
        return target;
    }

    @Test
    void aRecordedColumnWins() {
        // The measured scale-4 site: aperture (15, 13), record (59, 50).
        assertArrayEquals(new int[]{59, 50},
                PortalHelper.returnColumn(target(59, 50), new BlockPos(15, 133, 13)));
    }

    @Test
    void theColumnIsTakenVerbatim_notScaled() {
        // The scale-2 site where aperture == destination / 2 hides the defect:
        // 3464/2 = 1732, 2592/2 = 1296. Nothing on the return path scales.
        assertArrayEquals(new int[]{3464, 2592},
                PortalHelper.returnColumn(target(3464, 2592), new BlockPos(1732, 63, 1296)));
    }

    @Test
    void aLegacyRecordKeepsTheAperturesOwnColumn() {
        // sourceX/sourceZ are boxed and never constructor-assigned, so a
        // record written before setSourceColumn existed reads null, not 0.
        // A 0 here would teleport a player to the world origin.
        assertArrayEquals(new int[]{1732, 1296},
                PortalHelper.returnColumn(target(null, null), new BlockPos(1732, 63, 1296)));
    }

    @Test
    void aHalfWrittenColumnIsNotTrusted() {
        assertArrayEquals(new int[]{15, 13},
                PortalHelper.returnColumn(target(59, null), new BlockPos(15, 133, 13)));
        assertArrayEquals(new int[]{15, 13},
                PortalHelper.returnColumn(target(null, 50), new BlockPos(15, 133, 13)));
    }

    @Test
    void aZeroColumnIsARealColumn() {
        assertArrayEquals(new int[]{0, 0},
                PortalHelper.returnColumn(target(0, 0), new BlockPos(1732, 63, 1296)));
    }

    @Test
    void noTargetAtAllFallsBackRatherThanThrowing() {
        assertArrayEquals(new int[]{-40, 7},
                PortalHelper.returnColumn(null, new BlockPos(-40, 63, 7)));
    }

    /**
     * Both return-teleport sites must ASK for the column rather than compute
     * one. A site that stops calling this has gone back to the aperture's own
     * column, and no headless test of the arithmetic would notice.
     */
    @Test
    void bothTeleportSitesResolveTheColumnThroughPortalHelper() {
        for (String owner : new String[]{
                "com/customdimensions/mixin/EntityTickPortalMixin",
                "com/customdimensions/immersive/EntityPassthrough"}) {
            assertTrue(callsReturnColumn(owner),
                    owner + " does not call PortalHelper.returnColumn — its return teleport is "
                    + "computing a column of its own again");
        }
        // Negative control: the detector must be able to say no. PortalHelper
        // declares returnColumn and calls it nowhere.
        assertFalse(callsReturnColumn("com/customdimensions/portal/PortalHelper"));
    }

    private static boolean callsReturnColumn(String internalName) {
        Path classFile = Path.of("build", "classes", "java", "main")
                .resolve(internalName + ".class");
        assertTrue(Files.exists(classFile), "compile main before running this: " + classFile);
        boolean[] found = {false};
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                                String descriptor, boolean isInterface) {
                            if ("com/customdimensions/portal/PortalHelper".equals(owner)
                                    && "returnColumn".equals(method)) {
                                found[0] = true;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + classFile, e);
        }
        return found[0];
    }
}
