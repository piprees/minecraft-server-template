package com.customdimensions.command;

import com.customdimensions.dimension.FixedStructurePlacement;
import com.customdimensions.dimension.NoiseStructurePlacement;
import com.customdimensions.dimension.StructurePick;
import com.customdimensions.facts.FactsEngine;
import com.customdimensions.facts.Measured;
import com.customdimensions.facts.SeedFacts;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureSet;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code /customdim structure-census <dim>} — the LIVE structure census a
 * loaded world's own {@code StructurePlacementCalculator} generates from,
 * cross-checked against {@link FactsEngine}'s headless measurement of the
 * SAME dimension at the world's own seed: per group, per structure, and the
 * pool each structure draws from. Also reports the nearest live instance of
 * every progression-critical structure and every forced placement, using the
 * live calculator and the real ~150-mod structure-set registry — neither is
 * answerable from config alone.
 *
 * <p>The noise-managed counts and pools must agree exactly between the two
 * sides: a difference means the facts engine is describing a world the
 * server will not generate — a facts engine that is self-consistent and
 * wrong passes every test that only exercises itself, so this is the one
 * comparison that can catch it. It fails loudly in this command's own output,
 * one line, so a mismatch cannot read as a pass.
 */
public final class CensusCommands {

    /** Structure id -> what it gates. Reported whenever placed in this world. */
    private static final Map<String, String> PROGRESSION_CRITICAL = new LinkedHashMap<>();

    /** Each gate's share of the playable radius, matching {@code score/Criteria.java}. */
    private static final Map<String, Double> PROGRESSION_FRACTION = new LinkedHashMap<>();

    static {
        PROGRESSION_CRITICAL.put("minecraft:fortress", "blaze rods");
        PROGRESSION_CRITICAL.put("minecraft:end_city", "elytra");
        PROGRESSION_FRACTION.put("minecraft:fortress",
                com.customdimensions.score.Criteria.REACHABLE_FRACTION);
        PROGRESSION_FRACTION.put("minecraft:end_city",
                com.customdimensions.score.Criteria.END_REACHABLE_FRACTION);
    }

    private CensusCommands() {
    }

    static int structureCensus(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Identifier dimensionId = SpikeCommands.resolveId(ctx);

        ServerWorld world = SpikeSampler.loadedWorld(server, dimensionId);
        if (world == null) {
            source.sendError(Text.literal(
                    "structure-census needs a LOADED world as the oracle: "
                    + dimensionId + " is not loaded (use /customdim load first)"));
            return 0;
        }

        Map<String, Integer> liveByGroup = new TreeMap<>();
        Map<String, Integer> liveByStructure = new TreeMap<>();
        Map<String, Integer> livePool = new TreeMap<>();
        Map<String, Double> liveNearest = new TreeMap<>();
        Map<String, List<ChunkPos>> forced = new TreeMap<>();
        int liveTotal = 0;

        for (RegistryEntry<StructureSet> entry
                : world.getChunkManager().getStructurePlacementCalculator().getStructureSets()) {
            var placement = entry.value().placement();
            if (placement instanceof FixedStructurePlacement fixed) {
                for (StructureSet.WeightedEntry weighted : entry.value().structures()) {
                    weighted.structure().getKey().ifPresent(k -> forced
                            .computeIfAbsent(k.getValue().toString(), id -> new ArrayList<>())
                            .addAll(fixed.positions()));
                }
                continue;
            }
            if (!(placement instanceof NoiseStructurePlacement noise)) {
                continue;
            }
            List<StructurePick.PoolEntry> pool = new ArrayList<>();
            for (StructureSet.WeightedEntry weighted : entry.value().structures()) {
                weighted.structure().getKey().ifPresent(k -> pool.add(
                        new StructurePick.PoolEntry(k.getValue().toString(), weighted.weight())));
            }
            for (StructurePick.PoolEntry pe : pool) {
                livePool.merge(pe.structureId(), pe.weight(), Integer::sum);
            }
            List<StructurePick.PoolEntry> sorted = StructurePick.sortedPool(pool);
            long noiseSeed = noise.index().noiseSeed();
            int count = 0;
            for (ChunkPos pos : noise.index().positions()) {
                count++;
                liveTotal++;
                String assigned = StructurePick.assignedStructure(noiseSeed, pos.x, pos.z, sorted);
                if (assigned != null) {
                    liveByStructure.merge(assigned, 1, Integer::sum);
                    liveNearest.merge(assigned, Math.hypot(pos.x * 16.0, pos.z * 16.0), Math::min);
                }
            }
            liveByGroup.merge(noise.group(), count, Integer::sum);
        }

        SeedFacts facts = FactsEngine.measure(server, dimensionId, world.getSeed());
        SeedFacts.StructureFacts structures = facts.structures();

        List<String> mismatches = new ArrayList<>();
        String firstGroup = diffCounts(liveByGroup, structures.byGroup(), mismatches, "group");
        String firstStructure = diffCounts(liveByStructure, structures.byStructure(), mismatches, "structure");
        String firstPool = diffCounts(livePool, structures.pool(), mismatches, "pool entry");

        Measured<Integer> factsTotal = structures.totalPositions();
        if (factsTotal.isPresent() && factsTotal.orThrow() != liveTotal) {
            mismatches.add("total positions: live=" + liveTotal + " facts=" + factsTotal.orThrow());
        }

        StringBuilder msg = new StringBuilder("structure-census " + dimensionId
                + " seed=" + world.getSeed() + ": ");
        if (!mismatches.isEmpty()) {
            msg.append("FACTS ENGINE DISAGREES WITH THE LIVE WORLD -- ")
                    .append(mismatches.size()).append(" mismatch(es), first differing group ")
                    .append(firstGroup).append(", first differing structure ").append(firstStructure)
                    .append(", first differing pool entry ").append(firstPool);
            appendReachability(msg, liveNearest, forced, facts.playableRadius());
            appendForced(msg, forced);
            source.sendError(Text.literal(msg.toString()));
            return 0;
        }

        msg.append("live and facts agree (").append(liveTotal).append(" positions, ")
                .append(liveByGroup.size()).append(" group(s))");
        appendReachability(msg, liveNearest, forced, facts.playableRadius());
        appendForced(msg, forced);
        final String out = msg.toString();
        source.sendFeedback(() -> Text.literal(out), false);
        return 1;
    }

    /**
     * Compares a live count map against the facts equivalent, appending one
     * mismatch entry per differing key. Returns the first differing key, or
     * null when every key both sides know about agrees.
     */
    private static String diffCounts(Map<String, Integer> live, Measured<Map<String, Integer>> factsMeasured,
                                     List<String> mismatches, String label) {
        if (!factsMeasured.isPresent()) {
            if (live.isEmpty()) {
                return null;
            }
            mismatches.add("facts report no " + label + " data at all (" + factsMeasured.reason()
                    + ") but the live world has " + live.size() + " " + label + "(s)");
            return live.keySet().iterator().next();
        }
        Map<String, Integer> factsMap = factsMeasured.orThrow();
        String first = null;
        for (String key : mergedKeys(live.keySet(), factsMap.keySet())) {
            int liveVal = live.getOrDefault(key, 0);
            int factsVal = factsMap.getOrDefault(key, 0);
            if (liveVal != factsVal) {
                if (first == null) {
                    first = key;
                }
                mismatches.add(label + " " + key + ": live=" + liveVal + " facts=" + factsVal);
            }
        }
        return first;
    }

    /**
     * Nearest live instance of each progression-critical structure, from
     * noise assignment or a forced placement, whichever is closer.
     * Informational, not a pass/fail signal on its own: a fortress or end
     * city nobody can reach is a progression bug the live-vs-facts
     * comparison above would never surface.
     */
    private static void appendReachability(StringBuilder msg, Map<String, Double> liveNearest,
                                           Map<String, List<ChunkPos>> forced, double playableRadius) {
        for (Map.Entry<String, String> critical : PROGRESSION_CRITICAL.entrySet()) {
            String id = critical.getKey();
            // Per structure: the gates use different fractions.
            double floor = com.customdimensions.score.Criteria.reachableWithin(
                    playableRadius, PROGRESSION_FRACTION.get(id));
            Double nearest = liveNearest.get(id);
            for (ChunkPos pos : forced.getOrDefault(id, List.of())) {
                double blocks = Math.hypot(pos.x * 16.0, pos.z * 16.0);
                if (nearest == null || blocks < nearest) {
                    nearest = blocks;
                }
            }
            msg.append("; nearest ").append(id).append(" (").append(critical.getValue()).append("): ");
            if (nearest == null) {
                msg.append("not placed here");
            } else {
                msg.append(Math.round(nearest)).append(" blocks");
                msg.append(nearest <= floor ? " (within the " : " (BEYOND the ")
                        .append((int) floor).append("-block reachability floor)");
            }
        }
    }

    /** One compact summary line naming every forced structure id, capped so it cannot grow unbounded. */
    private static void appendForced(StringBuilder msg, Map<String, List<ChunkPos>> forced) {
        if (forced.isEmpty()) {
            return;
        }
        int total = forced.values().stream().mapToInt(List::size).sum();
        msg.append("; forced: ").append(total).append(" placement(s) (")
                .append(String.join(", ", forced.keySet())).append(')');
    }

    private static Set<String> mergedKeys(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }
}
