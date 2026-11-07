package com.customdimensions.server.zone;

import com.customdimensions.core.zone.ZoneShape;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.List;
import java.util.Optional;

/**
 * {@code customdimensions:zone_distance}: a sector's or polygon's distance in
 * blocks beyond its edge ({@link ZoneShape#outside}), horizontal only, for the
 * zone shapes vanilla's functions cannot draw (decisions.md RB-27). Density
 * function types stay on the server, so clients never see it.
 */
public final class ZoneDistanceFunction implements DensityFunction {
    public static final MapCodec<ZoneDistanceFunction> CODEC = RecordCodecBuilder.<Spec>mapCodec(i -> i.group(
            Codec.DOUBLE.listOf(2, 2).fieldOf("centre").forGetter(Spec::centre),
            Codec.STRING.fieldOf("shape").forGetter(Spec::shape),
            Codec.DOUBLE.optionalFieldOf("inner", 0.0).forGetter(Spec::inner),
            Codec.DOUBLE.optionalFieldOf("outer").forGetter(Spec::outer),
            Codec.DOUBLE.listOf(2, 2).optionalFieldOf("angles").forGetter(Spec::angles),
            Codec.DOUBLE.listOf(2, 2).listOf().optionalFieldOf("points").forGetter(Spec::points)).apply(i, Spec::new))
            .flatXmap(ZoneDistanceFunction::build, f -> DataResult.success(f.spec));

    record Spec(List<Double> centre, String shape, double inner, Optional<Double> outer, Optional<List<Double>> angles,
                Optional<List<List<Double>>> points) {}

    private final Spec spec;
    private final double centreX;
    private final double centreZ;
    private final ZoneShape shape;

    private ZoneDistanceFunction(Spec spec, ZoneShape shape) {
        this.spec = spec;
        this.centreX = spec.centre().get(0);
        this.centreZ = spec.centre().get(1);
        this.shape = shape;
    }

    static DataResult<ZoneDistanceFunction> build(Spec spec) {
        try {
            ZoneShape shape = switch (spec.shape()) {
                case "sector" -> new ZoneShape.Sector(spec.inner(), spec.outer().orElseThrow(() -> new IllegalArgumentException("sector needs outer")),
                        spec.angles().orElseThrow(() -> new IllegalArgumentException("sector needs angles")).get(0), spec.angles().get().get(1));
                case "polygon" -> new ZoneShape.Polygon(spec.points().orElseThrow(() -> new IllegalArgumentException("polygon needs points"))
                        .stream().map(p -> new double[] {p.get(0), p.get(1)}).toList());
                default -> throw new IllegalArgumentException("zone_distance draws a sector or a polygon, not " + spec.shape());
            };
            return DataResult.success(new ZoneDistanceFunction(spec, shape));
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    }

    public double outside(int x, int z) {
        return shape.outside(x - centreX, z - centreZ);
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new DensitySampler() {
            @Override
            public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume volume) {
                DensitySampler.sampleVolumeNaive(ctx, buffer, volume, this);
            }

            @Override
            public float sampleValue(SamplerContext ctx, int x, int y, int z) {
                return (float) outside(x, z);
            }
        };
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return this;
    }

    @Override
    public Interval range() {
        return Interval.INFINITE;
    }

    @Override
    public int domainAxes() {
        return AXIS_X | AXIS_Z;
    }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
