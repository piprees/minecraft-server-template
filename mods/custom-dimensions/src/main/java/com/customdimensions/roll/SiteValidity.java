package com.customdimensions.roll;

import com.customdimensions.command.SpikeSampler;
import com.customdimensions.config.DimensionConfig;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.HashMap;
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
 * dimension's biome source, with no chunk generated: the site's assigned
 * structure comes from {@link CandidateRender#structurePositions} (the real
 * {@code StructurePick} draw), its declared biomes from
 * {@code Structure.getValidBiomes()}, and the biome at the site from the same
 * sampler the render paints with.
 *
 * <p><b>The biome is read at quart y=16 (block 64)</b> — the slice
 * {@link SpikeSampler#sample} defines and the biome grid, the biome shares and
 * the render all share, so a verdict agrees with the picture beside it.
 * Vanilla tests the biome at the structure's own start height, which is not
 * that slice ([T50](TROUBLESHOOTING.md#t50)); a verdict is this slice's answer.
 */
public final class SiteValidity {

    private SiteValidity() {
    }

    /** What the biome at a site says about the structure assigned there. */
    public enum Verdict {
        /** The site's biome is one the structure declares. */
        VALID,
        /** The structure declares biomes, and the site's is not among them. */
        MISMATCH,
        /** The structure declares no valid biomes at all, so vanilla places it nowhere. */
        NO_VALID_BIOMES,
        /** The biome source produced nothing at this column. */
        UNSAMPLED;

        /** Everything but {@link #VALID} — what the overlay draws loudly. */
        public boolean bad() {
            return this != VALID;
        }
    }

    /** One noise site, its assignment, the biome under it and the verdict. */
    public record SiteVerdict(String group, long x, long z, String structureId,
                              String biome, Verdict verdict) {
    }

    /** One group's site count and how many of them failed. */
    public record GroupTally(int total, int bad) {
    }

    /** Every site in one (dimension, seed), with the tallies a summary line needs. */
    public record Report(String dimension, long seed, int radius, int biomeQuartY,
                         List<SiteVerdict> sites, Map<String, GroupTally> byGroup,
                         Map<String, Integer> badByStructure, long millis) {

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

        public int bad() {
            return this.total() - this.count(Verdict.VALID);
        }

        /** The one-line summary a command answers with — counts, never "OK". */
        public String summary() {
            return this.total() + " sites, " + this.bad() + " assigned a structure their biome "
                    + "rejects (" + this.count(Verdict.MISMATCH) + " mismatch, "
                    + this.count(Verdict.NO_VALID_BIOMES) + " no-valid-biomes, "
                    + this.count(Verdict.UNSAMPLED) + " unsampled), "
                    + this.byGroup.size() + " group(s), " + this.millis + "ms";
        }
    }

    /** The biome slice every verdict is read at, in quart coordinates. */
    public static final int BIOME_QUART_Y = 16;

    /**
     * Every noise-managed site in this (dimension, seed), each with a verdict.
     *
     * <p>Single-threaded on purpose: a {@link SpikeSampler.Rig} carries the
     * interpolation and column caches its {@code NoiseConfig} planted, so one
     * rig is one thread. Measured on the elfydd overworld the whole pass costs
     * well under the placement build it reads from.
     */
    public static Report of(MinecraftServer server, DimensionConfig def,
                            SpikeSampler.Base base, long seed, int radius) {
        long start = System.nanoTime();
        Map<String, List<CandidateRender.Site>> byGroup =
                CandidateRender.structurePositions(server, def, base, seed, radius);

        Registry<Structure> structures = server.getRegistryManager().get(RegistryKeys.STRUCTURE);
        Map<String, Set<Identifier>> validBiomes = new HashMap<>();
        SpikeSampler.Rig rig = SpikeSampler.forSeedClimate(server, base, seed);

        List<SiteVerdict> sites = new ArrayList<>();
        Map<String, GroupTally> tallies = new LinkedHashMap<>();
        Map<String, Integer> badByStructure = new TreeMap<>();
        for (Map.Entry<String, List<CandidateRender.Site>> group : byGroup.entrySet()) {
            int bad = 0;
            for (CandidateRender.Site site : group.getValue()) {
                String biome = SpikeSampler.sample(rig, (int) site.x(), (int) site.z()).biome();
                Set<Identifier> declared = validBiomes.computeIfAbsent(
                        site.structureId(), id -> declaredBiomes(structures, id));
                Verdict verdict = verdictOf(declared, biome);
                if (verdict.bad()) {
                    bad++;
                    badByStructure.merge(site.structureId(), 1, Integer::sum);
                }
                sites.add(new SiteVerdict(group.getKey(), site.x(), site.z(),
                        site.structureId(), biome, verdict));
            }
            tallies.put(group.getKey(), new GroupTally(group.getValue().size(), bad));
        }
        return new Report(def.getDimensionIdentifier().toString(), seed, radius, BIOME_QUART_Y,
                sites, tallies, badByStructure, (System.nanoTime() - start) / 1_000_000L);
    }

    /**
     * The biome ids a structure declares, or null when the registry has no
     * such structure — told apart from an empty set, which is a structure that
     * declares none and can therefore generate nowhere.
     */
    private static Set<Identifier> declaredBiomes(Registry<Structure> registry, String structureId) {
        Identifier id = structureId == null ? null : Identifier.tryParse(structureId);
        Structure structure = id == null ? null : registry.get(id);
        if (structure == null) {
            return null;
        }
        Set<Identifier> out = new java.util.HashSet<>();
        try {
            for (RegistryEntry<Biome> biome : structure.getValidBiomes()) {
                biome.getKey().ifPresent(key -> out.add(key.getValue()));
            }
        } catch (RuntimeException e) {
            return null;   // a broken structure is not ours to fail on
        }
        return out;
    }

    private static Verdict verdictOf(Set<Identifier> declared, String biome) {
        if (biome == null) {
            return Verdict.UNSAMPLED;
        }
        if (declared == null) {
            return Verdict.VALID;   // unresolvable structure: nothing to test it against
        }
        if (declared.isEmpty()) {
            return Verdict.NO_VALID_BIOMES;
        }
        Identifier id = Identifier.tryParse(biome);
        return id != null && declared.contains(id) ? Verdict.VALID : Verdict.MISMATCH;
    }

    /** The artefact body, sites included — the whole answer, not the summary. */
    public static String json(Report report) {
        StringBuilder b = new StringBuilder(
                com.customdimensions.command.Artefacts.jsonHeader("site-validity"));
        b.append(" \"dimension\": \"").append(report.dimension()).append("\",\n");
        b.append(" \"seed\": ").append(report.seed()).append(",\n");
        b.append(" \"radiusBlocks\": ").append(report.radius()).append(",\n");
        b.append(" \"biomeQuartY\": ").append(report.biomeQuartY()).append(",\n");
        b.append(" \"millis\": ").append(report.millis()).append(",\n");
        b.append(" \"totalSites\": ").append(report.total()).append(",\n");
        b.append(" \"badSites\": ").append(report.bad()).append(",\n");
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
                    .append(e.getValue().total()).append(", \"bad\": ")
                    .append(e.getValue().bad()).append('}');
        }
        b.append(first ? "},\n" : "\n },\n");
        b.append(" \"badByStructure\": {");
        first = true;
        for (Map.Entry<String, Integer> e : report.badByStructure().entrySet()) {
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
                    .append(", \"verdict\": \"").append(s.verdict().name()).append("\"}");
        }
        b.append(first ? "]\n}\n" : "\n ]\n}\n");
        return b.toString();
    }
}
