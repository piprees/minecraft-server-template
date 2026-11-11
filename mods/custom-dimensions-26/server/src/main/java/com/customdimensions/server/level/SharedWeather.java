package com.customdimensions.server.level;

/**
 * The server's one weather store is advanced once per server tick. Every level
 * with a sky and no ceiling (the End aside) runs vanilla's weather cycle on it,
 * so without this the shared timers would fall once per such level each tick;
 * {@code ServerLevelWeatherMixin} lets the first of them advance it and the rest
 * only read it. A level with weather of its own advances its own store.
 */
public final class SharedWeather {
    private static long advancedAt = Long.MIN_VALUE;

    private SharedWeather() {
    }

    /** Whether a level may advance the shared store on server tick {@code tick}: the first ask in a tick may. */
    public static synchronized boolean claim(long tick) {
        if (tick == advancedAt) return false;
        advancedAt = tick;
        return true;
    }

    /** Forgets the last tick, for a new server. */
    static synchronized void reset() {
        advancedAt = Long.MIN_VALUE;
    }
}
