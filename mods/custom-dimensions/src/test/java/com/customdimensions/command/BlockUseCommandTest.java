package com.customdimensions.command;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /customdim use} is a test instrument, so what it is worth depends
 * entirely on it performing a REAL use rather than an effect that resembles
 * one.
 *
 * <p>The geometry half is pure and tested directly. The path it takes needs a
 * live {@code ServerWorld} and a {@code ServerPlayerEntity}, which no test in
 * this suite can build — so it is pinned in the compiled bytecode instead:
 * which methods the command calls, and which it must never call.
 */
class BlockUseCommandTest {

    private static final Path CLASSES = Path.of("build", "classes", "java", "main");
    private static final String COMMAND =
            "com/customdimensions/command/BlockUseCommand.class";

    // ------------------------------------------------------------ geometry

    @Test
    void theHitLandsOnTheCentreOfTheFaceTheCallerNamed() {
        BlockPos pos = new BlockPos(10, 64, -20);

        assertEquals(new Vec3d(10.5, 65.0, -19.5), BlockUseCommand.hitVector(pos, Direction.UP));
        assertEquals(new Vec3d(10.5, 64.0, -19.5), BlockUseCommand.hitVector(pos, Direction.DOWN));
        assertEquals(new Vec3d(11.0, 64.5, -19.5), BlockUseCommand.hitVector(pos, Direction.EAST));
        assertEquals(new Vec3d(10.0, 64.5, -19.5), BlockUseCommand.hitVector(pos, Direction.WEST));
        assertEquals(new Vec3d(10.5, 64.5, -20.0), BlockUseCommand.hitVector(pos, Direction.NORTH));
        assertEquals(new Vec3d(10.5, 64.5, -19.0), BlockUseCommand.hitVector(pos, Direction.SOUTH));
    }

    @Test
    void theHitResultCarriesTheFaceAndThePositionAsked() {
        BlockPos pos = new BlockPos(-3, 7, 200);
        for (Direction side : Direction.values()) {
            BlockHitResult hit = BlockUseCommand.hitOn(pos, side);

            assertEquals(side, hit.getSide(), "the hit must name the face the caller asked for");
            assertEquals(pos, hit.getBlockPos());
            assertEquals(BlockUseCommand.hitVector(pos, side), hit.getPos());
            assertFalse(hit.isInsideBlock(),
                    "a click from outside the block, like a player's — not a hit inside it");
        }
    }

    @Test
    void everyFaceNameParsesAndNothingElseDoes() {
        for (Direction side : Direction.values()) {
            assertEquals(side, BlockUseCommand.parseFace(side.getName()));
            assertEquals(side, BlockUseCommand.parseFace(side.getName().toUpperCase(java.util.Locale.ROOT)));
        }
        assertNull(BlockUseCommand.parseFace("sideways"));
        assertNull(BlockUseCommand.parseFace(""));
        assertNull(BlockUseCommand.parseFace(null));
    }

    // ------------------------------------------------- the path it takes

    private record Call(String owner, String name) {
    }

    private static List<Call> callsFrom(String classFile) throws IOException {
        List<Call> calls = new ArrayList<>();
        Path path = CLASSES.resolve(classFile);
        assertTrue(Files.exists(path), "compiled class not found: " + path.toAbsolutePath()
                + " — this test reads bytecode, it must never silently skip");
        try (InputStream in = Files.newInputStream(path)) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String callName,
                                String callDesc, boolean isInterface) {
                            calls.add(new Call(owner, callName));
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    @Test
    void theUseGoesThroughTheInteractionManagerThatARealClickGoesThrough() throws IOException {
        // BlockState.onUse alone would not do: EndPortalFrameBlock does not
        // override it, and the eye is socketed by EnderEyeItem.useOnBlock —
        // only interactBlock reaches both, in vanilla's own order.
        List<Call> calls = callsFrom(COMMAND);

        assertTrue(calls.stream().anyMatch(c ->
                        c.owner().equals("net/minecraft/server/network/ServerPlayerInteractionManager")
                        && c.name().equals("interactBlock")),
                "the command does not call interactBlock, so it is not performing a real use — "
                + "whatever it does instead only resembles one");
    }

    @Test
    void itNeverProducesTheEffectItself() throws IOException {
        // The whole value of the instrument is that it did not do the thing:
        // the game did, through the path a player's click takes.
        List<Call> calls = callsFrom(COMMAND);

        for (String forbidden : List.of("setBlockState", "breakBlock", "removeBlock")) {
            assertTrue(calls.stream().noneMatch(c -> c.name().equals(forbidden)),
                    "the command calls " + forbidden + " — it is reimplementing an effect "
                    + "instead of performing a use, and proves nothing about the real path");
        }
    }

    @Test
    void itKnowsNothingAboutEndPortalFrames() throws IOException {
        // A generic use is what makes it trustworthy here and useful later. A
        // special case for the block under test is a test that tests itself.
        List<Call> calls = callsFrom(COMMAND);
        String source = Files.readString(Path.of("src", "main", "java", "com",
                "customdimensions", "command", "BlockUseCommand.java"));

        assertTrue(calls.stream().noneMatch(c -> c.owner().contains("EndPortalFrame")
                        || c.owner().contains("EnderEye")),
                "the command reaches for end-portal-frame or ender-eye types directly");
        assertFalse(source.contains("Blocks.END_PORTAL_FRAME")
                        || source.contains("Items.ENDER_EYE"),
                "the command names the very blocks it is meant to be a neutral instrument for");
    }

    @Test
    void itRefusesAColdColumnRatherThanGeneratingOne() throws IOException {
        // Same rule as every other diagnostic: a command that generates
        // terrain to answer a question is a watchdog kill waiting to happen.
        List<Call> calls = callsFrom(COMMAND);

        assertTrue(calls.stream().anyMatch(c ->
                        c.owner().equals("com/customdimensions/portal/PortalHelper")
                        && c.name().equals("isColumnResident")),
                "the command does not probe that the column is resident, so it can wait on "
                + "chunk generation from the command thread");
        assertTrue(calls.stream().noneMatch(c -> c.name().equals("getChunk")),
                "the command acquires a chunk in a way that can generate one");
    }

    @Test
    void itChecksReachRatherThanBypassingIt() throws IOException {
        List<Call> calls = callsFrom(COMMAND);

        assertTrue(calls.stream().anyMatch(c -> c.name().equals("canInteractWithBlockAt")),
                "the command does not check reach — an instrument that can use a block "
                + "through a wall proves less than one that cannot");
    }
}
