package com.customdimensions.command;

import com.customdimensions.roll.CandidateRender;
import com.customdimensions.roll.TerrainShape;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

import java.io.IOException;

/**
 * One column, both ladders, block by block.
 *
 * <p>{@code render-check} answers WHICH columns two height sources disagree
 * about. It cannot answer WHY, because it only ever sees each source's final
 * number. This prints the inputs instead: for every y in the generator's band,
 * whether the block state is opaque and whether the density says solid, beside
 * each other, with each walk's own answer at the bottom.
 *
 * <p>Written for a disagreement that survived two hypotheses. The facts and the
 * live world agreed with each other on 209 of 212 disagreeing Nether columns
 * and the render agreed with neither, always reading low and never high — while
 * the parity test feeding both walks the SAME synthetic column passed. Two
 * implementations that agree on identical input and disagree on a real world
 * are not reading the same input, and this is the instrument that says so
 * rather than another guess.
 *
 * <p>Headless: no world, no chunk generation, no wedge risk ([K1]).
 */
public final class ColumnLadder {

    private ColumnLadder() {
    }

    /** One y's worth of every reading. */
    private record Rung(int y, boolean opaque, boolean solid, double density, double raw,
                        double factsDensity, double initial, double depth) {
    }

    /**
     * Probes one column and writes the ladder, answering a one-line summary.
     *
     * @return the summary line, including the artefact path
     */
    public static String probe(MinecraftServer server, Identifier dimensionId,
                               long seed, int x, int z) throws IOException {
        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);
        if (!base.ok()) {
            return "column-ladder " + dimensionId + ": generator could not be built: "
                    + base.error();
        }
        int floorY = base.heightLimit().getBottomY();
        int topY = base.heightLimit().getTopY() - 1;

        SpikeSampler.Rig factsRig = SpikeSampler.forSeed(server, base, seed);
        CandidateRender.HeightModel model =
                CandidateRender.heightModel(server, dimensionId, base, seed, 1024);
        SpikeSampler.Rig renderRig = CandidateRender.rigFor(server, base, model, seed);
        TerrainShape.Density renderDensity = CandidateRender.densityFor(model, renderRig);

        var column = factsRig.generator().getColumnSample(
                x, z, factsRig.heightLimit(), factsRig.noiseConfig());

        // The rig's finalDensity WITHOUT the render's interpolation rewrite, so
        // a disagreement between the two ladders can be pinned on the rewrite
        // or cleared of it. Sampling the raw function is what the render did
        // before it learned to interpolate where generation interpolates.
        net.minecraft.world.gen.densityfunction.DensityFunction rawRoot =
                renderRig.noiseConfig() == null ? null
                        : renderRig.noiseConfig().getNoiseRouter().finalDensity();

        // The same function off the FACTS rig, which carries the generator's
        // COMPLETE settings. The render's rig is built from a trimmed router,
        // and a difference between these two is that trimming and nothing else.
        net.minecraft.world.gen.densityfunction.DensityFunction factsRoot =
                factsRig.noiseConfig() == null ? null
                        : factsRig.noiseConfig().getNoiseRouter().finalDensity();

        // The other two the router carries that could stand in for a surface.
        // initialDensityWithoutJaggedness is what vanilla's own surface
        // estimate reads, and the render's trimmed router zeroes it; depth is
        // the terrain-height term the retired depth path used. Both cost one
        // sample, the same as finalDensity, so either would be a drop-in.
        net.minecraft.world.gen.densityfunction.DensityFunction initialRoot =
                factsRig.noiseConfig() == null ? null
                        : factsRig.noiseConfig().getNoiseRouter().initialDensityWithoutJaggedness();
        net.minecraft.world.gen.densityfunction.DensityFunction depthRoot =
                factsRig.noiseConfig() == null ? null
                        : factsRig.noiseConfig().getNoiseRouter().depth();

        java.util.List<Rung> rungs = new java.util.ArrayList<>();
        for (int y = topY; y >= floorY; y--) {
            boolean opaque = column.getState(y).isOpaque();
            double d = renderDensity.at(x, y, z);
            double raw = rawRoot == null ? Double.NaN
                    : rawRoot.sample(new net.minecraft.world.gen.densityfunction
                            .DensityFunction.UnblendedNoisePos(x, y, z));
            double fd = factsRoot == null ? Double.NaN
                    : factsRoot.sample(new net.minecraft.world.gen.densityfunction
                            .DensityFunction.UnblendedNoisePos(x, y, z));
            var pos = new net.minecraft.world.gen.densityfunction
                    .DensityFunction.UnblendedNoisePos(x, y, z);
            double init = initialRoot == null ? Double.NaN : initialRoot.sample(pos);
            double dep = depthRoot == null ? Double.NaN : depthRoot.sample(pos);
            rungs.add(new Rung(y, opaque, d > 0.0, d, raw, fd, init, dep));
        }

        ColumnScan.Result blockWalk = ColumnScan.scan(topY, floorY,
                y -> column.getState(y).isOpaque());
        Integer densityWalk = TerrainShape.surfaceY(renderDensity, model.band(), x, z,
                base.hasCeiling());

        // Where the two ladders first disagree, walking down. That y is the
        // whole answer when there is one: everything below it is consequence.
        Integer firstSplit = null;
        for (Rung r : rungs) {
            if (r.opaque() != r.solid()) {
                firstSplit = r.y();
                break;
            }
        }
        long splits = rungs.stream().filter(r -> r.opaque() != r.solid()).count();

        Integer seaLevel = base.generator() instanceof NoiseChunkGenerator noiseGen
                ? noiseGen.getSettings().value().seaLevel() : null;

        StringBuilder json = new StringBuilder(1 << 16);
        json.append("{\n  \"kind\": \"column-ladder\",\n  \"stackVersion\": \"")
                .append(Artefacts.stackVersion()).append("\",\n");
        json.append("  \"dimension\": \"").append(dimensionId).append("\",\n");
        json.append("  \"seed\": ").append(seed).append(",\n");
        json.append("  \"x\": ").append(x).append(", \"z\": ").append(z).append(",\n");
        json.append("  \"hasCeiling\": ").append(base.hasCeiling()).append(",\n");
        json.append("  \"seaLevel\": ").append(seaLevel).append(",\n");
        json.append("  \"floorY\": ").append(floorY)
                .append(", \"topY\": ").append(topY).append(",\n");
        json.append("  \"band\": {\"bottomY\": ").append(model.band().bottomY())
                .append(", \"topY\": ").append(model.band().topY())
                .append(", \"rung\": ").append(model.band().rung()).append("},\n");
        json.append("  \"ceilingClip\": ").append(ColumnScan.CEILING_CLIP).append(",\n");
        if (base.generator() instanceof NoiseChunkGenerator ng) {
            var full = ng.getSettings().value().generationShapeConfig();
            json.append("  \"shapeFull\": {\"minimumY\": ").append(full.minimumY())
                    .append(", \"height\": ").append(full.height())
                    .append(", \"horizontalCell\": ").append(full.horizontalCellBlockCount())
                    .append(", \"verticalCell\": ").append(full.verticalCellBlockCount())
                    .append("},\n");
        }
        if (model.shapeSettings() != null) {
            var trimmed = model.shapeSettings().generationShapeConfig();
            json.append("  \"shapeRenderModel\": {\"minimumY\": ").append(trimmed.minimumY())
                    .append(", \"height\": ").append(trimmed.height())
                    .append(", \"horizontalCell\": ").append(trimmed.horizontalCellBlockCount())
                    .append(", \"verticalCell\": ").append(trimmed.verticalCellBlockCount())
                    .append("},\n");
        }
        json.append("  \"modelCellHorizontal\": ").append(model.cellHorizontal())
                .append(", \"modelShapeMinimumY\": ").append(model.shapeMinimumY())
                .append(", \"bandCellHeight\": ").append(model.band().cellHeight()).append(",\n");
        json.append("  \"blockWalk\": ").append(blockWalk.isPresent()
                ? String.valueOf(blockWalk.floorY())
                : "null").append(",\n");
        json.append("  \"blockWalkAbsent\": ").append(blockWalk.isPresent()
                ? "null" : "\"" + blockWalk.absentReason().replace("\"", "'") + "\"").append(",\n");
        json.append("  \"densityWalk\": ").append(densityWalk).append(",\n");
        json.append("  \"firstDisagreeingY\": ").append(firstSplit).append(",\n");
        json.append("  \"disagreeingRungs\": ").append(splits)
                .append(" ,\n  \"rungsTotal\": ").append(rungs.size()).append(",\n");
        json.append("  \"rungNote\": \"opaque is the generated block state; solid is "
                + "density > 0. A y where they differ is a y where the renderer is "
                + "reading something the world was not built from.\",\n");
        json.append("  \"rungColumns\": [\"y\", \"opaque\", \"solid\", \"density\", \"raw\", \"factsDensity\", \"initial\", \"depth\"],\n");
        json.append("  \"rungs\": [");
        for (int i = 0; i < rungs.size(); i++) {
            Rung r = rungs.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("\n    [").append(r.y()).append(", ").append(r.opaque())
                    .append(", ").append(r.solid()).append(", ")
                    .append(String.format(java.util.Locale.ROOT, "%.5f", r.density()))
                    .append(", ")
                    .append(String.format(java.util.Locale.ROOT, "%.5f", r.raw()))
                    .append(", ")
                    .append(String.format(java.util.Locale.ROOT, "%.5f", r.factsDensity()))
                    .append(", ")
                    .append(String.format(java.util.Locale.ROOT, "%.5f", r.initial()))
                    .append(", ")
                    .append(String.format(java.util.Locale.ROOT, "%.5f", r.depth()))
                    .append(']');
        }
        json.append("\n  ]\n}\n");

        java.nio.file.Path out = Artefacts.rollingDir().resolve("column-ladder")
                .resolve(dimensionId.getPath() + "-" + seed + "-" + x + "_" + z + ".json");
        Artefacts.write(out, json.toString());

        return "column-ladder " + dimensionId + " " + seed + " at (" + x + ", " + z + "): "
                + "blocks say " + (blockWalk.isPresent() ? blockWalk.floorY() : "no ground")
                + ", density says " + densityWalk
                + " — the two ladders differ on " + splits + " of " + rungs.size()
                + " blocks, first at y=" + firstSplit
                + " -> " + out;
    }
}
