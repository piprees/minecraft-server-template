package com.customdimensions.server.level;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * First in, first out, with at most one item in flight: a dimension's first
 * chunk generation never overlaps another's (TROUBLESHOOTING.md K6). A key is
 * held at most once, waiting or in flight.
 */
public final class ActivationQueue<K> {
    private final ArrayDeque<K> waiting = new ArrayDeque<>();
    private K current;

    /** Adds {@code key} at the back; false when it is already waiting or in flight. */
    public boolean offer(K key) {
        Objects.requireNonNull(key, "key");
        if (key.equals(current) || waiting.contains(key)) return false;
        waiting.add(key);
        return true;
    }

    /** The key that would start next, while nothing is in flight. */
    public Optional<K> peek() {
        return current != null ? Optional.empty() : Optional.ofNullable(waiting.peek());
    }

    /** Starts the next key, or nothing while one is in flight or none waits. */
    public Optional<K> start() {
        if (current != null || waiting.isEmpty()) return Optional.empty();
        current = waiting.poll();
        return Optional.of(current);
    }

    /** Ends the key in flight, so the next can start. */
    public void finish(K key) {
        if (current == null || !current.equals(key)) {
            throw new IllegalStateException("not in flight: " + key + " (in flight: " + current + ")");
        }
        current = null;
    }

    /** Removes a waiting key; false when it is not waiting. A key in flight is not removed. */
    public boolean remove(K key) {
        return waiting.remove(key);
    }

    public Optional<K> current() {
        return Optional.ofNullable(current);
    }

    public boolean contains(K key) {
        return key.equals(current) || waiting.contains(key);
    }

    public List<K> waiting() {
        return List.copyOf(waiting);
    }

    public boolean isIdle() {
        return current == null && waiting.isEmpty();
    }
}
