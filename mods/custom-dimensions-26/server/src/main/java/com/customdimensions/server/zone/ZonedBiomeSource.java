package com.customdimensions.server.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code customdimensions:zoned}: a base biome source with zones laid over it.
 * The router's temperature carries which zone's biomes hold at a point: the
 * base temperature plus {@code 16 * c} inside channel {@code c} (the compiler's
 * {@code ZonesCompiler}). Channel 0 is the base; channel {@code c} takes
 * {@code zones[c - 1]}'s layout at the base temperature, or the base's when the
 * zone only swaps, then that zone's swaps. The global swaps apply last.
 */
public final class ZonedBiomeSource extends BiomeSource {
    /** One channel's quantised temperature step: {@code Climate.quantizeCoord(16)}. */
    static final long STEP = Climate.quantizeCoord(16f);

    public record Zone(Optional<Climate.ParameterList<Holder<Biome>>> biomes, Map<ResourceKey<Biome>, Holder<Biome>> swap) {}

    static final Codec<Map<ResourceKey<Biome>, Holder<Biome>>> SWAP = Codec.unboundedMap(ResourceKey.codec(Registries.BIOME), Biome.CODEC);
    static final Codec<Zone> ZONE = RecordCodecBuilder.create(i -> i.group(
            Climate.ParameterList.codec(Biome.CODEC.fieldOf("biome")).optionalFieldOf("biomes").forGetter(Zone::biomes),
            SWAP.optionalFieldOf("swap", Map.of()).forGetter(Zone::swap)).apply(i, Zone::new));
    public static final MapCodec<ZonedBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("base").forGetter(s -> s.base),
            ZONE.listOf().optionalFieldOf("zones", List.of()).forGetter(s -> s.zones),
            SWAP.optionalFieldOf("swap", Map.of()).forGetter(s -> s.swap)).apply(i, ZonedBiomeSource::new));

    private final BiomeSource base;
    private final List<Zone> zones;
    private final Map<ResourceKey<Biome>, Holder<Biome>> swap;

    public ZonedBiomeSource(BiomeSource base, List<Zone> zones, Map<ResourceKey<Biome>, Holder<Biome>> swap) {
        this.base = base;
        this.zones = List.copyOf(zones);
        this.swap = Map.copyOf(swap);
    }

    public BiomeSource base() {
        return base;
    }

    public List<Zone> zones() {
        return zones;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        Stream<Holder<Biome>> layouts = zones.stream().flatMap(z -> z.biomes().stream())
                .flatMap(list -> list.values().stream().map(p -> p.getSecond()));
        Stream<Holder<Biome>> swapped = Stream.concat(swap.values().stream(), zones.stream().flatMap(z -> z.swap().values().stream()));
        return Stream.concat(Stream.concat(base.possibleBiomes().stream(), layouts), swapped).distinct();
    }

    /** The channel a quantised temperature carries: 0 for the base. */
    static int channel(long temperature) {
        return (int) Math.floorDiv(temperature + STEP / 2, STEP);
    }

    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        BiomeResolver baseResolver = base.createResolver(sampler);
        MultiNoiseBiomeSource multi = base instanceof MultiNoiseBiomeSource m ? m : null;
        return (x, y, z) -> {
            Climate.TargetPoint point = sampler.sample(x, y, z);
            int c = channel(point.temperature());
            Holder<Biome> biome;
            if (c <= 0 || c > zones.size()) {
                Climate.TargetPoint at = c == 0 ? point : shifted(point, c);
                biome = multi != null ? multi.getNoiseBiome(at) : baseResolver.getNoiseBiome(x, y, z);
            } else {
                Zone zone = zones.get(c - 1);
                Climate.TargetPoint at = shifted(point, c);
                if (zone.biomes().isPresent()) {
                    biome = zone.biomes().get().findValue(at);
                } else {
                    biome = multi != null ? multi.getNoiseBiome(at) : baseResolver.getNoiseBiome(x, y, z);
                }
                biome = swapped(zone.swap(), biome);
            }
            return swapped(swap, biome);
        };
    }

    private static Climate.TargetPoint shifted(Climate.TargetPoint p, int channel) {
        return new Climate.TargetPoint(p.temperature() - channel * STEP, p.humidity(), p.continentalness(), p.erosion(),
                p.depth(), p.weirdness());
    }

    private static Holder<Biome> swapped(Map<ResourceKey<Biome>, Holder<Biome>> swaps, Holder<Biome> biome) {
        if (swaps.isEmpty()) return biome;
        return biome.unwrapKey().map(swaps::get).orElse(biome);
    }
}
