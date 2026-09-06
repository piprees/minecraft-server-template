package com.customdimensions.immersive;

import com.customdimensions.immersive.ProjectionPresence.Presence;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stepping across the activation band must not blank the opening. Measured at
 * the render rate, an in-and-out walk drew nothing on 54% of frames because
 * every crossing sent a clear and rebuilt from scratch.
 *
 * <p>The policy is pure and asserted directly; the one line that consults it
 * sits in a pass needing a ServerWorld and a live player, so it is read out of
 * the compiled class — a verdict computed and dropped clears exactly as the
 * unconditional teardown did.
 */
class ProjectionPresenceTest {

    /** ImmersiveProjector's own geometry: DEACTIVATE_MARGIN 2, TICKET_DROP_MARGIN 10. */
    private static final int RANGE = 24;
    private static final int DEACTIVATE = RANGE + 2;
    private static final int GRACE = RANGE + 10;

    private static Presence at(double distance, boolean projecting, long tick, long leftAt) {
        return ProjectionPresence.of(distance * distance, RANGE, DEACTIVATE, GRACE,
                projecting, tick, leftAt);
    }

    // ------------------------------------------------------------------
    // The defect: an in-and-out walk must never clear
    // ------------------------------------------------------------------

    @Test
    void aPlayerSteppingOutAndBackKeepsTheProjectionThroughout() {
        // Walking is 4.317 blocks/s, so the two-block band is crossed in about
        // nine ticks. Twenty out and twenty back is a slow, deliberate loiter.
        long leftAt = ProjectionPresence.NOT_LEFT;
        List<Presence> seen = new ArrayList<>();
        for (long tick = 0; tick < 400; tick++) {
            // 20 ticks inside the band, 20 outside it, repeatedly.
            double distance = (tick / 20) % 2 == 0 ? RANGE - 1 : DEACTIVATE + 3;
            Presence presence = at(distance, true, tick, leftAt);
            seen.add(presence);
            if (presence == Presence.HOLD && leftAt == ProjectionPresence.NOT_LEFT) {
                leftAt = tick;
            } else if (presence == Presence.PROJECT) {
                leftAt = ProjectionPresence.NOT_LEFT;
            }
        }
        assertFalse(seen.contains(Presence.CLEAR),
                "an ordinary loiter across the two-block band tore the projection down; "
                        + "that is the blank frame the grace window exists to stop");
        assertTrue(seen.contains(Presence.HOLD), "the fixture never left the band at all");
        assertTrue(seen.contains(Presence.PROJECT), "the fixture never came back");
    }

    @Test
    void returningInsideTheWindowResumesRatherThanRebuilding() {
        assertEquals(Presence.HOLD, at(DEACTIVATE + 3, true, 100, ProjectionPresence.NOT_LEFT));
        // A literal bound, not GRACE_TICKS: a shrunk window must redden this
        // test rather than emptying its loop.
        for (long out = 1; out <= 99; out++) {
            assertEquals(Presence.HOLD, at(DEACTIVATE + 3, true, 100 + out, 100),
                    "cleared " + out + " ticks out, inside the grace window");
        }
        assertEquals(Presence.PROJECT, at(RANGE - 1, true, 100 + 99, 100),
                "a player back inside the band must draw again, not be held");
    }

    // ------------------------------------------------------------------
    // And the other direction: someone who has gone stops costing anything
    // ------------------------------------------------------------------

    @Test
    void aPlayerWhoStaysAwayLosesItWhenTheWindowExpires() {
        assertEquals(Presence.HOLD, at(DEACTIVATE + 3, true, 199, 100));
        assertEquals(Presence.CLEAR, at(DEACTIVATE + 3, true, 200, 100),
                "the window must expire for a player who has genuinely gone");
        assertEquals(Presence.CLEAR, at(DEACTIVATE + 3, true, 5000, 100));
    }

    @Test
    void walkingClearOfTheTicketBandClearsWithoutWaitingForTheTimer() {
        // Distance is the second bound: past the band the chunk ticket covers,
        // there is nothing left that could refresh the projection anyway.
        assertEquals(Presence.CLEAR, at(GRACE + 1, true, 100, 100));
        assertEquals(Presence.CLEAR, at(GRACE + 1, true, 100, ProjectionPresence.NOT_LEFT));
        assertEquals(Presence.HOLD, at(GRACE, true, 100, 100),
                "the band's own edge is still held");
    }

    @Test
    void theGraceBandNeverReachesPastTheChunkTicket() {
        // A projection held past its ticket would be refreshed from chunks that
        // have drained, which is a stale window rather than a live one.
        assertTrue(GRACE - RANGE <= 10,
                "grace radius must stay within TICKET_DROP_MARGIN of the activation range");
        assertEquals(100, ProjectionPresence.GRACE_TICKS,
                "grace shares TICKET_EXPIRY_TICKS; change both or neither");
    }

    // ------------------------------------------------------------------
    // Activation is unchanged
    // ------------------------------------------------------------------

    @Test
    void nothingIsHeldForAPlayerWhoHasNoProjection() {
        assertEquals(Presence.PROJECT, at(RANGE, false, 0, ProjectionPresence.NOT_LEFT));
        assertEquals(Presence.CLEAR, at(RANGE + 1, false, 0, ProjectionPresence.NOT_LEFT),
                "grace must never start a projection nobody had");
        assertEquals(Presence.CLEAR, at(DEACTIVATE + 1, false, 0, 0));
    }

    @Test
    void theHysteresisBandStillHoldsAnActiveProjection() {
        assertEquals(Presence.PROJECT, at(DEACTIVATE, true, 0, ProjectionPresence.NOT_LEFT));
        assertNotEquals(Presence.PROJECT, at(DEACTIVATE + 1, true, 0, ProjectionPresence.NOT_LEFT));
    }

    // ------------------------------------------------------------------
    // The line that consults it
    // ------------------------------------------------------------------

    @Test
    void theProjectionPassAsksTheGracePolicyAndActsOnTheAnswer() throws IOException {
        Calls calls = read("com/customdimensions/immersive/ImmersiveProjector.class");

        Set<String> made = calls.byMethod.get("projectToPlayers");
        assertFalse(made == null, "ImmersiveProjector has no projectToPlayers");
        assertTrue(made.contains(PRESENCE + ".of"),
                "projectToPlayers tears down on its own range test rather than asking "
                        + PRESENCE + ": " + made);
        assertTrue(made.contains(STATE + ".outOfRangeSince"),
                "the pass must feed the policy when the player left, or every tick out "
                        + "looks like the first: " + made);
        assertTrue(made.contains(STATE + ".setOutOfRangeSince"),
                "nothing records when the player left, so the window never expires: " + made);

        List<Integer> after = calls.after.get(PRESENCE + ".of");
        assertFalse(after == null || after.isEmpty(), "ProjectionPresence.of is never reached");
        for (int opcode : after) {
            assertTrue(opcode != Opcodes.POP && opcode != Opcodes.POP2,
                    "the verdict is thrown away; the pass must branch on it");
        }
    }

    private static final String PRESENCE = "com/customdimensions/immersive/ProjectionPresence";
    private static final String STATE = "com/customdimensions/immersive/PlayerProjectionState";
    private static final Path CLASSES = Path.of("build", "classes", "java", "main");

    /** Which calls each method makes, and what happens to the verdict. */
    private static final class Calls extends ClassVisitor {
        final Map<String, Set<String>> byMethod = new LinkedHashMap<>();
        final Map<String, List<Integer>> after = new LinkedHashMap<>();

        Calls() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            Set<String> calls = this.byMethod.computeIfAbsent(name, k -> new LinkedHashSet<>());
            return new MethodVisitor(Opcodes.ASM9) {
                private String pending;

                private void settle(int opcode) {
                    if (this.pending != null) {
                        Calls.this.after.computeIfAbsent(this.pending, k -> new ArrayList<>())
                                .add(opcode);
                        this.pending = null;
                    }
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
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    settle(opcode);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String method,
                        String desc, boolean itf) {
                    settle(opcode);
                    calls.add(owner + "." + method);
                    if (PRESENCE.equals(owner) && "of".equals(method)) {
                        this.pending = PRESENCE + ".of";
                    }
                }
            };
        }
    }

    private static Calls read(String classFile) throws IOException {
        Path path = CLASSES.resolve(classFile);
        assertTrue(Files.isRegularFile(path),
                "compile the mod before running this: " + path.toAbsolutePath());
        Calls calls = new Calls();
        try (InputStream in = Files.newInputStream(path)) {
            new ClassReader(in).accept(calls, ClassReader.SKIP_FRAMES);
        }
        return calls;
    }
}
