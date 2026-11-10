package de.tum.cit.aet.hephaestus.mentor;

import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;

/** Partial answers as the conversation loop sends them, for tests that need one. */
public final class MentorPartialUpdates {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private MentorPartialUpdates() {}

    /** The whole snapshot {@code partial}, which every reader takes, after any earlier one. */
    public static MentorTurnProgress.Update whole(JsonNode partial) {
        return new MentorTurnProgress.Update(SEQUENCE.incrementAndGet(), 0, 0, 0, partial.toString());
    }
}
