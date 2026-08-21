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
 * breaking.
 *
 * <p>A portal is one thing with two ends: breaking only one leaves the other
 * as a real, registered portal pointing at a doorway that no longer exists
 * (or, for the source side, one that rebuilds the far end on the next
 * traversal). Both ends must break together.
 *
 * <p>The two ends are matched not by geometry but by a stamped source
 * column: every arrival cell carries the {@code sourceWorld}/
 * {@code sourceX}/{@code sourceZ} it was built for
 * ({@link PortalHelper#setSourceColumn}), so matching is exact equality, not
 * reconstructing a rounded division.
 *
 * <p>Anchor dimensions (many source portals share one arrival — guarded by
 * {@code definition.hasAnchor()} and by the anchor path never stamping a
 * source column), single-use expiry (triggered only where a player actually
 * breaks something, never from {@link PortalHelper#removeZone}, which
 * expiry also calls), and exit portals/shrines (which carry an
 * {@code exitMode}) must NOT break symmetrically — see
 * {@link #breaksSymmetrically}.
 *
 * <p>Pure: plain values in, plain values out, no world and no Minecraft
 * runtime. The caller reads the registry and writes the blocks.
 */
public final class PortalBreakLink {

    private PortalBreakLink() {
    }

    /**
     * The column a portal interior is centred on, as {@code (x, z)}.
     * Integer-averaged, using the exact same expression {@code
     * ServerWorldMixin} uses to compute the arrival column — if the two ever
     * drifted, symmetric breaking would silently match nothing.
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
