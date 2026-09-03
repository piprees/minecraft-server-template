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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tick must never wait for a chunk. Waiting parks the main thread on a
 * future nothing is working on ([K1]/[K6]) and the watchdog shuts the server
 * down at 60s — twice now, in two different helpers.
 *
 * <p>So this asserts the PROPERTY, not a list of names: walk the compiled
 * call graph out from the tick entry points and fail if any reachable method
 * acquires a chunk through an accessor that can block. A name-keyed rule
 * cannot see the second helper somebody writes, which is exactly how both
 * crashes happened.
 *
 * <h2>Which accessors block, measured from the 1.21.1 bytecode</h2>
 * <ul>
 *   <li>{@code ServerChunkManager.getChunk(int, int, ChunkStatus, boolean)}
 *       reaches {@code MainThreadExecutor.runTasks(future::isDone)} and
 *       {@code join()} on EVERY path — {@code create} only changes which
 *       future it waits on. With a chunk ticket already placed, the holder
 *       exists at a sufficient level, the future is not done, and it waits
 *       for generation. <b>{@code create = false} is not a promise not to
 *       block.</b></li>
 *   <li>{@code ChunkManager.getWorldChunk(int, int, boolean)} is a thin
 *       delegate to that, so the three-argument probe blocks too.</li>
 *   <li>{@code ServerChunkManager.getWorldChunk(int, int)} cannot: cache
 *       lookup, then {@code getChunkHolder} and {@code getOrNull}, returning
 *       null. No future, no {@code runTasks}. This is the safe probe.</li>
 *   <li>{@code ServerChunkManager.isChunkLoaded(int, int)} cannot either:
 *       {@code getChunkHolder} then {@code isMissingForLevel}.</li>
 * </ul>
 */
class TickPathChunkLoadTest {

    private static final Path CLASSES = Path.of("build", "classes", "java", "main");
    private static final String OURS = "com/customdimensions/";

    /**
     * Where a server tick enters this mod. Every method of both mixins,
     * because that is all either class exists to do; everything else on a
     * tick path is reached from one of them and the walk finds it.
     */
    private static final List<String> TICK_ENTRY_CLASSES = List.of(
            "com/customdimensions/mixin/ServerWorldMixin",
            "com/customdimensions/mixin/EntityTickPortalMixin",
            "com/customdimensions/mixin/PortalDestinationMixin");

    /**
     * Methods the walk does not traverse THROUGH, because a runtime residency
     * check stands in front of them that no static walk can see. One entry,
     * and adding another should be an argument, not a convenience: each one
     * is a promise that every caller proved the column resident first.
     */
    private static final Map<String, String> GUARDED_BOUNDARIES = Map.of(
            "com/customdimensions/portal/PortalHelper#surfaceYIfResident",
            "probes the whole arrival footprint itself and answers null rather than reading a "
            + "cold column; findSurfaceY is reached only past that check");

    private record MethodRef(String owner, String name, String desc) {
        String node() {
            return owner + "#" + name;
        }

        @Override
        public String toString() {
            return owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
        }
    }

    private record Call(MethodRef target, int line) {
    }

    /** Every call our compiled code makes, keyed by the method making it. */
    private static Map<MethodRef, List<Call>> callGraph() throws IOException {
        Map<MethodRef, List<Call>> graph = new LinkedHashMap<>();
        try (Stream<Path> tree = Files.walk(CLASSES)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        private String owner;

                        @Override
                        public void visit(int version, int access, String name, String signature,
                                String superName, String[] interfaces) {
                            this.owner = name;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                String signature, String[] exceptions) {
                            MethodRef self = new MethodRef(this.owner, name, descriptor);
                            List<Call> calls =
                                    graph.computeIfAbsent(self, k -> new ArrayList<>());
                            return new MethodVisitor(Opcodes.ASM9) {
                                private int line = -1;

                                @Override
                                public void visitLineNumber(int lineNumber, org.objectweb.asm.Label start) {
                                    this.line = lineNumber;
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String callOwner,
                                        String callName, String callDesc, boolean isInterface) {
                                    calls.add(new Call(
                                            new MethodRef(callOwner, callName, callDesc), this.line));
                                }
                            };
                        }
                    }, ClassReader.SKIP_FRAMES);
                }
            }
        }
        return graph;
    }

    /**
     * Does this call acquire a chunk in a way that can wait for generation?
     * Chunk ACQUISITION only — see {@link #aTickPathNeverReachesABlockingChunkGet}
     * for why a block read cannot be judged this way.
     */
    private static boolean blocks(MethodRef call) {
        if (!call.owner().startsWith("net/minecraft/")) {
            return false;
        }
        if (call.name().equals("getChunk")) {
            return true;
        }
        // The three-argument probe delegates to getChunk; the two-argument one
        // on ServerChunkManager does not.
        return call.name().equals("getWorldChunk") && call.desc().startsWith("(IIZ)");
    }

    @Test
    void aTickPathNeverReachesABlockingChunkGet() throws IOException {
        assertTrue(Files.isDirectory(CLASSES),
                "compiled classes not found at " + CLASSES.toAbsolutePath()
                        + " — this test reads the call graph, it must never silently skip");
        Map<MethodRef, List<Call>> graph = callGraph();

        // Breadth-first from every method of the tick entry classes, keeping
        // the shortest path to each method so a failure can name the route.
        Map<MethodRef, List<MethodRef>> pathTo = new HashMap<>();
        ArrayDeque<MethodRef> queue = new ArrayDeque<>();
        for (MethodRef method : graph.keySet()) {
            if (TICK_ENTRY_CLASSES.contains(method.owner())) {
                pathTo.put(method, List.of(method));
                queue.add(method);
            }
        }
        assertTrue(!queue.isEmpty(), "no tick entry points found; the class names have moved");

        Set<String> found = new HashSet<>();
        List<String> offences = new ArrayList<>();
        while (!queue.isEmpty()) {
            MethodRef from = queue.poll();
            for (Call call : graph.getOrDefault(from, List.of())) {
                if (blocks(call.target())) {
                    List<MethodRef> route = pathTo.get(from);
                    if (found.add(from.node() + "#" + call.line())) {
                        offences.add(route.stream().map(MethodRef::toString)
                                .reduce((a, b) -> a + " -> " + b).orElse("?")
                                + " -> " + call.target().owner()
                                        .substring(call.target().owner().lastIndexOf('/') + 1)
                                + "." + call.target().name() + call.target().desc()
                                + "  [" + from.owner().substring(from.owner().lastIndexOf('/') + 1)
                                + ".java:" + call.line() + "]");
                    }
                    continue;
                }
                MethodRef next = call.target();
                if (!next.owner().startsWith(OURS)
                        || GUARDED_BOUNDARIES.containsKey(next.node())
                        || pathTo.containsKey(next)
                        || !graph.containsKey(next)) {
                    continue;
                }
                List<MethodRef> route = new ArrayList<>(pathTo.get(from));
                route.add(next);
                pathTo.put(next, route);
                queue.add(next);
            }
        }

        assertEquals(List.of(), offences.stream().sorted().toList(),
                "a server tick can reach a chunk accessor that waits for generation. "
                + "getChunk(...) and getWorldChunk(x, z, create) both end in "
                + "MainThreadExecutor.runTasks(future::isDone) — create=false is NOT a promise "
                + "not to block, and once a ticket exists it waits for that chunk to generate. "
                + "Probe with the two-argument getChunkManager().getWorldChunk(cx, cz) instead, "
                + "and skip a cold column");
    }

    @Test
    void theWalkActuallyReachesTheModsOwnCode() throws IOException {
        // Anti-vacuity: a graph that reached nothing would pass the rule above
        // no matter what the code did.
        Map<MethodRef, List<Call>> graph = callGraph();
        Set<String> reachedClasses = new HashSet<>();
        ArrayDeque<MethodRef> queue = new ArrayDeque<>();
        Set<MethodRef> seen = new HashSet<>();
        for (MethodRef method : graph.keySet()) {
            if (TICK_ENTRY_CLASSES.contains(method.owner())) {
                queue.add(method);
                seen.add(method);
            }
        }
        while (!queue.isEmpty()) {
            for (Call call : graph.getOrDefault(queue.poll(), List.of())) {
                MethodRef next = call.target();
                if (next.owner().startsWith(OURS) && graph.containsKey(next) && seen.add(next)) {
                    reachedClasses.add(next.owner());
                    queue.add(next);
                }
            }
        }

        assertTrue(reachedClasses.size() > 20,
                "the walk reached only " + reachedClasses.size() + " classes; it is not "
                        + "following calls and proves nothing");
        for (String expected : List.of(
                "com/customdimensions/portal/PortalHelper",
                "com/customdimensions/immersive/ImmersiveProjector",
                "com/customdimensions/immersive/ArrivalResolver",
                "com/customdimensions/dimension/ExitConditions")) {
            assertTrue(reachedClasses.contains(expected),
                    expected + " is on a tick path but the walk never reached it");
        }
    }

    @Test
    void everyGuardedBoundaryProbesBeforeItReads() throws IOException {
        // The walk stops AT a boundary, so it cannot see the guard inside one
        // go missing — every caller would still look correct while the
        // blocking read went straight back onto the tick. Checked against the
        // instruction order: the residency probe must come first.
        Map<MethodRef, List<Call>> graph = callGraph();
        for (String boundary : GUARDED_BOUNDARIES.keySet()) {
            List<Call> calls = null;
            for (Map.Entry<MethodRef, List<Call>> entry : graph.entrySet()) {
                if (entry.getKey().node().equals(boundary)) {
                    calls = entry.getValue();
                }
            }
            assertTrue(calls != null, boundary + " does not exist");

            int probe = -1;
            int read = -1;
            for (int i = 0; i < calls.size(); i++) {
                String name = calls.get(i).target().name();
                if (probe < 0 && (name.equals("residentChunk")
                        || name.equals("arrivalFootprintResident")
                        || name.equals("isColumnResident"))) {
                    probe = i;
                }
                if (read < 0 && name.equals("findSurfaceY")) {
                    read = i;
                }
            }
            assertTrue(read >= 0, boundary + " no longer performs the read it exists to guard");
            assertTrue(probe >= 0 && probe < read,
                    boundary + " reads the column before proving it resident — the blocking "
                    + "read is back on the tick path, and every caller still looks correct");
        }
    }

    /**
     * The one tick path that reads blocks nobody is standing near. Adoption
     * flood-fills through {@code World.getBlockState}, which resolves via
     * {@code getChunk(..., create = true)} and generates terrain on the calling
     * thread — a block read, so {@link #blocks} cannot see it and the walk
     * above never will. The residency probe has to be proved by order instead.
     */
    @Test
    void theApproachPassProvesResidencyBeforeItCollectsAPortalArea() throws IOException {
        Map<MethodRef, List<Call>> graph = callGraph();
        List<Call> calls = null;
        for (Map.Entry<MethodRef, List<Call>> entry : graph.entrySet()) {
            if (entry.getKey().node()
                    .equals("com/customdimensions/mixin/ServerWorldMixin#adoptPortalsOnApproach")) {
                calls = entry.getValue();
            }
        }
        assertTrue(calls != null, "the approach pass has moved or been renamed");

        int probe = -1;
        int read = -1;
        for (int i = 0; i < calls.size(); i++) {
            String name = calls.get(i).target().name();
            if (probe < 0 && name.equals("residencyOf")) {
                probe = i;
            }
            if (read < 0 && name.equals("collectPortalArea")) {
                read = i;
            }
        }
        assertTrue(read >= 0, "the approach pass no longer collects a portal area");
        assertTrue(probe >= 0 && probe < read,
                "the approach pass collects a portal area without building a residency check "
                + "first. collectPortalArea and frameBlockIds both read blocks, and on this path "
                + "no player is near enough to hold the neighbouring chunks — the fill generates "
                + "terrain on the tick and the watchdog kills the server ([K1]/[K6])");
    }

    /**
     * The other tick path that flood-fills a portal area.
     * {@code Entity.tickPortalTeleportation} reaches
     * {@code VanillaLinkResolver.recordVanillaCrossing} for a portal vanilla
     * owns, and the arrival it is handed is a column in a world nobody may be
     * standing in. The fill is bounded to
     * {@link PortalAdoption#FOOTPRINT_RADIUS}, but the START block is read
     * unconditionally — so the residency probe still has to come first.
     */
    @Test
    void theVanillaCrossingRecordProvesResidencyBeforeItCollectsAPortalArea() throws IOException {
        Map<MethodRef, List<Call>> graph = callGraph();
        List<Call> calls = null;
        for (Map.Entry<MethodRef, List<Call>> entry : graph.entrySet()) {
            if (entry.getKey().node().equals(
                    "com/customdimensions/immersive/VanillaLinkResolver#recordVanillaCrossing")) {
                calls = entry.getValue();
            }
        }
        assertTrue(calls != null, "the vanilla-crossing record has moved or been renamed");

        int probe = -1;
        int read = -1;
        for (int i = 0; i < calls.size(); i++) {
            String name = calls.get(i).target().name();
            if (probe < 0 && name.equals("isColumnResident")) {
                probe = i;
            }
            if (read < 0 && name.equals("portalAreaAround")) {
                read = i;
            }
        }
        assertTrue(read >= 0, "the vanilla-crossing record no longer collects a portal area");
        assertTrue(probe >= 0 && probe < read,
                "the arrival column is read before it is proved resident — getBlockState "
                + "resolves through getChunk(create = true) and generates terrain on the tick "
                + "([K1]/[K6])");
    }

    @Test
    void everyGuardedBoundaryStillExistsAndSaysWhy() throws IOException {
        // A boundary naming a method that no longer exists would silently stop
        // suppressing anything — or worse, hide a rename.
        Map<MethodRef, List<Call>> graph = callGraph();
        Set<String> nodes = new HashSet<>();
        for (MethodRef method : graph.keySet()) {
            nodes.add(method.node());
        }
        for (Map.Entry<String, String> boundary : GUARDED_BOUNDARIES.entrySet()) {
            assertTrue(nodes.contains(boundary.getKey()),
                    boundary.getKey() + " is declared a guarded boundary but does not exist");
            assertTrue(!boundary.getValue().isBlank(),
                    boundary.getKey() + " must say why it is safe to stop the walk there");
        }
    }
}
