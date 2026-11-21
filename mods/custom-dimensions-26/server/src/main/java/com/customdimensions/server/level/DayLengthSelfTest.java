package com.customdimensions.server.level;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.MoonPhase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The day length part of the start-up self-test, read from the overworld: its
 * default clock is moved to each warped hour and the sun angle read back, the
 * moon phase read a day apart, and the clock's reach counted (every dimension
 * type on the same clock shares its time, because a clock's time is the
 * server's, not a level's). The clock is put back where it was.
 */
public final class DayLengthSelfTest {
    /** Degrees the sun may sit from a named hour's angle. */
    static final double TOLERANCE = 1.0;

    private DayLengthSelfTest() {}

    public static List<String> run(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        try {
            lines.add("PASS day length: " + measure(server));
        } catch (Throwable t) {
            lines.add("FAIL day length: " + t);
        }
        return lines;
    }

    private static String measure(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Holder<WorldClock> clock = level.dimensionType().defaultClock().orElseThrow(() -> new AssertionError("the overworld has no default clock"));
        String id = clock.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
        if (!id.startsWith("adventure:day_length/")) return "the overworld keeps the vanilla clock " + id + "; no day length applies";
        String[] parts = id.substring(id.lastIndexOf('/') + 1).split("_");
        long day = Long.parseLong(parts[0]), night = Long.parseLong(parts[1]), period = day + night;
        ServerClockManager clocks = server.clockManager();
        long before = clocks.getInstance(clock).totalTicks();
        try {
            double sunrise = sun(level, clocks, clock, 0);
            double noon = sun(level, clocks, clock, day / 2);
            double sunset = sun(level, clocks, clock, day);
            double midnight = sun(level, clocks, clock, day + night / 2);
            double next = sun(level, clocks, clock, period);
            need("noon at " + day / 2, Math.min(noon, 360 - noon) <= TOLERANCE, noon);
            need("midnight at " + (day + night / 2), Math.abs(midnight - 180) <= TOLERANCE, midnight);
            need("sunrise and sunset mirrored about noon", Math.abs(sunrise + sunset - 360) <= TOLERANCE, sunrise + "/" + sunset);
            need("the next day's sunrise at " + period, Math.abs(next - sunrise) <= TOLERANCE, next);
            MoonPhase first = moon(level, clocks, clock, 0), same = moon(level, clocks, clock, period - 1),
                    second = moon(level, clocks, clock, period);
            need("one moon phase a day", first == same && first != second, first + "/" + same + "/" + second);
            long sharing = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).listElements()
                    .filter(t -> t.value().defaultClock().filter(c -> c.is(clock)).isPresent()).count();
            return String.format(Locale.ROOT, "clock %s, period %d; sun %.1f at 0, %.1f at %d (noon), %.1f at %d (sunset), %.1f at %d (midnight), %.1f at %d; moon %s then %s;"
                            + " %d dimension types share the clock", id, period, sunrise, noon, day / 2, sunset, day, midnight, day + night / 2, next, period,
                    first, second, sharing);
        } finally {
            clocks.setTotalTicks(clock, before);
            level.environmentAttributes().invalidateTickCache();
        }
    }

    private static double sun(ServerLevel level, ServerClockManager clocks, Holder<WorldClock> clock, long ticks) {
        clocks.setTotalTicks(clock, ticks);
        level.environmentAttributes().invalidateTickCache();
        return ((level.environmentAttributes().getDimensionValue(EnvironmentAttributes.SUN_ANGLE) % 360) + 360) % 360;
    }

    private static MoonPhase moon(ServerLevel level, ServerClockManager clocks, Holder<WorldClock> clock, long ticks) {
        clocks.setTotalTicks(clock, ticks);
        level.environmentAttributes().invalidateTickCache();
        return level.environmentAttributes().getDimensionValue(EnvironmentAttributes.MOON_PHASE);
    }

    private static void need(String what, boolean ok, Object got) {
        if (!ok) throw new AssertionError(what + ": got " + got);
    }
}
