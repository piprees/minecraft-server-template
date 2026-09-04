package com.customdimensions.client;

import org.slf4j.Logger;

/**
 * A diagnostic that repeats: the first at INFO, where a human reading the log
 * and a grep asserting the behaviour happened both need it; every repeat at
 * DEBUG.
 *
 * <p>A line on a frame counter or a wall-clock interval is never INFO. The
 * live counters behind those lines are on the dev bridge, which is where a
 * diagnosis reads them from.
 */
public final class Repeated {

    private Repeated() {}

    public static void log(Logger logger, boolean first, String format, Object... arguments) {
        if (first) {
            logger.info(format, arguments);
        } else {
            logger.debug(format, arguments);
        }
    }
}
