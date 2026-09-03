package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The answers a shell asserts on. Every case is checked by reading the response
 * back with {@link JsonReader}, so a field that is right but embedded in output
 * {@code jq} cannot parse still fails.
 */
class DevResponseTest {

    private static final String BEFORE = "{\"player\":{\"pos\":[0,64,0]}}";
    private static final String AFTER = "{\"player\":{\"pos\":[12,64,0]}}";
    private static final String SHOT_BEFORE = "{\"path\":\"/tmp/a.png\",\"bytes\":10}";
    private static final String SHOT_AFTER = "{\"path\":\"/tmp/b.png\",\"bytes\":20}";

    private static Map<String, Object> read(String response) {
        return JsonReader.object(response);
    }

    private static WalkTracker arrived() {
        WalkTracker tracker = new WalkTracker(0, 64, 0, 0, 12, 20, 400);
        tracker.accept(12, 64, 0, 60);
        return tracker;
    }

    private static WalkTracker stalled() {
        WalkTracker tracker = new WalkTracker(0, 64, 0, 0, 12, 20, 400);
        tracker.accept(3.5, 63, -1.25, 10);
        tracker.accept(3.5, 63, -1.25, 30);
        return tracker;
    }

    // ------------------------------------------------------------------ walk

    @Test
    void anArrivedWalkReportsItsMeasurements() {
        Map<String, Object> body = read(DevResponse.walk(
                arrived(), 12, BEFORE, AFTER, SHOT_BEFORE, SHOT_AFTER));
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertEquals("walk", body.get("action"));
        assertEquals(12.0, (Double) body.get("requested"));
        assertEquals(12.0, (Double) body.get("travelled"));
        assertEquals(60.0, (Double) body.get("ticks"));
        assertEquals(Boolean.TRUE, body.get("arrived"));
        assertEquals(Boolean.FALSE, body.get("stalled"));
        assertEquals("arrived", body.get("reason"));
    }

    @Test
    void anArrivedWalkHasNoStallPosition() {
        Map<String, Object> body = read(DevResponse.walk(
                arrived(), 12, BEFORE, AFTER, SHOT_BEFORE, SHOT_AFTER));
        assertNull(body.get("stalledAt"));
        assertTrue(body.containsKey("stalledAt"));
    }

    @Test
    void aStalledWalkSaysSoAndSaysWhere() {
        Map<String, Object> body = read(DevResponse.walk(
                stalled(), 12, BEFORE, AFTER, SHOT_BEFORE, SHOT_AFTER));
        assertEquals(Boolean.FALSE, body.get("arrived"));
        assertEquals(Boolean.TRUE, body.get("stalled"));
        assertEquals(List.of(3.5, 63.0, -1.25), body.get("stalledAt"));
        assertEquals("position unchanged for 20 ticks while forward was held",
                body.get("reason"));
    }

    @Test
    void aStalledWalkStillReportsHowFarItGot() {
        Map<String, Object> body = read(DevResponse.walk(
                stalled(), 12, BEFORE, AFTER, SHOT_BEFORE, SHOT_AFTER));
        assertEquals(3.717, (Double) body.get("travelled"));
        assertEquals(30.0, (Double) body.get("ticks"));
    }

    @Test
    void aWalkCarriesBothStatesAndBothShots() {
        Map<String, Object> body = read(DevResponse.walk(
                arrived(), 12, BEFORE, AFTER, SHOT_BEFORE, SHOT_AFTER));
        Map<?, ?> before = (Map<?, ?>) body.get("before");
        Map<?, ?> after = (Map<?, ?>) body.get("after");
        assertEquals(List.of(0.0, 64.0, 0.0), ((Map<?, ?>) before.get("player")).get("pos"));
        assertEquals(List.of(12.0, 64.0, 0.0), ((Map<?, ?>) after.get("player")).get("pos"));
        Map<?, ?> shots = (Map<?, ?>) body.get("shots");
        assertEquals("/tmp/a.png", ((Map<?, ?>) shots.get("before")).get("path"));
        assertEquals("/tmp/b.png", ((Map<?, ?>) shots.get("after")).get("path"));
    }

    // ---------------------------------------------------------------- action

    @Test
    void anActionCarriesItsNameDetailStatesAndShots() {
        Map<String, Object> body = read(DevResponse.action(
                "sneak", "{\"ticks\":20}", BEFORE, AFTER, SHOT_BEFORE, SHOT_AFTER));
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertEquals("sneak", body.get("action"));
        assertEquals(20.0, (Double) ((Map<?, ?>) body.get("detail")).get("ticks"));
        assertTrue(body.containsKey("before"));
        assertTrue(body.containsKey("after"));
        assertEquals("/tmp/b.png",
                ((Map<?, ?>) ((Map<?, ?>) body.get("shots")).get("after")).get("path"));
    }

    // ----------------------------------------------------------------- error

    @Test
    void anErrorIsNotOkAndCarriesItsMessage() {
        Map<String, Object> body = read(DevResponse.error("no player in the world"));
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertEquals("no player in the world", body.get("error"));
    }

    @Test
    void anErrorMessageWithQuotesStaysParseable() {
        Map<String, Object> body = read(DevResponse.error("unknown action: \"jump\""));
        assertEquals("unknown action: \"jump\"", body.get("error"));
    }

    /**
     * Never null and never blank: a harness reading {@code .error} gets a
     * sentence for every refusal, so "no reason given" cannot be mistaken for
     * a field that failed to parse.
     */
    @Test
    void anErrorWithNoMessageStillCarriesAReadableOne() {
        Map<String, Object> body = read(DevResponse.error(null));
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertEquals("unknown error", body.get("error"));
    }

    // --------------------------------------------------------------- timeout

    /**
     * A timeout is the condition a harness most needs told about, and the one
     * most easily mistaken for a parse failure at the {@code curl | jq} end. It
     * gets its own shape, and it is never an empty body.
     */
    @Test
    void aTimeoutIsAJsonErrorNamingTheEndpointAndTheBound() {
        Map<String, Object> body = read(DevResponse.timeout("/state", 5000));
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertEquals("/state", body.get("path"));
        assertEquals(5000.0, (Double) body.get("timeoutMs"));
        assertTrue(String.valueOf(body.get("error")).contains("/state"), String.valueOf(body.get("error")));
        assertTrue(String.valueOf(body.get("error")).contains("5000"), String.valueOf(body.get("error")));
    }

    /** A shell has to be able to tell a timeout from every other refusal. */
    @Test
    void aTimeoutIsFlaggedAsOneAndAsWorthRetrying() {
        Map<String, Object> body = read(DevResponse.timeout("/state", 5000));
        assertEquals(Boolean.TRUE, body.get("timeout"));
        assertEquals(Boolean.TRUE, body.get("retryable"));
    }

    @Test
    void anOrdinaryErrorIsNotFlaggedAsATimeout() {
        Map<String, Object> body = read(DevResponse.error("no player in the world"));
        assertNull(body.get("timeout"));
        assertNull(body.get("retryable"));
    }

    // ---------------------------------------------------------------- reason

    /** The wire format must carry the reason, not a Java class name. */
    @Test
    void aThrowableWithAMessageReportsTheMessageAlone() {
        assertEquals("no player in the world",
                DevResponse.reasonOf(new IllegalStateException("no player in the world")));
    }

    @Test
    void aThrowableWithNoMessageFallsBackToItsType() {
        assertEquals("IllegalStateException",
                DevResponse.reasonOf(new IllegalStateException()));
    }

    @Test
    void aThrowableWithABlankMessageFallsBackToItsType() {
        assertEquals("IllegalStateException",
                DevResponse.reasonOf(new IllegalStateException("   ")));
    }

    @Test
    void noThrowableAtAllStillReadsAsSomething() {
        assertEquals("unknown error", DevResponse.reasonOf(null));
    }

    // ------------------------------------------------------- never empty

    @Test
    void aRealBodyPassesThroughUntouched() {
        assertEquals("{\"ok\":true}", DevResponse.nonEmpty("{\"ok\":true}"));
    }

    @Test
    void aNullBodyBecomesAParseableError() {
        Map<String, Object> body = read(DevResponse.nonEmpty(null));
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertNotNull(body.get("error"));
    }

    @Test
    void anEmptyBodyBecomesAParseableError() {
        Map<String, Object> body = read(DevResponse.nonEmpty(""));
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertNotNull(body.get("error"));
    }

    @Test
    void aWhitespaceOnlyBodyBecomesAParseableError() {
        Map<String, Object> body = read(DevResponse.nonEmpty("   \n"));
        assertEquals(Boolean.FALSE, body.get("ok"));
        assertNotNull(body.get("error"));
    }

    @Test
    void anErrorWithABlankMessageStillCarriesSomethingReadable() {
        Map<String, Object> body = read(DevResponse.error(""));
        assertNotNull(body.get("error"));
        assertTrue(String.valueOf(body.get("error")).isBlank() == false);
    }
}
