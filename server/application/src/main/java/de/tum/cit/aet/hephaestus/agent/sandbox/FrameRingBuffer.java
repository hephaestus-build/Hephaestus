package de.tum.cit.aet.hephaestus.agent.sandbox;

import io.micrometer.core.instrument.Counter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Bounded ring buffer of frames with drop-oldest on overflow. Frames carry a monotonic sequence
 * number; {@link #snapshotSince} lets a subscriber resume without duplicates from a known cursor.
 */
public final class FrameRingBuffer {

    private final int capacity;
    private final ArrayDeque<Entry> entries;
    private final Counter droppedCounter;
    private long nextSequence;

    public FrameRingBuffer(int capacity, Counter droppedCounter) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
        }
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(capacity);
        this.droppedCounter = droppedCounter;
        this.nextSequence = 0L;
    }

    public synchronized long offer(JsonNode frame) {
        long seq = nextSequence++;
        if (entries.size() == capacity) {
            entries.removeFirst();
            droppedCounter.increment();
        }
        entries.addLast(new Entry(seq, frame));
        return seq;
    }

    /** Frames with sequence {@code > since}, in arrival order. Pass {@code -1} for the full snapshot. */
    public synchronized List<JsonNode> snapshotSince(long since) {
        List<JsonNode> result = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.sequence > since) {
                result.add(e.frame);
            }
        }
        return result;
    }

    /** Sequence of the most recently added frame, or {@code -1} if empty. */
    public synchronized long latestSequence() {
        return nextSequence - 1;
    }

    public int capacity() {
        return capacity;
    }

    public synchronized int size() {
        return entries.size();
    }

    private record Entry(long sequence, JsonNode frame) {}
}
