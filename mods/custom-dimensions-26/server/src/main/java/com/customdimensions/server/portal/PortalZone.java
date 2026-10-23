package com.customdimensions.server.portal;

import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.portal.PortalGeometry;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A lit opening filled with portal blocks. A source zone is one a player lit;
 * an arrival zone is the frame this mod built at the far end, whose cells
 * each carry a {@link ReturnTarget}. {@code world} is where the opening
 * stands, {@code target} the world it leads to.
 */
public final class PortalZone {
    public final Set<BlockPos> interior;
    public final PortalDefinition definition;
    public final Direction.Axis axis;
    public final ResourceKey<Level> world;
    public final ResourceKey<Level> target;
    public final boolean arrival;
    /** Single-use countdown: -1 never traversed, above 0 ticking, 0 expires this tick. */
    public int singleUseTicksLeft = -1;

    public PortalZone(Set<BlockPos> interior, PortalDefinition definition, Direction.Axis axis,
                      ResourceKey<Level> world, ResourceKey<Level> target, boolean arrival) {
        this.interior = Set.copyOf(interior);
        this.definition = definition;
        this.axis = axis;
        this.world = world;
        // A definition names the world its portal leads TO, so lighting one
        // inside that world is a way back: obsidian in the Nether leads to the overworld.
        this.target = !arrival && world.equals(target) ? Level.OVERWORLD : target;
        this.arrival = arrival;
    }

    public Set<com.customdimensions.core.BlockPos> coreInterior() {
        return interior.stream().map(Pos::core).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The opening's lowest cell in {@code BlockPos} order. */
    public BlockPos landingCell() {
        return Pos.mc(PortalGeometry.keyCell(coreInterior()));
    }

    /** Where an entity lands in this opening: its lowest cell, one up when the opening is flat. */
    public BlockPos landing() {
        BlockPos key = landingCell();
        return axis == Direction.Axis.Y ? key.above() : key;
    }

    public int[] centreColumn() {
        return PortalGeometry.centreColumn(coreInterior());
    }

    /** One arrival cell's way back. {@code exitMode} null means back to the portal that built it. */
    public record ReturnTarget(ResourceKey<Level> leadsTo, int sourceY, int color, int cooldown, String particleType,
                               String exitMode, Integer sourceX, Integer sourceZ) {
        /** Exit portals, shrines and anchors carry an exit mode and are never broken from the far end. */
        public boolean breaksSymmetrically() {
            return exitMode == null;
        }
    }
}
