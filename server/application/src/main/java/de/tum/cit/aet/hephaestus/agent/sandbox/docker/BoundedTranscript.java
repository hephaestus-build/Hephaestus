package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * A container's output, collected whole unless it is enormous, and never silently cut.
 *
 * <p>A review's transcript is the only account of what it did: which practices it observed, what it was
 * refused and why, where its time went. Keeping a prefix loses the ending, where a run says how it
 * failed; keeping a suffix loses the beginning, where it says what it was asked and what it read. So
 * everything is kept until a run is large enough to threaten the collecting process, and past that
 * point both ends are kept with a line between them saying exactly how much is missing — a reader is
 * never left to wonder whether they are looking at the whole thing.
 */
final class BoundedTranscript {

    private final int headLimit;
    private final int tailLimit;
    private final StringBuilder head = new StringBuilder();
    private final Deque<String> tail = new ArrayDeque<>();
    private int tailChars;
    private long dropped;

    BoundedTranscript(int headLimit, int tailLimit) {
        if (headLimit <= 0 || tailLimit <= 0) {
            throw new IllegalArgumentException("head and tail limits must be positive");
        }
        this.headLimit = headLimit;
        this.tailLimit = tailLimit;
    }

    /** Frames arrive on a Docker callback thread while the caller waits on completion. */
    synchronized void append(String chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        String rest = chunk;
        if (head.length() < headLimit) {
            int room = Math.min(headLimit - head.length(), rest.length());
            head.append(rest, 0, room);
            rest = rest.substring(room);
            if (rest.isEmpty()) {
                return;
            }
        }
        tail.addLast(rest);
        tailChars += rest.length();
        // Whole chunks leave the front, and the one whose departure would take the tail under its limit
        // stays: trimming costs the chunks it drops rather than a copy of everything it keeps, and no
        // chunk is ever split. The deque therefore never empties, so the chunk that arrived last — the
        // ending — is never the one evicted.
        while (tailChars - Objects.requireNonNull(tail.peekFirst()).length() >= tailLimit) {
            String evicted = tail.removeFirst();
            tailChars -= evicted.length();
            dropped += evicted.length();
        }
    }

    /** How many characters were dropped from the middle; zero means this transcript is complete. */
    synchronized long dropped() {
        return dropped;
    }

    @Override
    public synchronized String toString() {
        StringBuilder out = new StringBuilder(head.length() + tailChars + 128);
        out.append(head);
        if (dropped > 0) {
            out.append("\n[hephaestus] ")
                    .append(dropped)
                    .append(" characters of this transcript were dropped here: the run produced more output than ")
                    .append(headLimit + tailLimit)
                    .append(" characters. What follows is its ending.\n");
        }
        for (String chunk : tail) {
            out.append(chunk);
        }
        return out.toString();
    }
}
