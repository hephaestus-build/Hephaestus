package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import tools.jackson.databind.JsonNode;

/** Historical digest entries predate proactive runs and remain readable after the cutover. */
public final class WeeklyDigestTestFixtures {
    private WeeklyDigestTestFixtures() {}

    public static ConversationEntryDraft historical(String text, JsonNode facts, String requestId) {
        return new ConversationEntryDraft(
                ConversationEntry.Kind.DIGEST, ConversationEntry.Surface.SYSTEM, text, facts, null, requestId, null);
    }
}
