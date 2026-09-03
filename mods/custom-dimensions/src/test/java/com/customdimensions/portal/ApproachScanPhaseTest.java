package com.customdimensions.portal;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.DimensionConfigLoader;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The approach scan is driven off the SERVER tick, which every loaded world
 * shares, so an unphased {@code ticks % interval} runs every dimension's POI
 * square on the same tick. Phasing spreads them, the way the projector's
 * particlePhase spreads portals inside one world.
 *
 * <p>Checked against the real shipped dimension set, because the set is the
 * thing that makes the spike matter — a herd of two is not a herd.
 */
class ApproachScanPhaseTest {

    /** Repo root, from the Gradle project directory (mods/custom-dimensions). */
    private static final Path CONFIG_ROOT = Path.of("..", "..", "config", "custom-dimensions");

    private static final int INTERVAL = 40;

    private static Map<String, DimensionConfig> shipped() {
        assertTrue(Files.isDirectory(CONFIG_ROOT.resolve("dimensions")),
                "shipped dimension configs not found at " + CONFIG_ROOT.toAbsolutePath()
                        + " — this test must run against the real set, never silently skip");
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadAllWithSettings(CONFIG_ROOT, null).dimensions();
        assertFalse(dims.isEmpty(), "loaded no dimensions at all");
        return dims;
    }

    /** How many shipped dimensions land on each tick of the interval. */
    private static Map<Integer, Integer> perTick(Map<String, DimensionConfig> dims) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (DimensionConfig config : dims.values()) {
            counts.merge(PortalAdoption.approachPhase(config.getDimensionId(), INTERVAL),
                    1, Integer::sum);
        }
        return counts;
    }

    @Test
    void theShippedDimensionsDoNotAllScanOnTheSameTick() {
        Map<String, DimensionConfig> dims = shipped();
        Map<Integer, Integer> counts = perTick(dims);

        int perfect = (dims.size() + INTERVAL - 1) / INTERVAL;
        int worst = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        // Three times a perfect spread, not a tuned number: a hash is allowed
        // to clump, and an unphased pass puts all of them on one tick.
        assertTrue(worst <= perfect * 3,
                "the worst tick carries " + worst + " of " + dims.size() + " dimensions "
                        + "(a perfect spread is " + perfect + "): the approach scan is a spike, "
                        + "not a cadence. Distribution: " + counts);
    }

    @Test
    void aPhaseAlwaysLandsInsideTheInterval() {
        // Out of range and the world either never scans or scans every tick.
        for (DimensionConfig config : shipped().values()) {
            int phase = PortalAdoption.approachPhase(config.getDimensionId(), INTERVAL);
            assertTrue(phase >= 0 && phase < INTERVAL,
                    config.getDimensionId() + " phases to " + phase + ", outside 0.." + INTERVAL);
        }
    }

    @Test
    void aWorldKeepsTheSamePhaseEveryTick() {
        // The offset is added to a running counter, so a phase that moved
        // would make a world scan erratically rather than on a cadence.
        assertEquals(PortalAdoption.approachPhase("adventure:the_crucible", INTERVAL),
                PortalAdoption.approachPhase("adventure:the_crucible", INTERVAL));
    }

    @Test
    void twoWorldsWhoseIdsDifferByOneCharacterDoNotShareATick() {
        assertFalse(PortalAdoption.approachPhase("adventure:the_end", INTERVAL)
                == PortalAdoption.approachPhase("adventure:the_end_", INTERVAL));
    }

    /**
     * The spread above is a property of the function; this is the only thing
     * that says the tick gate uses it. Dropping the offset from the modulo in
     * the mixin passes every other test in this class.
     */
    @Test
    void theTickGateInTheMixinIsBuiltFromThePhase() throws IOException {
        Path mixin = Path.of("build", "classes", "java", "main",
                "com", "customdimensions", "mixin", "ServerWorldMixin.class");
        assertTrue(Files.isRegularFile(mixin),
                "compiled mixin not found at " + mixin.toAbsolutePath()
                        + " — this test reads bytecode, it must never silently skip");

        List<String> called = new ArrayList<>();
        try (InputStream in = Files.newInputStream(mixin)) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!name.equals("adoptPortalsOnApproach")) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String callName,
                                String callDesc, boolean isInterface) {
                            called.add(callName);
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }

        assertFalse(called.isEmpty(), "the approach pass has moved or been renamed");
        assertTrue(called.contains("approachPhase"),
                "the approach pass gates on the raw server tick, which every loaded world "
                + "shares: all " + shipped().size() + " shipped dimensions scan together. "
                + "Calls found: " + called);
    }

    @Test
    void aZeroIntervalIsNotADivideByZero() {
        // The caller's constant could reach zero through a refactor; the
        // phase must not be the thing that crashes the tick when it does.
        assertEquals(0, PortalAdoption.approachPhase("adventure:the_crucible", 0));
    }
}
