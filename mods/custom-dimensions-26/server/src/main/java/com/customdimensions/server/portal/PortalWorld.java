package com.customdimensions.server.portal;

import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.portal.ArrivalSite;
import com.customdimensions.core.portal.FrameForms;
import com.customdimensions.core.portal.FrameView;
import com.customdimensions.core.portal.PortalGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Block reads and writes the portal code makes against a live level. */
public final class PortalWorld {
    /** Frames and arrivals are structural: no neighbour updates, so nothing next to them reacts. */
    public static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** Arrival height for a column with no surface (a void world); the builder lays a floor. */
    public static final int VOID_FALLBACK_Y = 64;

    private PortalWorld() {
    }

    public static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** The registered block for {@code id}, or null when no such block exists. */
    public static Block block(String id) {
        Identifier key = id == null ? null : Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getValue(key);
    }

    public static boolean isPortal(BlockState state) {
        return state.is(Portals.block());
    }

    /** Air, or one of our portal blocks: what an opening may be made of. */
    public static boolean isFillable(BlockState state) {
        return state.isAir() || state.is(Blocks.LIGHT) || isPortal(state);
    }

    /** The view ignition reads: a frame block must be an accepted form. */
    public static FrameView frameView(ServerLevel level) {
        return frameView(level, false);
    }

    /** {@code standing} also accepts covered decay (nylium to netherrack), for checking a lit frame. */
    public static FrameView frameView(ServerLevel level, boolean standing) {
        return new FrameView() {
            @Override
            public boolean isFillable(com.customdimensions.core.BlockPos pos) {
                return PortalWorld.isFillable(level.getBlockState(Pos.mc(pos)));
            }

            @Override
            public boolean matches(com.customdimensions.core.BlockPos pos, FrameForms frame) {
                String id = blockId(level.getBlockState(Pos.mc(pos)));
                return standing ? frame.keepsStanding(id) : frame.acceptsBlockId(id);
            }
        };
    }

    /** Whether every chunk {@code columns} touches is loaded now; never loads or waits. */
    public static boolean resident(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Every chunk a standard arrival at this column reads: the interior, its pad and the egress beyond. */
    public static boolean arrivalResident(ServerLevel level, int x, int z) {
        return resident(level, x - 2, z - 2, x + ArrivalSite.STANDARD_PAD + 1, z + ArrivalSite.STANDARD_PAD + 1);
    }

    /** One above the motion-blocking surface; the caller has proved the chunk resident. */
    public static int surfaceY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinY() + 1) {
            return VOID_FALLBACK_Y;
        }
        return Math.min(y, level.getMaxY() - 8);
    }

    public static ArrivalSite.Column column(ServerLevel level) {
        return new ArrivalSite.Column(level.getMinY(), level.getMaxY() + 1, level.dimensionType().hasCeiling());
    }

    public static ArrivalSite.Probe probe(ServerLevel level) {
        return new ArrivalSite.Probe() {
            @Override
            public boolean isClear(com.customdimensions.core.BlockPos pos) {
                BlockState state = level.getBlockState(Pos.mc(pos));
                return ArrivalSite.isClear(state.isAir(), state.canBeReplaced(), !state.getFluidState().isEmpty());
            }

            @Override
            public boolean isOpaque(com.customdimensions.core.BlockPos pos) {
                return level.getBlockState(Pos.mc(pos)).isSolidRender();
            }

            @Override
            public boolean isCarveable(com.customdimensions.core.BlockPos pos) {
                BlockState state = level.getBlockState(Pos.mc(pos));
                return !state.is(Blocks.BEDROCK) && !state.hasBlockEntity();
            }
        };
    }

    /** The concrete frame block for a definition: {@code framePlaceBlock}, the plain frame, else obsidian. */
    public static BlockState placeState(PortalDefinition def, String part) {
        Block block = block(part == null ? def.getFramePlaceBlock() : def.getPartPlaceBlock(part));
        if (block == null || block == Blocks.AIR) {
            block = block(def.getFramePlaceBlock());
        }
        return (block == null || block == Blocks.AIR ? Blocks.OBSIDIAN : block).defaultBlockState();
    }

    /** The forms an existing frame is checked against: the accept forms plus whatever this mod places. */
    public static FrameForms validationForms(PortalDefinition def, boolean arrival) {
        if (!arrival) {
            return def.resolveFrameForms();
        }
        List<String> forms = new ArrayList<>(def.getFrameAccepts());
        forms.add(blockId(placeState(def, null)));
        for (String part : List.of("top", "sides", "bottom")) {
            forms.add(blockId(placeState(def, part)));
        }
        return FrameForms.of(forms);
    }

    /** Fills an opening with portal blocks on its axis. */
    public static void fill(ServerLevel level, Set<BlockPos> interior, Direction.Axis axis) {
        BlockState state = Portals.block().defaultBlockState().setValue(PortalBlock.AXIS, axis);
        for (BlockPos p : interior) {
            level.setBlock(p, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Empties an opening's portal blocks; other blocks are left alone. */
    public static int clear(ServerLevel level, Set<BlockPos> interior) {
        int cleared = 0;
        for (BlockPos p : interior) {
            if (isPortal(level.getBlockState(p))) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                cleared++;
            }
        }
        return cleared;
    }

    /** Builds the frame ring around an opening, per part when the definition has parts and stands upright. */
    public static void buildFrame(ServerLevel level, Set<BlockPos> interior, Direction.Axis axis, PortalDefinition def) {
        boolean perPart = def.hasPartMaterials() && axis != Direction.Axis.Y;
        int minY = interior.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int maxY = interior.stream().mapToInt(BlockPos::getY).max().orElse(0);
        BlockState uniform = placeState(def, null);
        Set<com.customdimensions.core.BlockPos> core = new java.util.HashSet<>();
        interior.forEach(p -> core.add(Pos.core(p)));
        for (com.customdimensions.core.BlockPos ring : PortalGeometry.frameRing(core, Pos.core(axis))) {
            BlockState state = perPart
                    ? placeState(def, PortalGeometry.classifyFramePart(ring, minY, maxY)) : uniform;
            level.setBlock(Pos.mc(ring), state, QUIET);
        }
        // A floor under a flat opening, and under an upright opening's bottom row, wherever nothing solid is.
        for (BlockPos p : interior) {
            if (axis != Direction.Axis.Y && p.getY() != minY) {
                continue;
            }
            BlockPos below = p.below();
            if (!level.getBlockState(below).isSolid()) {
                level.setBlock(below, uniform, QUIET);
            }
        }
    }

    /** Empties the opening and one cell either side of it, so the arrival has a way out. */
    public static void clearArrival(ServerLevel level, Set<BlockPos> interior, Direction.Axis axis) {
        Set<com.customdimensions.core.BlockPos> core = new java.util.HashSet<>();
        interior.forEach(p -> core.add(Pos.core(p)));
        ArrivalSite.Probe probe = probe(level);
        for (com.customdimensions.core.BlockPos c : ArrivalSite.arrivalCells(core, Pos.core(axis))) {
            BlockState state = level.getBlockState(Pos.mc(c));
            if (!state.isAir() && !isPortal(state) && probe.isCarveable(c)) {
                level.setBlock(Pos.mc(c), Blocks.AIR.defaultBlockState(), QUIET);
            }
        }
    }

    public static boolean frameIntact(ServerLevel level, PortalZone zone) {
        Set<com.customdimensions.core.BlockPos> core = zone.coreInterior();
        FrameView view = frameView(level, true);
        com.customdimensions.core.Direction.Axis axis = Pos.core(zone.axis);
        if (zone.arrival) {
            return PortalGeometry.isBoundedByFrame(view, core, validationForms(zone.definition, true), axis);
        }
        return PortalGeometry.isBoundedByFrameParts(view, core, zone.definition, axis);
    }
}
