package com.customdimensions.server.portal;

import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.portal.PortalDecay;
import com.customdimensions.core.portal.PortalGeometry;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

/**
 * The portal subsystem's wiring: the block, the ignition event, the registry's
 * load and save, and the per-level pass that keeps lit portals honest —
 * single-use countdowns, particles, a frame check, cells waiting to be cleared
 * and exit portals.
 * <p>
 * A portal is one thing with two ends. A broken source frame takes its
 * arrival with it and a broken arrival frame closes its source, except for
 * anchors and exit portals, which many sources share. Single-use expiry
 * never touches the far end: the way in crumbles, the way home stays.
 */
public final class Portals {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(CustomDimensions.ID, "portal");
    /** Vanilla's portal violet: the way back to a world with no portal of its own. */
    public static final int NEUTRAL_COLOR = 0x8844FF;
    static final int PARTICLE_INTERVAL = 10;
    static final int VALIDATE_INTERVAL = 20;

    private static PortalBlock block;

    private Portals() {
    }

    public static PortalBlock block() {
        return block;
    }

    public static void register() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, ID);
        block = Registry.register(BuiltInRegistries.BLOCK, key, new PortalBlock(BlockBehaviour.Properties.of()
                .setId(key).noCollision().strength(-1.0f, 3600000.0f).noLootTable().sound(SoundType.GLASS)
                .lightLevel(state -> 11).pushReaction(PushReaction.IMMOVEABLE)));
        PortalTickets.register();
        Ignition.register();
        LevelManager.onClose(PortalTickets::release);
        LevelManager.onDelete(PortalRegistry.get()::forgetWorld);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LevelManager.get().ifPresent(m -> PortalCatalogue.load(m.index()));
            PortalRegistry.get().load(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PortalRegistry.get().save();
            PortalRegistry.get().clear();
            PortalTickets.clear();
            ExitPortals.clear();
        });
        ServerTickEvents.END_LEVEL_TICK.register(Portals::tick);
    }

    private static void tick(ServerLevel level) {
        long time = level.getGameTime();
        PortalRegistry registry = PortalRegistry.get();
        for (PortalZone zone : registry.zones(level.dimension())) {
            if (!registry.contains(zone) || !loaded(level, zone)) {
                continue;
            }
            if (zone.singleUseTicksLeft > 0) {
                zone.singleUseTicksLeft--;
            }
            if (zone.singleUseTicksLeft == 0) {
                expireSingleUse(level, zone);
                continue;
            }
            if (time % VALIDATE_INTERVAL == 0) {
                validate(level, zone);
            }
            if (time % PARTICLE_INTERVAL == 0) {
                particles(level, zone);
            }
        }
        clearPending(level);
        ExitPortals.tick(level);
    }

    /** Every chunk under the opening and its frame is resident, so checking it loads nothing. */
    static boolean loaded(ServerLevel level, PortalZone zone) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : zone.interior) {
            minX = Math.min(minX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return PortalWorld.resident(level, minX - 1, minZ - 1, maxX + 1, maxZ + 1);
    }

    /** A neighbour of a portal block changed: check its zone, and clear the block if no zone owns it. */
    static void validateAt(ServerLevel level, BlockPos pos) {
        PortalZone zone = PortalRegistry.get().zoneAt(level.dimension(), pos);
        if (zone == null) {
            if (PortalWorld.isPortal(level.getBlockState(pos))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            return;
        }
        validate(level, zone);
    }

    /**
     * The frame stands and every cell is a portal block, or the zone breaks.
     * A frame that stands around an empty opening (a 1.21.1 zone, whose
     * openings held nothing) is filled instead.
     */
    static void validate(ServerLevel level, PortalZone zone) {
        if (!PortalWorld.frameIntact(level, zone)) {
            breakZone(level, zone);
            return;
        }
        boolean empty = true;
        boolean full = true;
        for (BlockPos p : zone.interior) {
            BlockState state = level.getBlockState(p);
            boolean portal = PortalWorld.isPortal(state);
            empty &= state.isAir();
            full &= portal;
        }
        if (empty) {
            PortalWorld.fill(level, zone.interior, zone.axis);
        } else if (!full) {
            breakZone(level, zone);
        }
    }

    /** Breaks a zone at both ends, as a player breaking its frame does. */
    public static void breakZone(ServerLevel level, PortalZone zone) {
        PortalRegistry registry = PortalRegistry.get();
        if (zone.arrival) {
            closeArrival(level, zone);
        } else {
            breakLinkedArrival(level, zone);
            PortalWorld.clear(level, zone.interior);
            registry.remove(zone);
            CustomDimensions.LOG.info("portal broken in {} at {}", level.dimension().identifier(), zone.landingCell());
        }
        registry.save();
    }

    /** Clears the arrival a source zone built, unless it is an anchor many sources share. */
    private static void breakLinkedArrival(ServerLevel level, PortalZone zone) {
        if (zone.definition != null && zone.definition.hasAnchor()) {
            return;
        }
        int[] centre = zone.centreColumn();
        PortalRegistry registry = PortalRegistry.get();
        Set<BlockPos> cells = registry.arrivalCellsFor(zone.target, zone.world, centre[0], centre[1]);
        if (cells.isEmpty()) {
            return;
        }
        registry.removeTargets(zone.target, cells);
        PortalZone arrival = registry.zoneAt(zone.target, cells.iterator().next());
        if (arrival != null) {
            registry.remove(arrival);
        }
        clearOrQueue(level.getServer().getLevel(zone.target), zone.target, cells);
        CustomDimensions.LOG.info("Source portal broken in {}; closed its arrival in {} ({} cells)",
                zone.world.identifier(), zone.target.identifier(), cells.size());
    }

    /** Closes an arrival and the source zone that built it; the source frame stays, its opening goes. */
    private static void closeArrival(ServerLevel level, PortalZone arrival) {
        PortalRegistry registry = PortalRegistry.get();
        PortalZone.ReturnTarget t = registry.target(level.dimension(), arrival.landingCell());
        registry.removeTargets(level.dimension(), arrival.interior);
        registry.remove(arrival);
        PortalWorld.clear(level, arrival.interior);
        if (t == null || !t.breaksSymmetrically() || t.sourceX() == null) {
            return;
        }
        List<PortalZone> matched = new ArrayList<>();
        for (PortalZone source : registry.zones(t.leadsTo())) {
            if (source.arrival || (source.definition != null && source.definition.hasAnchor())
                    || !source.target.equals(level.dimension())) {
                continue;
            }
            int[] c = source.centreColumn();
            if (c[0] == t.sourceX() && c[1] == t.sourceZ()) {
                matched.add(source);
            }
        }
        for (PortalZone source : matched) {
            registry.remove(source);
            clearOrQueue(level.getServer().getLevel(source.world), source.world, source.interior);
        }
        CustomDimensions.LOG.info("Arrival portal broken in {}; closed {} source portal(s) in {}",
                level.dimension().identifier(), matched.size(), t.leadsTo().identifier());
    }

    /** Clears cells in resident chunks now and queues the rest; never loads a chunk to do it. */
    private static void clearOrQueue(ServerLevel level, ResourceKey<Level> key, Set<BlockPos> cells) {
        Set<BlockPos> cold = new HashSet<>();
        for (BlockPos p : cells) {
            if (level != null && level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) != null) {
                PortalWorld.clear(level, Set.of(p));
            } else {
                cold.add(p);
            }
        }
        if (!cold.isEmpty()) {
            PortalRegistry.get().queueClear(key, cold);
        }
    }

    private static void clearPending(ServerLevel level) {
        Set<BlockPos> pending = PortalRegistry.get().pendingClears(level.dimension());
        if (pending.isEmpty()) {
            return;
        }
        pending.removeIf(p -> {
            if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) {
                return false;
            }
            if (PortalRegistry.get().zoneAt(level.dimension(), p) == null) {
                PortalWorld.clear(level, Set.of(p));
            }
            return true;
        });
    }

    /** Arms a single-use zone's countdown at its first traversal; written at once so a restart resumes it. */
    static void startSingleUse(PortalZone zone) {
        if (zone.definition == null || !zone.definition.isSingleUse() || zone.singleUseTicksLeft >= 0) {
            return;
        }
        zone.singleUseTicksLeft = Math.max(1, zone.definition.getSingleUseDelayTicks());
        PortalRegistry.get().save();
    }

    /** The countdown reached zero: the opening empties and the frame breaks per {@code breakMode}. */
    static void expireSingleUse(ServerLevel level, PortalZone zone) {
        PortalWorld.clear(level, zone.interior);
        PortalRegistry.get().remove(zone);
        Set<com.customdimensions.core.BlockPos> core = zone.coreInterior();
        List<com.customdimensions.core.BlockPos> ring = PortalGeometry.frameRing(core, Pos.core(zone.axis));
        String mode = zone.definition.getSingleUseBreakMode();
        if ("partial".equals(mode) && !ring.isEmpty()) {
            com.customdimensions.core.BlockPos first = ring.get(0);
            for (int index : PortalDecay.pickPartialIndices(ring.size(),
                    PortalDecay.partialSeed(first.x(), first.y(), first.z()))) {
                decay(level, Pos.mc(ring.get(index)), zone.definition, true);
            }
        } else if ("destroy".equals(mode)) {
            ring.forEach(p -> level.destroyBlock(Pos.mc(p), false));
        } else {
            ring.forEach(p -> decay(level, Pos.mc(p), zone.definition, false));
        }
        PortalRegistry.get().save();
        CustomDimensions.LOG.info("Single-use portal expired in {} ({} mode)", zone.world.identifier(), mode);
    }

    /** Swaps one frame block for its decayed form; an unmapped block is removed only in partial mode. */
    private static void decay(ServerLevel level, BlockPos pos, PortalDefinition def, boolean removeUnmapped) {
        BlockState state = level.getBlockState(pos);
        String replacement = PortalDecay.resolve(PortalWorld.blockId(state), def.getSingleUseDecayMap());
        if (replacement == null) {
            if (removeUnmapped) {
                level.destroyBlock(pos, false);
            }
            return;
        }
        Block to = PortalWorld.block(replacement);
        if (to == null) {
            return;
        }
        level.levelEvent(2001, pos, Block.getId(state));
        level.setBlock(pos, to.defaultBlockState(), PortalWorld.QUIET);
    }

    private static void particles(ServerLevel level, PortalZone zone) {
        if (level.players().isEmpty()) {
            return;
        }
        int color = parseColor(zone.definition.getColor());
        String type = zone.definition.getParticleType();
        if (zone.arrival) {
            PortalZone.ReturnTarget t = PortalRegistry.get().target(level.dimension(), zone.landingCell());
            if (t != null) {
                color = t.color();
                type = t.particleType();
            }
        }
        ParticleOptions effect = particle(type, color);
        for (BlockPos p : zone.interior) {
            level.sendParticles(effect, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1, 0.3, 0.3, 0.3, 0.01);
        }
    }

    /** A simple particle by id, else dust in the portal's colour. */
    static ParticleOptions particle(String type, int color) {
        Identifier id = type == null ? null : Identifier.tryParse(type);
        if (id != null && BuiltInRegistries.PARTICLE_TYPE.getValue(id) instanceof SimpleParticleType simple) {
            return simple;
        }
        return new DustParticleOptions(color, 1.0f);
    }

    public static int parseColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return NEUTRAL_COLOR;
        }
        try {
            return Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return NEUTRAL_COLOR;
        }
    }

    static void playSound(ServerLevel level, BlockPos pos, String name) {
        Identifier id = name == null ? null : Identifier.tryParse(name);
        if (id == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(id);
        if (sound != null) {
            level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0f, 1.0f);
        }
    }
}
