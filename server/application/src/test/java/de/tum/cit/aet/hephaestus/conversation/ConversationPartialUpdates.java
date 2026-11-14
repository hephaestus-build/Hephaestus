package de.tum.cit.aet.hephaestus.conversation;

import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;

/** Partial answers as the conversation loop sends them, for tests that need one. */
public final class ConversationPartialUpdates {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private ConversationPartialUpdates() {}

    /** The whole snapshot {@code partial}, which every reader takes, after any earlier one. */
    public static ConversationTurnProgress.Update whole(JsonNode partial) {
        return new ConversationTurnProgress.Update(SEQUENCE.incrementAndGet(), 0, 0, 0, partial.toString());
    }
}
