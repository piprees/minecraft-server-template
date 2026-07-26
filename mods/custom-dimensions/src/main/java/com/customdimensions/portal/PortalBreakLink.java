package com.customdimensions.portal;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which portal is the other half of this one — the pure half of symmetric
 * breaking (Phase 9c).
 *
 * <h2>Why a portal has to break at both ends</h2>
 * A portal is one thing with two ends. Mining the frame in the overworld took
 * the source zone down and left the arrival standing in the destination:
 * still a real {@code NETHER_PORTAL}, still registered, still sending anyone
 * who walked into it back to a doorway that no longer exists. Mining the
 * arrival was the same in reverse — the source zone stayed live and rebuilt a
 * fresh arrival on the next traversal, so the portal the player had just
 * destroyed came straight back somewhere near where it was. Neither end could
 * be got rid of from the end you were standing at.
 *
 * <h2>How the two ends find each other</h2>
 * Not by geometry. The arrival's column is {@code source / scale} and
 * recovering the source from it would mean multiplying a rounded number back
 * up and searching a box — the arithmetic that has already been wrong twice
 * in this file's history. Every arrival cell instead carries the column it was
 * built FOR, stamped by {@link PortalHelper#setSourceColumn} at creation:
 * {@code sourceWorld}, {@code sourceX}, {@code sourceZ}. Matching is then an
 * exact equality on values somebody else already computed, in both directions.
 *
 * <h2>What must NOT break symmetrically</h2>
 * <ul>
 *   <li><b>Anchor dimensions.</b> Many source portals share one arrival, so
 *       one player mining their own frame would take away everybody's way
 *       home. Guarded explicitly on the source side by
 *       {@code definition.hasAnchor()}; on the arrival side it falls out for
 *       free, because the anchor path never calls {@code setSourceColumn} and
 *       so an anchor arrival has no source column to resolve. Both, because an
 *       invariant this sharp should not rest on an absence alone.</li>
 *   <li><b>Single-use expiry.</b> "The way in crumbles behind you" must not
 *       crumble the way HOME. That is why symmetric breaking is triggered at
 *       the two places a player actually breaks something, and never from
 *       {@link PortalHelper#removeZone}, which single-use expiry also calls.</li>
 *   <li><b>Exit portals and exit shrines.</b> They carry an {@code exitMode}
 *       and are the mod's own guaranteed way out; they are not one end of a
 *       player-built pair.</li>
 * </ul>
 *
 * <p>Pure: plain values in, plain values out, no world and no Minecraft
 * runtime. The caller reads the registry and writes the blocks.
 */
public final class PortalBreakLink {

    private PortalBreakLink() {
    }

    /**
     * The COLUMN a portal interior is centred on, as
     * {@code (x, z)}.
     *
     * <p>Integer-averaged, and shared with the traversal path deliberately:
     * {@code ServerWorldMixin} computes the arrival column from this exact
     * expression, and {@code setSourceColumn} stamps that value onto every
     * arrival cell. If the two ever drifted, symmetric breaking would silently
     * match nothing — which looks precisely like the bug it is meant to fix.
     * One definition, no chance of drift.
     *
     * @return {@code {x, z}}, or null for an empty interior
     */
    public static int[] centreColumn(Collection<BlockPos> interior) {
        if (interior == null || interior.isEmpty()) {
            return null;
        }
        int x = 0;
        int z = 0;
        for (BlockPos p : interior) {
            x += p.getX();
            z += p.getZ();
        }
        int count = interior.size();
        return new int[]{x / count, z / count};
    }

    /**
     * Every registered arrival cell in one world that was built for the source
     * portal at {@code (sourceX, sourceZ)} in {@code sourceWorld}.
     *
     * <p>Records with no source column (written before it existed, or by the
     * anchor path which never stamps one) match nothing. That is the correct
     * degradation in both cases: an unstamped record cannot be attributed to a
     * source, and guessing would take down a portal somebody else built.
     */
    public static Set<BlockPos> arrivalCellsFor(
            Map<BlockPos, PortalHelper.PortalReturnTarget> targets,
            RegistryKey<World> sourceWorld, int sourceX, int sourceZ) {
        Set<BlockPos> out = new HashSet<>();
        if (targets == null || sourceWorld == null) {
            return out;
        }
        for (Map.Entry<BlockPos, PortalHelper.PortalReturnTarget> entry : targets.entrySet()) {
            PortalHelper.PortalReturnTarget target = entry.getValue();
            if (isLinkedTo(target, sourceWorld, sourceX, sourceZ)) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /**
     * Is this arrival one end of the player-built pair whose other end is the
     * source portal at {@code (sourceX, sourceZ)} in {@code sourceWorld}?
     */
    public static boolean isLinkedTo(PortalHelper.PortalReturnTarget target,
            RegistryKey<World> sourceWorld, int sourceX, int sourceZ) {
        if (target == null || target.sourceX == null || target.sourceZ == null) {
            return false;
        }
        if (!breaksSymmetrically(target)) {
            return false;
        }
        return sourceWorld.equals(target.sourceWorld)
                && target.sourceX == sourceX && target.sourceZ == sourceZ;
    }

    /**
     * May this arrival take its counterpart down with it?
     *
     * <p>No for anything carrying an {@code exitMode}: anchor arrivals (shared
     * by every source portal into that dimension), exit portals and exit
     * shrines (the mod's own guaranteed way home, not half of a pair).
     */
    public static boolean breaksSymmetrically(PortalHelper.PortalReturnTarget target) {
        return target != null && target.exitMode == null;
    }

    /**
     * Is this zone the source end of the arrival that was just broken?
     *
     * <p>Compares the zone's own centre column against the column the arrival
     * recorded, so the match is exact rather than a proximity search.
     */
    public static boolean zoneMatchesColumn(Collection<BlockPos> interior,
            RegistryKey<World> zoneTargetWorld, RegistryKey<World> arrivalWorld,
            Integer sourceX, Integer sourceZ) {
        if (sourceX == null || sourceZ == null || zoneTargetWorld == null) {
            return false;
        }
        if (!zoneTargetWorld.equals(arrivalWorld)) {
            return false;
        }
        int[] centre = centreColumn(interior);
        return centre != null && centre[0] == sourceX && centre[1] == sourceZ;
    }
}
