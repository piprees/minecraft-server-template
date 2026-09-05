package com.customdimensions.portal;

import com.customdimensions.immersive.ProjectionVolume;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A portal must PUT you where it SHOWS you. The projection reads the recorded
 * source column; both return teleports once copied the arrival aperture's own
 * column instead, which is the same answer only where the aperture happens to
 * be its own destination divided by the scale. Measured elsewhere: 57 blocks
 * out at a scale-4 site, 2280 at a scale-2 one.
 *
 * <p>{@code ProjectionVolume.resolveReturnColumn} is the one guard all three
 * paths share. They differ only in the fallback a column-less record gets: a
 * projection samples the aperture CENTRE, a teleport moves the entity from the
 * CELL it is standing in. That difference is deliberate and pinned below.
 */
class PortalReturnColumnTest {

    private static final RegistryKey<World> SOURCE =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));

    /** A two-wide, two-high aperture whose centre is not every cell. */
    private static final Set<BlockPos> APERTURE = Set.of(
            new BlockPos(1732, 63, 1296), new BlockPos(1733, 63, 1296),
            new BlockPos(1732, 64, 1296), new BlockPos(1733, 64, 1296));

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
     * The property the whole defect was: one record, one answer. Whichever
     * cell of the aperture an entity stands in, the teleport lands where the
     * projection drew.
     */
    @Test
    void theProjectionAndTheTeleportResolveOneColumn() {
        ProjectionVolume.TargetMapping mapping =
                ProjectionVolume.returnMapping(APERTURE, 3464, 2592);
        for (BlockPos cell : APERTURE) {
            assertArrayEquals(new int[]{mapping.arrivalX(), mapping.arrivalZ()},
                    PortalHelper.returnColumn(target(3464, 2592), cell));
        }
    }

    /**
     * Where they legitimately differ, so nobody collapses the two fallbacks.
     * A projection has an aperture and samples its centre; a teleport has one
     * entity and must not shunt it sideways to a cell it was not in.
     */
    @Test
    void aColumnLessRecordFallsBackToTheCentreForAPreviewAndTheCellForATeleport() {
        ProjectionVolume.TargetMapping mapping =
                ProjectionVolume.returnMapping(APERTURE, null, null);
        assertEquals(1732, mapping.arrivalX());
        assertEquals(1296, mapping.arrivalZ());
        assertEquals(0, mapping.dx());
        assertArrayEquals(new int[]{1733, 1296},
                PortalHelper.returnColumn(target(null, null), new BlockPos(1733, 64, 1296)));
    }

    /**
     * Everything that resolves a return column must ASK for the shared guard
     * rather than test the pair itself. Two copies of that test are what let
     * the projection and the traversal diverge in the first place.
     */
    @Test
    void everyReturnPathResolvesTheColumnThroughTheSharedGuard() {
        for (String owner : new String[]{
                "com/customdimensions/mixin/EntityTickPortalMixin",
                "com/customdimensions/immersive/EntityPassthrough"}) {
            assertTrue(calls(owner, "com/customdimensions/portal/PortalHelper", "returnColumn"),
                    owner + " does not call PortalHelper.returnColumn — its return teleport is "
                    + "computing a column of its own again");
        }
        String projection = "com/customdimensions/immersive/ProjectionVolume";
        assertTrue(calls("com/customdimensions/portal/PortalHelper",
                        projection, "resolveReturnColumn"),
                "PortalHelper.returnColumn has stopped delegating to the shared guard");
        assertTrue(calls(projection, projection, "resolveReturnColumn"),
                "ProjectionVolume.returnMapping has stopped using the shared guard");
        // Negative control: the detector must be able to say no.
        assertFalse(calls("com/customdimensions/portal/PortalBreakLink",
                projection, "resolveReturnColumn"));
    }

    /** Whether {@code internalName} invokes {@code owner#method} anywhere. */
    private static boolean calls(String internalName, String owner, String method) {
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
                        public void visitMethodInsn(int opcode, String called, String name,
                                String descriptor, boolean isInterface) {
                            if (owner.equals(called) && method.equals(name)) {
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
