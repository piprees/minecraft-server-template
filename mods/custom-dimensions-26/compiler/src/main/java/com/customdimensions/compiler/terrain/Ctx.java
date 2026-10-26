package com.customdimensions.compiler.terrain;

import com.google.gson.JsonElement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.customdimensions.compiler.terrain.Df.add;
import static com.customdimensions.compiler.terrain.Df.c;
import static com.customdimensions.compiler.terrain.Df.mul;
import static com.customdimensions.compiler.terrain.Df.ramp;
import static com.customdimensions.compiler.terrain.Df.ref;
import static com.customdimensions.compiler.terrain.Df.spline;

/** What a terrain builds from: its parameters, the dimension's heights and the router's climate inputs. */
final class Ctx {

    private final TerrainSpec spec;
    private final String prefix;
    private final String namespace;
    private final double sea;
    private final int minY;
    private final int maxY;
    private final Set<String> noises = new TreeSet<>();
    private final Map<String, JsonElement> named = new LinkedHashMap<>();

    Ctx(TerrainSpec spec, String namespace, String prefix, double sea, int minY, int maxY) {
        this.spec = spec;
        this.namespace = namespace;
        this.prefix = prefix;
        this.sea = sea;
        this.minY = minY;
        this.maxY = maxY;
    }

    double p(String name) {
        return spec.param(name);
    }

    double sea() {
        return sea;
    }

    int minY() {
        return minY;
    }

    int maxY() {
        return maxY;
    }

    double heightMultiplier() {
        return spec.heightMultiplier();
    }

    Set<String> noises() {
        return noises;
    }

    /**
     * {@code function} written once as the terrain's {@code <prefix>/<key>} and
     * referenced by id, so a term a shape reads several times is built once
     * per column.
     */
    JsonElement named(String key, JsonElement function) {
        named.put(key, Df.cache(function));
        return ref(prefix + "/" + key);
    }

    /** The functions {@link #named} recorded, by key. */
    Map<String, JsonElement> namedFunctions() {
        return Collections.unmodifiableMap(named);
    }

    JsonElement continents() {
        return ref(prefix + "/continents");
    }

    JsonElement erosion() {
        return ref(prefix + "/erosion");
    }

    /** Vanilla's peaks-and-valleys fold of the ridges noise: 1 on a ridge line, -1 in a valley. */
    JsonElement peaksValleys() {
        return ref(prefix + "/ridges_folded");
    }

    /** One of the library's noises, {@code frequency} times its base frequency, stretched by the terrain's scale. */
    JsonElement noise(String name, double frequency, double yScale) {
        noises.add(name);
        return Df.noise(namespace + ":terrain/" + name, frequency / spec.scale(), yScale, true);
    }

    /** 0 over the ocean, 1 from just past the shoreline inland. */
    JsonElement landMask() {
        return ramp(continents(), p("coast"), p("coast") + 0.125);
    }

    /** 1 where erosion is lowest, 0 where it is highest, shaded by the peaks-and-valleys fold. */
    JsonElement hills() {
        JsonElement byErosion = spline(erosion(), -1, 1, -0.4, 0.7, 0, 0.35, 0.5, 0.1, 1, 0);
        return mul(byErosion, add(mul(peaksValleys(), 0.4), 0.6));
    }

    /** {@code factor} from roughness: 22 at 0 (the ground follows the height), 2 at 1 (3D noise dominates). */
    JsonElement factor() {
        double r = p("roughness");
        return c(2 + 20 * (1 - r) * (1 - r));
    }

    /** The shared land: ocean floor to shore to inland height along continentalness, plus erosion hills. */
    TerrainFamily.Shape land() {
        double coast = p("coast");
        double floor = p("seaFloor");
        double inland = p("landHeight");
        JsonElement base = spline(continents(), -1.5, floor - 20, coast - 0.25, floor, coast - 0.04, sea - 5,
                coast, sea, coast + 0.06, sea + 4, coast + 0.3, (sea + 4 + inland) / 2, 1.5, inland);
        JsonElement h = add(base, mul(mul(landMask(), hills()), p("relief")));
        return new TerrainFamily.Shape(h, factor(), c(0), null, null, add(continents(), -0.11 - coast), 1);
    }
}
