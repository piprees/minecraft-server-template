package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActivationQueueTest {
    @Test
    void startsOneAtATimeInArrivalOrder() {
        ActivationQueue<String> queue = new ActivationQueue<>();
        queue.offer("a");
        queue.offer("b");
        queue.offer("c");
        assertEquals(Optional.of("a"), queue.start());
        assertEquals(Optional.empty(), queue.start(), "a second start while one is in flight");
        assertEquals(Optional.empty(), queue.peek());
        queue.finish("a");
        assertEquals(Optional.of("b"), queue.peek());
        assertEquals(Optional.of("b"), queue.start());
        queue.finish("b");
        assertEquals(Optional.of("c"), queue.start());
        queue.finish("c");
        assertTrue(queue.isIdle());
        assertEquals(Optional.empty(), queue.start());
    }

    @Test
    void holdsAKeyOnce() {
        ActivationQueue<String> queue = new ActivationQueue<>();
        assertTrue(queue.offer("a"));
        assertFalse(queue.offer("a"), "already waiting");
        queue.start();
        assertFalse(queue.offer("a"), "already in flight");
        assertTrue(queue.contains("a"));
        queue.finish("a");
        assertFalse(queue.contains("a"));
        assertTrue(queue.offer("a"), "offered again once finished");
    }

    @Test
    void finishingAKeyNotInFlightIsRefused() {
        ActivationQueue<String> queue = new ActivationQueue<>();
        assertThrows(IllegalStateException.class, () -> queue.finish("a"));
        queue.offer("a");
        queue.offer("b");
        queue.start();
        assertThrows(IllegalStateException.class, () -> queue.finish("b"));
        assertEquals(Optional.of("a"), queue.current());
    }

    @Test
    void removesOnlyWaitingKeys() {
        ActivationQueue<String> queue = new ActivationQueue<>();
        queue.offer("a");
        queue.offer("b");
        queue.offer("c");
        queue.start();
        assertFalse(queue.remove("a"), "in flight");
        assertTrue(queue.remove("b"));
        assertEquals(List.of("c"), queue.waiting());
        assertFalse(queue.isIdle());
    }

    @Test
    void refusesNull() {
        assertThrows(NullPointerException.class, () -> new ActivationQueue<String>().offer(null));
    }
}
