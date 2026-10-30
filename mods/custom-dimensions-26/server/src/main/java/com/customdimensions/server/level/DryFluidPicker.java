package com.customdimensions.server.level;

import com.customdimensions.core.zone.ZoneMask;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.synth.Noise;

import java.util.List;

/**
 * A level's global fluid rule with no sea and no lava wherever a dry zone's
 * compiled (jittered) mask is above zero, and {@link #MARGIN} blocks beyond:
 * the mask is {@code clamp(1 - (outside + jitter) / blend)}, so a column is
 * dry where {@code outside + jitter < blend + MARGIN}, with the jitter read
 * from the same noise the compiled mask reads under the level's seed. The
 * generator places lava below its lava level before any aquifer is
 * consulted, and an aquifer cell takes the sea from the cells round it, so
 * the picker answers air at a covered point and the aquifer turns any fluid it
 * would still place in a covered column into air ({@code NoiseBasedAquiferMixin}).
 * Springs and lakes, which features place after the noise, are left out where
 * the rule covers them ({@code DryFluidFeatureMixin}).
 */
public final class DryFluidPicker implements Aquifer.FluidPicker {

    /**
     * Blocks beyond the compiled mask's edge that stay dry: one aquifer cell, more than water's seven-block
     * spread, so the sea that ends there cannot flow into a column the mask covers.
     */
    public static final double MARGIN = 16;

    /** One dry zone: its still-edge mask and its jitter noise in blocks ({@code null} for a still edge). */
    public record Zone(ZoneMask still, Noise jitter, double blocksPerUnit) {
        /** The farthest beyond the still edge a covered column lies: the blend, the noise's full range and the margin. */
        double reach() {
            double range = jitter == null ? 0 : Math.max(Math.abs(jitter.range().min()), Math.abs(jitter.range().max()));
            return still.rampWidth() + range * blocksPerUnit + MARGIN + 1;
        }
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final Aquifer.FluidPicker beneath;
    private final List<Zone> zones;
    private final double[] reach;

    public DryFluidPicker(Aquifer.FluidPicker beneath, List<Zone> zones) {
        this.beneath = beneath;
        this.zones = List.copyOf(zones);
        this.reach = this.zones.stream().mapToDouble(Zone::reach).toArray();
    }

    /** Whether the column lies under a dry zone's compiled mask or within {@link #MARGIN} blocks of it. */
    public boolean covers(int x, int z) {
        for (int i = 0; i < zones.size(); i++) {
            Zone zone = zones.get(i);
            ZoneMask m = zone.still();
            double outside = m.shape().outside(x - m.centreX(), z - m.centreZ());
            if (outside >= reach[i]) continue;
            double jitter = zone.jitter() == null ? 0 : zone.jitter().get(x, 0.0, z) * zone.blocksPerUnit();
            if (outside + jitter < m.rampWidth() + MARGIN) return true;
        }
        return false;
    }

    /** Whether the column, or any corner of the square {@code radius} blocks round it, is {@link #covers covered}. */
    public boolean coversAny(int x, int z, int radius) {
        return covers(x, z) || radius > 0 && (covers(x - radius, z - radius) || covers(x + radius, z - radius)
                || covers(x - radius, z + radius) || covers(x + radius, z + radius));
    }

    @Override
    public Aquifer.FluidStatus computeFluid(int x, int y, int z) {
        Aquifer.FluidStatus status = beneath.computeFluid(x, y, z);
        return covers(x, z) ? new Aquifer.FluidStatus(status.fluidLevel(), AIR) : status;
    }
}
