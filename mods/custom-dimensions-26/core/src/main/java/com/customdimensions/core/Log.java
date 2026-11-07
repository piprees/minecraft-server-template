package com.customdimensions.core;

/**
 * The core's logger: SLF4J-style {@code {}} placeholders over
 * {@link System.Logger}, so core needs no logging dependency. A trailing
 * {@link Throwable} argument is logged as the cause.
 */
public final class Log {

    private static final System.Logger LOGGER = System.getLogger("customdimensions");

    private Log() {
    }

    public static void info(String message, Object... args) {
        log(System.Logger.Level.INFO, message, args);
    }

    public static void warn(String message, Object... args) {
        log(System.Logger.Level.WARNING, message, args);
    }

    public static void error(String message, Object... args) {
        log(System.Logger.Level.ERROR, message, args);
    }

    private static void log(System.Logger.Level level, String message, Object[] args) {
        if (!LOGGER.isLoggable(level)) {
            return;
        }
        Throwable cause = null;
        int usable = args.length;
        if (usable > 0 && args[usable - 1] instanceof Throwable t) {
            cause = t;
            usable--;
        }
        String text = format(message, args, usable);
        if (cause != null) {
            LOGGER.log(level, text, cause);
        } else {
            LOGGER.log(level, text);
        }
    }

    static String format(String message, Object[] args, int usable) {
        if (message == null || usable == 0) {
            return message;
        }
        StringBuilder out = new StringBuilder(message.length() + 16 * usable);
        int from = 0;
        int next = 0;
        while (next < usable) {
            int at = message.indexOf("{}", from);
            if (at < 0) {
                break;
            }
            out.append(message, from, at).append(args[next++]);
            from = at + 2;
        }
        return out.append(message, from, message.length()).toString();
    }
}
