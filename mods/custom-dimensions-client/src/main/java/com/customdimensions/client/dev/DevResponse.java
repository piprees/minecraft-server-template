package com.customdimensions.client.dev;

/**
 * The shapes {@link DevServer} answers with. Every action carries the state
 * before and after it and a screenshot of each, so a caller never has to infer
 * what happened from whether the request returned.
 */
public final class DevResponse {

    private DevResponse() {}

    public static String walk(WalkTracker tracker, double requested, String before,
                              String after, String beforeShot, String afterShot) {
        return Json.obj()
                .bool("ok", true)
                .str("action", "walk")
                .num("requested", requested)
                .num("travelled", tracker.travelled())
                .num("ticks", tracker.ticks())
                .bool("arrived", tracker.arrived())
                .bool("stalled", tracker.stalled())
                .str("reason", tracker.reason())
                .raw("stalledAt", tracker.stalledAt() == null ? null
                        : Json.numbers(tracker.stalledX(), tracker.stalledY(), tracker.stalledZ()))
                .raw("before", before)
                .raw("after", after)
                .raw("shots", shots(beforeShot, afterShot))
                .toString();
    }

    public static String action(String action, String detail, String before,
                                String after, String beforeShot, String afterShot) {
        return Json.obj()
                .bool("ok", true)
                .str("action", action)
                .raw("detail", detail)
                .raw("before", before)
                .raw("after", after)
                .raw("shots", shots(beforeShot, afterShot))
                .toString();
    }

    public static String error(String message) {
        return Json.obj().bool("ok", false).str("error", message).toString();
    }

    private static String shots(String before, String after) {
        return Json.obj().raw("before", before).raw("after", after).toString();
    }
}
