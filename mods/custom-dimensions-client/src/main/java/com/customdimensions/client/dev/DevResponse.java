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
        return Json.obj().bool("ok", false).str("error", readable(message)).toString();
    }

    /**
     * A bounded wait that expired. Flagged distinctly because a harness needs to
     * tell "the render thread was busy, ask again" from "this request was wrong",
     * and because an empty body reads as a parse failure at the jq end.
     */
    public static String timeout(String path, long timeoutMs) {
        return Json.obj()
                .bool("ok", false)
                .str("error", path + " timed out after " + timeoutMs
                        + "ms waiting for the render thread")
                .bool("timeout", true)
                .bool("retryable", true)
                .str("path", path)
                .num("timeoutMs", timeoutMs)
                .toString();
    }

    /** The reason, never the Java class name when there is a message to give. */
    public static String reasonOf(Throwable thrown) {
        if (thrown == null) {
            return "unknown error";
        }
        String message = thrown.getMessage();
        return message == null || message.isBlank()
                ? thrown.getClass().getSimpleName()
                : message;
    }

    /** The last guard: a response body is never empty, whatever went wrong. */
    public static String nonEmpty(String body) {
        return body == null || body.isBlank() ? error("empty response") : body;
    }

    private static String readable(String message) {
        return message == null || message.isBlank() ? "unknown error" : message;
    }

    private static String shots(String before, String after) {
        return Json.obj().raw("before", before).raw("after", after).toString();
    }
}
