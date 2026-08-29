package com.customdimensions.roll;

import com.customdimensions.command.SpikeSampler;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.dimension.NoisePoolBuilder;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Whether the structure a noise site was assigned would be accepted there by
 * its own declared biomes.
 *
 * <p>That test is what {@code NoiseStructureSelectionMixin} replaces with
 * {@code biomeEntry -> true} at generation time, so nothing in a running world
 * ever asks it. It is asked here instead, over the placement index and the
 * dimension's biome source, with no chunk generated.
 *
 * <p><b>An out-of-biome site is not automatically a bug.</b>
 * {@code structures.wants} and {@code structures.include} admit a
 * zero-affinity structure at full weight on purpose, and the bypassed
 * predicate is what makes that request generate — so a site is VALID, WANTED
 * (out of biome and asked for) or VIOLATION (out of biome and asked for by
 * nobody). The admission test is
 * {@link NoisePoolBuilder#admittedDespiteBiomes}, the same predicate the pool
 * builder decides with.
 *
 * <p><b>Where the biome is read.</b> Vanilla samples at the position the
 * structure chose for itself — {@code Structure.isBiomeValid} reads
 * {@code BiomeCoords.fromBlock(pos.getY())} of a position from
 * {@code getStructurePosition}, which for a surface family is
 * {@code getHeightInGround} and for an underground family is a deep or fixed
 * y. This reads the render's own surface height at the site, which tracks the
 * first and not the second, and records the fixed-slice verdict beside it so
 * the gap between the two is a measured number rather than an assumption
 * ({@code fixedSliceFlips}). A site whose structure generates underground is
 * counted separately, because that is where the remaining error lives.
 */
public final class SiteValidity {

    private SiteValidity() {
    }

    /** What the biome at a site says about the structure assigned there. */
    public enum Verdict {
        /** The site's biome is one the structure declares. */
        VALID,
        /** Out of biome, and this dimension's wants/include asked for it. */
        WANTED,
        /** Out of biome, and nothing asked for it. The bug. */
        VIOLATION,
        /** The structure declares no valid biomes at all, and nothing asked for it. */
        NO_VALID_BIOMES,
        /** The biome source produced nothing at this column. */
        UNSAMPLED;

        /** Whether this verdict should fail a gate — a want and an unknown should not. */
        public boolean fails() {
            return this == VIOLATION || this == NO_VALID_BIOMES;
        }
    }

    /**
     * One noise site, its assignment, the biome under it and the verdict —
     * plus the verdict the fixed slice would have reached, so the difference
     * between the two reads is a recorded pair rather than a flag.
     */
    public record SiteVerdict(String group, long x, long z, String structureId,
                              String biome, Integer surfaceY, Verdict verdict,
                              Verdict fixedVerdict, boolean underground,
                              Verdict bandMidVerdict, Verdict subsurfaceVerdict) {

        public boolean flipped() {
            return this.verdict != this.fixedVerdict;
        }
    }

    /** One group's site count and how many of them fail. */
    public record GroupTally(int total, int failed, int wanted) {
    }

    /** Every site in one (dimension, seed), with the tallies a summary line needs. */
    public record Report(String dimension, long seed, int radius, int fixedQuartY,
                         List<SiteVerdict> sites, Map<String, GroupTally> byGroup,
                         Map<String, Integer> failedByStructure, long millis) {

        public int total() {
            return this.sites.size();
        }

        public int count(Verdict verdict) {
            int n = 0;
            for (SiteVerdict s : this.sites) {
                if (s.verdict() == verdict) {
                    n++;
                }
            }
            return n;
        }

        public int failed() {
            int n = 0;
            for (SiteVerdict s : this.sites) {
                if (s.verdict().fails()) {
                    n++;
                }
            }
            return n;
        }

        /** How many verdicts the fixed slice would have got differently. */
        public int fixedSliceFlips() {
            int n = 0;
            for (SiteVerdict s : this.sites) {
                if (s.flipped()) {
                    n++;
                }
            }
            return n;
        }

        /**
         * Sites the surface read CLEARS that the fixed slice condemned, and
         * sites it condemns that the fixed slice cleared. Reported apart
         * because a net figure hides the second, which is the direction that
         * matters — an instrument that only ever exonerates is not an
         * instrument.
         */
        public int flipsToPassing() {
            int n = 0;
            for (SiteVerdict s : this.sites) {
                if (s.fixedVerdict().fails() && !s.verdict().fails()) {
                    n++;
                }
            }
            return n;
        }

        public int flipsToFailing() {
            int n = 0;
            for (SiteVerdict s : this.sites) {
                if (!s.fixedVerdict().fails() && s.verdict().fails()) {
                    n++;
                }
            }
            return n;
        }

        /**
         * Of the failing underground sites, how many a deeper read clears.
         * Two depths, because neither is vanilla's own: the middle of the
         * dimension's band, and the middle of the rock beneath this column.
         * A low number on both means the surface read was not the thing
         * condemning them.
         */
        public int undergroundClearedAtBandMid() {
            int n = 0;
            for (SiteVerdict s : this.sites) {
                if (s.verdict().fails() && s.underground()
                        && s.bandMidVerdict() != null && !s.bandMidVerdict().fails()) {
                    n++;
                }
            }
            return n;
        }

        public int undergroundClearedAtSubsurface() {
            int n = 0;
            for (SiteVerdict s : this.sites) {
                if (s.verdict().fails() && s.underground()
                        && s.subsurfaceVerdict() != null && !s.subsurfaceVerdict().fails()) {
                    n++;
                }
            }
            return n;
        }

        /** Failing sites whose structure generates underground — where the residual error is. */
        public int failedUnderground() {
            int n = 0;
            for (SiteVerdict s : this.sites) {
                if (s.verdict().fails() && s.underground()) {
                    n++;
                }
            }
            return n;
        }

        /** The one-line summary a command answers with — counts, never "OK". */
        public String summary() {
            return this.total() + " sites, " + this.failed() + " violations ("
                    + this.count(Verdict.VIOLATION) + " out-of-biome, "
                    + this.count(Verdict.NO_VALID_BIOMES) + " no-valid-biomes; "
                    + this.failedUnderground() + " of them underground-step, of which "
                    + this.undergroundClearedAtBandMid() + " clear at the band midpoint and "
                    + this.undergroundClearedAtSubsurface() + " below this column), "
                    + this.count(Verdict.WANTED) + " wanted, "
                    + this.count(Verdict.UNSAMPLED) + " unsampled, "
                    + this.fixedSliceFlips() + " verdicts differ from the y="
                    + (this.fixedQuartY * 4) + " slice (" + this.flipsToPassing()
                    + " cleared, " + this.flipsToFailing() + " newly failing), "
                    + this.byGroup.size() + " group(s), " + this.millis + "ms";
        }
    }

    /** The slice kept alongside the surface read, so the two can be compared. */
    public static final int FIXED_QUART_Y = 16;

    /**
     * Every noise-managed site in this (dimension, seed), each with a verdict.
     *
     * <p>Single-threaded on purpose: a {@link SpikeSampler.Rig} carries the
     * interpolation and column caches its {@code NoiseConfig} planted, so one
     * rig is one thread.
     */
    public static Report of(MinecraftServer server, DimensionConfig def,
                            SpikeSampler.Base base, long seed, int radius) {
        long start = System.nanoTime();
        Map<String, List<CandidateRender.Site>> byGroup =
                CandidateRender.structurePositions(server, def, base, seed, radius);

        Registry<Structure> structureRegistry =
                server.getRegistryManager().get(RegistryKeys.STRUCTURE);
        Set<String> admitted = NoisePoolBuilder.admittedStructureIds(def,
                server.getRegistryManager().get(RegistryKeys.STRUCTURE_SET).getIndexedEntries());

        // One rig for both questions: the surface walk reads the final density
        // and the biome reads the climate chains, and climateAndShape keeps
        // both in the settings this model was built from.
        CandidateRender.HeightModel model =
                CandidateRender.heightModel(server, base, seed, Math.max(1, radius * 2));
        SpikeSampler.Rig rig = CandidateRender.rigFor(server, base, model, seed);
        TerrainShape.Density shape = CandidateRender.densityFor(model, rig);

        Map<String, Set<Identifier>> declared = new HashMap<>();
        Map<String, Boolean> underground = new HashMap<>();
        List<SiteVerdict> sites = new ArrayList<>();
        Map<String, GroupTally> tallies = new LinkedHashMap<>();
        Map<String, Integer> failedByStructure = new TreeMap<>();
        for (Map.Entry<String, List<CandidateRender.Site>> group : byGroup.entrySet()) {
            int failed = 0;
            int wanted = 0;
            for (CandidateRender.Site site : group.getValue()) {
                int x = (int) site.x();
                int z = (int) site.z();
                Set<Identifier> valid = declared.computeIfAbsent(
                        site.structureId(), id -> declaredBiomes(structureRegistry, id));
                boolean asked = admitted.contains(site.structureId());

                Integer surfaceY = CandidateRender.surfaceAt(model, shape, null, x, z);
                String atSurface = surfaceY == null ? null : biomeAt(rig, x, surfaceY >> 2, z);
                String atFixed = biomeAt(rig, x, FIXED_QUART_Y, z);
                // No floor under the column: the fixed slice is the only read
                // there is, and saying so beats inventing a height.
                String biome = atSurface != null ? atSurface : atFixed;

                Verdict verdict = verdictOf(valid, biome, asked);
                Verdict fixedVerdict = verdictOf(valid, atFixed, asked);

                // An underground family picks a deep or fixed y of its own, so
                // the surface read describes a column it never occupies. Two
                // more reads bound that: the middle of the dimension's own
                // band, and the middle of the rock between its floor and this
                // column's surface.
                boolean deep = underground.computeIfAbsent(site.structureId(),
                        id -> generatesUnderground(structureRegistry, id));
                Verdict bandMid = null;
                Verdict subsurface = null;
                if (deep) {
                    int mid = (model.band().bottomY() + model.band().topY()) / 2;
                    bandMid = verdictOf(valid, biomeAt(rig, x, mid >> 2, z), asked);
                    if (surfaceY != null) {
                        int under = (model.band().bottomY() + surfaceY) / 2;
                        subsurface = verdictOf(valid, biomeAt(rig, x, under >> 2, z), asked);
                    }
                }
                if (verdict.fails()) {
                    failed++;
                    failedByStructure.merge(site.structureId(), 1, Integer::sum);
                } else if (verdict == Verdict.WANTED) {
                    wanted++;
                }
                sites.add(new SiteVerdict(group.getKey(), site.x(), site.z(), site.structureId(),
                        biome, surfaceY, verdict, fixedVerdict, deep, bandMid, subsurface));
            }
            tallies.put(group.getKey(), new GroupTally(group.getValue().size(), failed, wanted));
        }
        return new Report(def.getDimensionIdentifier().toString(), seed, radius, FIXED_QUART_Y,
                sites, tallies, failedByStructure, (System.nanoTime() - start) / 1_000_000L);
    }

    /** The biome id the source produces at one block column and quart height. */
    private static String biomeAt(SpikeSampler.Rig rig, int x, int quartY, int z) {
        if (rig.noiseConfig() == null || rig.generator() == null) {
            return null;
        }
        try {
            RegistryEntry<Biome> entry = rig.generator().getBiomeSource().getBiome(
                    x >> 2, quartY, z >> 2, rig.noiseConfig().getMultiNoiseSampler());
            return entry == null ? null
                    : entry.getKey().map(key -> key.getValue().toString()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The biome ids a structure declares, or null when the registry has no
     * such structure — told apart from an empty set, which is a structure that
     * declares none and can therefore generate nowhere.
     */
    private static Set<Identifier> declaredBiomes(Registry<Structure> registry, String structureId) {
        Structure structure = lookup(registry, structureId);
        if (structure == null) {
            return null;
        }
        Set<Identifier> out = new HashSet<>();
        try {
            for (RegistryEntry<Biome> biome : structure.getValidBiomes()) {
                biome.getKey().ifPresent(key -> out.add(key.getValue()));
            }
        } catch (RuntimeException e) {
            return null;   // a broken structure is not ours to fail on
        }
        return out;
    }

    /**
     * Whether a structure generates below the surface, which is where a
     * surface-height biome read stops describing the position vanilla tests.
     */
    private static boolean generatesUnderground(Registry<Structure> registry, String structureId) {
        Structure structure = lookup(registry, structureId);
        if (structure == null) {
            return false;
        }
        try {
            return structure.getFeatureGenerationStep()
                    == net.minecraft.world.gen.GenerationStep.Feature.UNDERGROUND_STRUCTURES;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Structure lookup(Registry<Structure> registry, String structureId) {
        Identifier id = structureId == null ? null : Identifier.tryParse(structureId);
        return id == null ? null : registry.get(id);
    }

    private static Verdict verdictOf(Set<Identifier> declared, String biome, boolean admitted) {
        if (biome == null) {
            return Verdict.UNSAMPLED;
        }
        if (declared == null) {
            return Verdict.VALID;   // unresolvable structure: nothing to test it against
        }
        Identifier id = Identifier.tryParse(biome);
        if (id != null && declared.contains(id)) {
            return Verdict.VALID;
        }
        if (admitted) {
            return Verdict.WANTED;
        }
        return declared.isEmpty() ? Verdict.NO_VALID_BIOMES : Verdict.VIOLATION;
    }

    /** The artefact body, sites included — the whole answer, not the summary. */
    public static String json(Report report) {
        StringBuilder b = new StringBuilder(
                com.customdimensions.command.Artefacts.jsonHeader("site-validity"));
        b.append(" \"dimension\": \"").append(report.dimension()).append("\",\n");
        b.append(" \"seed\": ").append(report.seed()).append(",\n");
        b.append(" \"radiusBlocks\": ").append(report.radius()).append(",\n");
        b.append(" \"biomeReadAt\": \"structure column surface height\",\n");
        b.append(" \"fixedQuartY\": ").append(report.fixedQuartY()).append(",\n");
        b.append(" \"fixedSliceFlips\": ").append(report.fixedSliceFlips()).append(",\n");
        b.append(" \"fixedSliceFlipsToPassing\": ").append(report.flipsToPassing()).append(",\n");
        b.append(" \"fixedSliceFlipsToFailing\": ").append(report.flipsToFailing()).append(",\n");
        b.append(" \"millis\": ").append(report.millis()).append(",\n");
        b.append(" \"totalSites\": ").append(report.total()).append(",\n");
        b.append(" \"failedSites\": ").append(report.failed()).append(",\n");
        b.append(" \"failedUnderground\": ").append(report.failedUnderground()).append(",\n");
        b.append(" \"undergroundClearedAtBandMid\": ")
                .append(report.undergroundClearedAtBandMid()).append(",\n");
        b.append(" \"undergroundClearedAtSubsurface\": ")
                .append(report.undergroundClearedAtSubsurface()).append(",\n");
        for (Verdict v : Verdict.values()) {
            b.append(" \"").append(v.name().toLowerCase(java.util.Locale.ROOT))
                    .append("Sites\": ").append(report.count(v)).append(",\n");
        }
        b.append(" \"byGroup\": {");
        boolean first = true;
        for (Map.Entry<String, GroupTally> e : report.byGroup().entrySet()) {
            b.append(first ? "\n  " : ",\n  ");
            first = false;
            b.append('"').append(e.getKey()).append("\": {\"total\": ")
                    .append(e.getValue().total()).append(", \"failed\": ")
                    .append(e.getValue().failed()).append(", \"wanted\": ")
                    .append(e.getValue().wanted()).append('}');
        }
        b.append(first ? "},\n" : "\n },\n");
        b.append(" \"failedByStructure\": {");
        first = true;
        for (Map.Entry<String, Integer> e : report.failedByStructure().entrySet()) {
            b.append(first ? "\n  " : ",\n  ");
            first = false;
            b.append('"').append(e.getKey()).append("\": ").append(e.getValue());
        }
        b.append(first ? "},\n" : "\n },\n");
        b.append(" \"sites\": [");
        first = true;
        for (SiteVerdict s : report.sites()) {
            b.append(first ? "\n  " : ",\n  ");
            first = false;
            b.append("{\"group\": \"").append(s.group())
                    .append("\", \"x\": ").append(s.x())
                    .append(", \"z\": ").append(s.z())
                    .append(", \"structure\": \"").append(s.structureId())
                    .append("\", \"biome\": ")
                    .append(s.biome() == null ? "null" : "\"" + s.biome() + "\"")
                    .append(", \"surfaceY\": ").append(s.surfaceY() == null ? "null" : s.surfaceY())
                    .append(", \"underground\": ").append(s.underground())
                    .append(", \"fixedVerdict\": \"").append(s.fixedVerdict().name()).append('"')
                    .append(", \"bandMidVerdict\": ").append(s.bandMidVerdict() == null
                            ? "null" : "\"" + s.bandMidVerdict().name() + "\"")
                    .append(", \"subsurfaceVerdict\": ").append(s.subsurfaceVerdict() == null
                            ? "null" : "\"" + s.subsurfaceVerdict().name() + "\"")
                    .append(", \"verdict\": \"").append(s.verdict().name()).append("\"}");
        }
        b.append(first ? "]\n}\n" : "\n ]\n}\n");
        return b.toString();
    }
}
