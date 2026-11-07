package com.customdimensions.server.portal;

import com.customdimensions.core.Id;
import com.customdimensions.core.config.MultiverseConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.portal.Aura;
import com.customdimensions.core.portal.AuraPolicy;
import com.customdimensions.core.portal.AuraState;
import com.customdimensions.core.portal.PortalShape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The aura part of the portal self-test (portals.md § Checks), run on the
 * server thread in one go. Each bay is a lit portal into a {@code none}, a
 * {@code natural} or an {@code everything} dimension, standing in a solid
 * cobblestone box with planks (crafted) and stone (natural) set into it; a
 * second set stands in claimed chunks. Passes are driven directly with fixed
 * seeds. The bays' chunks are loaded by the test, never by an aura.
 */
final class AuraSelfTest {
    static final int ROW_X = 4104;
    static final int ROW_Z = 4104;
    static final int Y = 200;
    static final int BAY_SPACING = 32;
    static final int PASSES = 300;
    static final String PALETTE_BLOCK = "minecraft:netherrack";
    static final UUID CLAIMANT = UUID.fromString("00000000-0000-4000-8000-00000000a0a0");
    static final int PERSIST_SOURCE_SPENT = 37;
    static final int PERSIST_SITE_SPENT = 23;
    static final List<String> POLICIES = List.of(AuraPolicy.NONE, AuraPolicy.NATURAL, AuraPolicy.EVERYTHING);

    private final ServerLevel level;
    private final List<String> lines = new ArrayList<>();
    private final List<Bay> bays = new ArrayList<>();
    private int nextBay;

    /** A lit test bay: its zone, the ring as built and the box as filled. */
    private record Bay(PortalZone zone, Map<BlockPos, BlockState> ring, Map<BlockPos, BlockState> box, boolean claimed) {}

    private AuraSelfTest(MinecraftServer server) {
        this.level = server.overworld();
    }

    static List<String> run(MinecraftServer server) {
        AuraSelfTest test = new AuraSelfTest(server);
        try {
            test.checks();
        } catch (Throwable t) {
            test.lines.add("FAIL portal auras: the checks threw: " + t);
        } finally {
            test.cleanUp();
        }
        return test.lines;
    }

    private void checks() {
        Map<String, PortalDefinition> defs = new HashMap<>();
        for (String policy : POLICIES) {
            PortalDefinition def = definitionFor(policy);
            if (def == null) {
                lines.add("FAIL portal auras: no shipped portal leads to a '" + policy + "' dimension");
                return;
            }
            defs.put(policy, def);
        }
        PortalDefinition.AuraSettings settings = settings(4, 16, -1);
        boolean claims = ClaimsCompat.isAvailable();
        for (String policy : POLICIES) {
            for (boolean claimed : new boolean[]{false, true}) {
                Bay bay = bay(defs.get(policy), claimed && claims);
                Random random = new Random(POLICIES.indexOf(policy) * 2L + (claimed ? 1 : 0));
                for (int i = 0; i < PASSES; i++) {
                    PortalAuras.runSource(level, bay.zone(), settings, random);
                }
            }
        }
        lines.add(policyLine(AuraPolicy.NONE, defs));
        lines.add(policyLine(AuraPolicy.NATURAL, defs));
        lines.add(policyLine(AuraPolicy.EVERYTHING, defs));
        lines.add(claimLine(claims));
        lines.add(frameLine());
        lines.add(budgetLine(defs.get(AuraPolicy.EVERYTHING)));
        lines.add(linkLine(defs.get(AuraPolicy.NONE)));
        lines.add(fallbackLine());
    }

    /** The first shipped portal whose target's live policy is {@code policy}, with a standard opening. */
    private static PortalDefinition definitionFor(String policy) {
        for (PortalDefinition def : MultiverseConfig.getInstance().getPortals()) {
            if (def.isVanillaManaged() || !def.isAuraEnabled() || def.hasAnchor() || def.getTargetDimension() == null
                    || !PortalShape.STANDARD.equals(PortalShape.normalise(def.getShape()))) {
                continue;
            }
            String live = MultiverseConfig.getInstance().getAuraSubsumeFor(Id.parse(def.getTargetDimension()));
            if (policy.equals(live)) {
                return def;
            }
        }
        return null;
    }

    private static PortalDefinition.AuraSettings settings(int radius, int perPass, int budget) {
        PortalDefinition.AuraSettings s = new PortalDefinition.AuraSettings();
        s.radius = radius;
        s.blocksPerPass = perPass;
        s.budget = budget;
        return s;
    }

    /** Fills the pass volume with cobblestone, sets planks and stone in it, then builds and lights the portal. */
    private Bay bay(PortalDefinition def, boolean claimed) {
        BlockPos at = new BlockPos(ROW_X + nextBay++ * BAY_SPACING, Y, ROW_Z);
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        BlockPos centre = at.above();
        Map<BlockPos, BlockState> box = new HashMap<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 5; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos p = centre.offset(dx, dy, dz);
                    level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), PortalWorld.QUIET);
                }
            }
        }
        for (BlockPos p : List.of(centre.offset(3, 0, 3), centre.offset(-3, 2, 3), centre.offset(2, -3, -3))) {
            level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), PortalWorld.QUIET);
        }
        for (BlockPos p : List.of(centre.offset(-3, 1, -3), centre.offset(3, 4, -2), centre.offset(-2, -2, 3))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), PortalWorld.QUIET);
        }
        PortalSelfTest.Bay built = PortalSelfTest.build(level, def, Direction.Axis.X, at, null);
        PortalZone zone = new PortalZone(built.interior(), def, Direction.Axis.X, level.dimension(),
                PortalRegistry.key(def.getTargetDimension()), false);
        PortalRegistry.get().addIfAbsent(zone);
        PortalWorld.fill(level, zone.interior, zone.axis);
        zone.aura = new AuraState(List.of(PALETTE_BLOCK), List.of(), List.of(), List.of(), 0);
        Map<BlockPos, BlockState> ring = new HashMap<>();
        built.ring().forEach(p -> ring.put(p, level.getBlockState(p)));
        Set<com.customdimensions.core.BlockPos> excluded = Aura.exclusion(zone.coreInterior());
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 5; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos p = centre.offset(dx, dy, dz);
                    if (!excluded.contains(Pos.core(p))) {
                        box.put(p, level.getBlockState(p));
                    }
                }
            }
        }
        if (claimed && !ClaimsCompat.claim(level, at, CLAIMANT)) {
            throw new IllegalStateException("could not claim the chunk at " + at.toShortString());
        }
        Bay bay = new Bay(zone, ring, box, claimed);
        bays.add(bay);
        return bay;
    }

    private Bay find(PortalDefinition def, boolean claimed) {
        return bays.stream().filter(b -> b.zone().definition == def && b.claimed() == claimed).findFirst().orElseThrow();
    }

    /** Cells of the box now different from what was filled, split by what they held. */
    private int[] changes(Bay bay) {
        int cobble = 0;
        int planks = 0;
        int stone = 0;
        for (Map.Entry<BlockPos, BlockState> e : bay.box().entrySet()) {
            BlockState now = level.getBlockState(e.getKey());
            if (now.equals(e.getValue())) {
                continue;
            }
            Block was = e.getValue().getBlock();
            if (was == Blocks.OAK_PLANKS) planks++;
            else if (was == Blocks.STONE) stone++;
            else cobble++;
        }
        return new int[]{cobble, planks, stone};
    }

    private String policyLine(String policy, Map<String, PortalDefinition> defs) {
        PortalDefinition def = defs.get(policy);
        Bay bay = find(def, false);
        int[] c = changes(bay);
        int cells = bay.box().size();
        String name = "aura subsume " + policy + " (" + def.getTargetDimension() + ")";
        String counts = c[0] + " cobblestone, " + c[1] + "/3 planks, " + c[2] + "/3 stone changed of " + cells
                + " cells after " + PASSES + " passes";
        boolean ok = switch (policy) {
            case AuraPolicy.NONE -> c[0] == 0 && c[1] == 0 && c[2] == 0;
            case AuraPolicy.NATURAL -> c[0] == 0 && c[1] == 0 && c[2] == 3;
            default -> c[0] > (cells - 6) / 2 && c[2] == 3;
        };
        return (ok ? "PASS " : "FAIL ") + name + ": " + counts;
    }

    private String claimLine(boolean claims) {
        String name = "aura claim veto holds under none, natural and everything";
        if (!claims) {
            return "FAIL " + name + ": Open Parties and Claims' API is not available";
        }
        List<String> touched = new ArrayList<>();
        int cells = 0;
        for (Bay bay : bays) {
            if (!bay.claimed()) continue;
            int[] c = changes(bay);
            cells += bay.box().size();
            if (c[0] + c[1] + c[2] > 0) touched.add(bay.zone().definition.getTargetDimension());
        }
        return touched.isEmpty() ? "PASS " + name + ": " + cells + " claimed cells untouched across 3 bays"
                : "FAIL " + name + ": claimed cells changed beside " + touched;
    }

    private String frameLine() {
        int ringCells = 0;
        int openCells = 0;
        for (Bay bay : bays) {
            for (Map.Entry<BlockPos, BlockState> e : bay.ring().entrySet()) {
                if (!level.getBlockState(e.getKey()).equals(e.getValue())) {
                    return "FAIL aura never touches frame or opening: frame block at " + e.getKey().toShortString()
                            + " is " + level.getBlockState(e.getKey());
                }
                ringCells++;
            }
            for (BlockPos p : bay.zone().interior) {
                if (!PortalWorld.isPortal(level.getBlockState(p))) {
                    return "FAIL aura never touches frame or opening: opening cell " + p.toShortString() + " is "
                            + level.getBlockState(p);
                }
                openCells++;
            }
        }
        return "PASS aura never touches frame or opening: " + ringCells + " frame and " + openCells
                + " portal cells intact after " + PASSES * bays.size() + " passes";
    }

    private String budgetLine(PortalDefinition def) {
        String name = "aura budget stops each pass and the lifetime";
        Bay bay = bay(def, false);
        PortalDefinition.AuraSettings perPass = settings(4, 3, -1);
        int most = 0;
        for (int i = 0; i < 20; i++) {
            int spent = PortalAuras.runSource(level, bay.zone(), perPass, new Random(100 + i));
            if (spent > 3) return "FAIL " + name + ": a pass spent " + spent + " with blocksPerPass 3";
            most = Math.max(most, spent);
        }
        int before = bay.zone().aura.spent;
        PortalDefinition.AuraSettings lifetime = settings(4, 16, before + 10);
        int total = 0;
        for (int i = 0; i < 50; i++) {
            total += PortalAuras.runSource(level, bay.zone(), lifetime, new Random(200 + i));
        }
        int[] changed = changes(bay);
        int after = PortalAuras.runSource(level, bay.zone(), lifetime, new Random(999));
        boolean ok = most == 3 && total == 10 && bay.zone().aura.spent == before + 10 && after == 0
                && changes(bay)[0] == changed[0];
        return (ok ? "PASS " : "FAIL ") + name + ": per-pass most " + most + "/3; lifetime " + total
                + "/10 spent over 50 passes, then " + after;
    }

    /** onLink samples both ends: the box around the far opening, and the source's own surroundings. */
    private String linkLine(PortalDefinition def) {
        String name = "aura link samples both ends";
        Bay source = find(def, false);
        Bay far = bay(def, false);
        source.zone().aura = null;
        PortalAuras.onLink(level, source.zone(), level, far.zone().interior);
        AuraState near = source.zone().aura;
        PortalRegistry.AuraSite site = PortalRegistry.get().auraSite(level.dimension(),
                Pos.mc(Aura.minCorner(far.zone().coreInterior())));
        boolean ok = near != null && near.terrain.contains("minecraft:cobblestone") && site != null
                && !site.state().terrain.isEmpty();
        return (ok ? "PASS " : "FAIL ") + name + ": source palette " + (near == null ? null : near.terrain)
                + ", far site " + (site == null ? null : site.state().terrain);
    }

    /** A side with nothing resident to sample takes its definition's default block and loads no chunk. */
    private String fallbackLine() {
        String name = "aura falls back to the definition palette without loading a chunk";
        ResourceKey<Level> basalt = PortalRegistry.key("adventure:the_basalt_spires");
        String fallback = PortalAuras.fallbackBlock(basalt);
        com.customdimensions.core.BlockPos cold = new com.customdimensions.core.BlockPos(900_008, 64, 900_008);
        boolean wasCold = level.getChunkSource().getChunkNow(cold.getX() >> 4, cold.getZ() >> 4) == null;
        AuraState state = Aura.sample(new LevelAuraWorld(level), cold, Set.of(), Set.of(), fallback);
        boolean stillCold = level.getChunkSource().getChunkNow(cold.getX() >> 4, cold.getZ() >> 4) == null;
        boolean ok = "minecraft:blackstone".equals(fallback) && List.of(fallback).equals(state.terrain) && wasCold
                && stillCold;
        return (ok ? "PASS " : "FAIL ") + name + ": the_basalt_spires falls back to " + fallback + ", sampled "
                + state.terrain + ", chunk " + (stillCold ? "still unloaded" : "loaded");
    }

    /**
     * The two-boot check's write half: the link sampled both ends, and each
     * side's spent budget is set to a known count before the save.
     */
    static String persistWrite(PortalZone source, PortalZone arrival) {
        PortalRegistry.AuraSite site = PortalRegistry.get().auraSite(arrival.world,
                Pos.mc(Aura.minCorner(arrival.coreInterior())));
        if (source.aura == null || site == null) {
            throw new IllegalStateException("the link recorded no aura (source " + source.aura + ", site " + site + ")");
        }
        source.aura.spent = Math.max(source.aura.spent, PERSIST_SOURCE_SPENT);
        site.state().spent = Math.max(site.state().spent, PERSIST_SITE_SPENT);
        return source.aura.spent + " " + site.state().spent;
    }

    /** The verify half: both counters resumed at or above what was written, never from zero. */
    static String persistVerify(PortalZone source, ResourceKey<Level> target, BlockPos arrivalCell, String recorded) {
        String[] parts = recorded.trim().split(" ");
        if (parts.length != 2) {
            throw new IllegalStateException("no aura line in the persist file; run write first");
        }
        int sourceSpent = Integer.parseInt(parts[0]);
        int siteSpent = Integer.parseInt(parts[1]);
        PortalZone arrival = PortalRegistry.get().zoneAt(target, arrivalCell);
        PortalRegistry.AuraSite site = arrival == null ? null
                : PortalRegistry.get().auraSite(target, Pos.mc(Aura.minCorner(arrival.coreInterior())));
        if (source.aura == null || source.aura.spent < sourceSpent) {
            throw new IllegalStateException("source aura budget " + (source.aura == null ? "lost" : source.aura.spent)
                    + " after restart, wrote " + sourceSpent);
        }
        if (site == null || site.state().spent < siteSpent) {
            throw new IllegalStateException("arrival aura budget " + (site == null ? "lost" : site.state().spent)
                    + " after restart, wrote " + siteSpent);
        }
        return "aura budgets resumed at " + source.aura.spent + " and " + site.state().spent + " (wrote " + sourceSpent
                + " and " + siteSpent + ")";
    }

    private void cleanUp() {
        PortalRegistry registry = PortalRegistry.get();
        for (Bay bay : bays) {
            registry.remove(bay.zone());
            PortalWorld.clear(level, bay.zone().interior);
            if (bay.claimed()) {
                ClaimsCompat.unclaim(level, bay.zone().landingCell());
            }
        }
        registry.save();
    }
}
