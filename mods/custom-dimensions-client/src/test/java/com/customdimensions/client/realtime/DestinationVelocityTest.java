package com.customdimensions.client.realtime;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fed entity must carry its velocity, or every snapshot teleports it the
 * whole per-window displacement — an error that scales with speed and has no
 * ceiling. Measured over 251 samples before this held: {@code dy = +0.00} on
 * every held sample and jumps of 2.5 to 5.7 blocks per snapshot.
 *
 * <p>Both halves are asserted from bytecode: the vanilla PREMISE, which is a
 * property of the Minecraft version and not of this mod, and this mod's two
 * application sites. A version bump that moves {@code setVelocity} into
 * {@code Entity.onSpawnPacket} makes the premise test fail loudly rather than
 * leaving a stale comment behind.
 */
class DestinationVelocityTest {

    private static final String SPAWN_PACKET =
            "net/minecraft/network/packet/s2c/play/EntitySpawnS2CPacket";

    /**
     * The premise. {@code Entity.onSpawnPacket} keeps position, angles, id
     * and uuid; only {@code LivingEntity}'s override applies the velocity —
     * which is why a mob copy moved and an arrow did not.
     */
    @Test
    void vanillaDropsSpawnVelocityForEverythingButALivingEntity() {
        assertFalse(callsSetVelocity(fromClasspath("net/minecraft/entity/Entity"), "onSpawnPacket"),
                "Entity.onSpawnPacket now applies velocity — this mod's applyVelocity may be "
                + "redundant, and its reasoning is out of date");
        assertTrue(callsSetVelocity(fromClasspath("net/minecraft/entity/LivingEntity"), "onSpawnPacket"),
                "LivingEntity.onSpawnPacket no longer applies velocity");
    }

    /** A spawn packet is the only thing in the feed that carries velocity. */
    @Test
    void theSnapshotPacketIsWhatCarriesVelocity() {
        Set<String> spawnPacket = methodsOf(fromClasspath(SPAWN_PACKET));
        assertTrue(spawnPacket.containsAll(Set.of("getVelocityX", "getVelocityY", "getVelocityZ")));
        assertFalse(methodsOf(fromClasspath(
                        "net/minecraft/network/packet/s2c/play/EntityTrackerUpdateS2CPacket"))
                .stream().anyMatch(name -> name.toLowerCase().contains("velocity")),
                "EntityTrackerUpdateS2CPacket carries velocity now — a move could refresh from it");
    }

    /**
     * Both application sites. A spawn without it starts the copy at rest; a
     * move without it freezes the copy at whatever it entered with, and the
     * snapshot cadence then reads as a teleport.
     */
    @Test
    void bothSpawnAndMoveApplyTheSnapshotVelocity() {
        byte[] live = fromBuild("com/customdimensions/client/realtime/DestinationEntities$Live");
        assertTrue(callsSetVelocity(live, "spawn"), "spawn does not apply the snapshot velocity");
        assertTrue(callsSetVelocity(live, "move"), "move does not refresh the snapshot velocity");
        // Negative control: the detector must be able to say no. `remove`
        // drops an entity and touches no physics.
        assertFalse(callsSetVelocity(live, "remove"));
    }

    // ------------------------------------------------------------------

    /**
     * Whether {@code method}, or any private helper it calls in the same
     * class, reaches {@code Entity.setVelocity}. One hop is enough: this mod
     * calls it through {@code applyVelocity} and vanilla calls it directly.
     */
    private static boolean callsSetVelocity(byte[] classFile, String method) {
        return reaches(classFile, method, "setVelocity")
                || helpersOf(classFile, method).stream()
                        .anyMatch(helper -> reaches(classFile, helper, "setVelocity"));
    }

    private static boolean reaches(byte[] classFile, String method, String called) {
        boolean[] found = {false};
        visit(classFile, method, (owner, name) -> {
            if (called.equals(name)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** Calls {@code method} makes to its own class — the one hop that is followed. */
    private static Set<String> helpersOf(byte[] classFile, String method) {
        String self = ownerOf(classFile);
        Set<String> helpers = new LinkedHashSet<>();
        visit(classFile, method, (owner, name) -> {
            if (self.equals(owner) && !name.equals(method)) {
                helpers.add(name);
            }
        });
        return helpers;
    }

    private interface CallSink {
        void call(String owner, String name);
    }

    private static void visit(byte[] classFile, String method, CallSink sink) {
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!name.equals(method)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called,
                            String descriptor, boolean isInterface) {
                        sink.call(owner, called);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
    }

    private static Set<String> methodsOf(byte[] classFile) {
        Set<String> names = new LinkedHashSet<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                names.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return names;
    }

    private static String ownerOf(byte[] classFile) {
        return new ClassReader(classFile).getClassName();
    }

    /** A Minecraft class, read off the Loom-mapped jar on the test classpath. */
    private static byte[] fromClasspath(String internalName) {
        try (InputStream in = DestinationVelocityTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "not on the test classpath: " + internalName);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + internalName, e);
        }
    }

    /** One of this mod's own compiled classes. */
    private static byte[] fromBuild(String internalName) {
        Path classFile = Path.of("build", "classes", "java", "main")
                .resolve(internalName + ".class");
        assertTrue(Files.exists(classFile), "compile main before running this: " + classFile);
        try {
            return Files.readAllBytes(classFile);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + classFile, e);
        }
    }
}
