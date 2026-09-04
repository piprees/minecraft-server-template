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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IgniterSpend decides; the mixin applies. Nothing inside a mixin is reachable
 * by a unit test, so this reads the compiled class: a decision that is computed
 * and then ignored destroys a player's flint and steel exactly as an
 * unconditional decrement does, and looks like a fix in the diff.
 */
class IgniterSpendWiredTest {

    private static final Path MIXIN = Path.of("build", "classes", "java", "main",
            "com", "customdimensions", "mixin", "PortalIgnitionMixin.class");

    private static final String SPEND = "com/customdimensions/portal/IgniterSpend";
    private static final String STACK = "net/minecraft/item/ItemStack";

    private static final class Calls extends ClassVisitor {
        /** method name -> the "owner.method" calls it makes. */
        final Map<String, Set<String>> byMethod = new LinkedHashMap<>();
        /** Opcodes seen immediately after an IgniterSpend.of call. */
        final List<Integer> afterSpendDecision = new ArrayList<>();
        /** Descriptors of every ItemStack.damage overload called. */
        final Set<String> damageOverloads = new LinkedHashSet<>();
        /** Call sites, not distinct callees — two decrements are two sites. */
        int damageSites;
        int decrementSites;

        Calls() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            Set<String> calls = this.byMethod.computeIfAbsent(name, k -> new LinkedHashSet<>());
            return new MethodVisitor(Opcodes.ASM9) {
                private boolean justDecided;

                private void settle(int opcode) {
                    if (this.justDecided) {
                        Calls.this.afterSpendDecision.add(opcode);
                        this.justDecided = false;
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    settle(opcode);
                }

                @Override
                public void visitInsn(int opcode) {
                    settle(opcode);
                }

                @Override
                public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                    settle(opcode);
                }

                @Override
                public void visitVarInsn(int opcode, int slot) {
                    settle(opcode);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String method,
                        String desc, boolean itf) {
                    settle(opcode);
                    calls.add(owner + "." + method);
                    if (STACK.equals(owner) && "damage".equals(method)) {
                        Calls.this.damageOverloads.add(desc);
                        Calls.this.damageSites++;
                    }
                    if (STACK.equals(owner) && "decrement".equals(method)) {
                        Calls.this.decrementSites++;
                    }
                    if (SPEND.equals(owner) && "of".equals(method)) {
                        this.justDecided = true;
                    }
                }
            };
        }
    }

    private static Calls readMixin() throws IOException {
        assertTrue(Files.exists(MIXIN),
                "compile the mod before running this: " + MIXIN.toAbsolutePath());
        Calls calls = new Calls();
        try (InputStream in = Files.newInputStream(MIXIN)) {
            new ClassReader(in).accept(calls, ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    /** The single method that spends the igniter, by the call that spends it. */
    private static String spendingMethod(Calls calls, String call) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : calls.byMethod.entrySet()) {
            if (entry.getValue().contains(call)) {
                found.add(entry.getKey());
            }
        }
        assertEquals(1, found.size(),
                "expected exactly one method in PortalIgnitionMixin calling " + call
                + ", found " + found);
        return found.get(0);
    }

    @Test
    void aDamageableIgniterIsDamagedAndNotDecremented() throws IOException {
        // The whole defect: decrement(1) on a stack of one destroys the tool.
        // Counting SITES, not distinct callees — the overload swap below is
        // invisible to a set, and one branch reverting to decrement is exactly
        // the bug coming back on the path nobody tests by hand.
        Calls calls = readMixin();

        assertEquals(2, calls.damageSites,
                "both player cases must damage; damage sites found: " + calls.damageSites);
        assertEquals(1, calls.decrementSites,
                "the one decrement left belongs to CONSUME; sites found: " + calls.decrementSites);
        assertEquals(spendingMethod(calls, STACK + ".damage"),
                spendingMethod(calls, STACK + ".decrement"),
                "damage and decrement must sit in the same branch of one decision, not two paths");
    }

    @Test
    void theSpendIsDecidedByIgniterSpendAndNotInline() throws IOException {
        Calls calls = readMixin();

        String spending = spendingMethod(calls, STACK + ".decrement");
        Set<String> made = calls.byMethod.get(spending);
        assertTrue(made.contains(SPEND + ".of"),
                spending + " spends the igniter without asking IgniterSpend; it calls " + made);
        assertTrue(made.contains(STACK + ".isDamageable"),
                "the decision must be fed the stack's real durability, not a constant; " + made);
    }

    @Test
    void theDecisionIsActedOnRatherThanComputedAndDropped() throws IOException {
        // A verdict that is calculated and discarded reads as a fix and behaves
        // like the bug. Discarding a returned enum is exactly a POP.
        Calls calls = readMixin();

        assertFalse(calls.afterSpendDecision.isEmpty(),
                "PortalIgnitionMixin no longer calls IgniterSpend.of at all");
        for (int opcode : calls.afterSpendDecision) {
            assertTrue(opcode != Opcodes.POP && opcode != Opcodes.POP2,
                    "IgniterSpend.of's verdict is thrown away; the mixin must branch on it");
        }
    }

    @Test
    void anIgnitionWithNoPlayerStillDamagesRatherThanDestroys() throws IOException {
        // A dispenser has no player. The player-taking overload NPEs on one,
        // and decrementing there destroys the tool by the other route.
        Calls calls = readMixin();

        assertEquals(2, calls.damageOverloads.size(),
                "expected both ItemStack.damage overloads — one per player case; found "
                + calls.damageOverloads);
        assertTrue(calls.damageOverloads.stream()
                        .anyMatch(d -> d.contains("Lnet/minecraft/server/world/ServerWorld;")),
                "the null-player case needs the ServerWorld overload; found "
                + calls.damageOverloads);
    }

    @Test
    void creativeIsAskedOfThePlayerAndNotOfTheStack() throws IOException {
        Calls calls = readMixin();

        String spending = spendingMethod(calls, STACK + ".decrement");
        assertTrue(calls.byMethod.get(spending).stream().anyMatch(c -> c.endsWith(".isCreative")),
                "a creative player pays for nothing, and nothing here asks whether they are one");
    }
}
