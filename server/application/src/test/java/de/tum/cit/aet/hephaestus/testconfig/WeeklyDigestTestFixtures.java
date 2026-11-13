package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryDraft;
import tools.jackson.databind.JsonNode;

/** Historical digest entries predate proactive runs and remain readable after the cutover. */
public final class WeeklyDigestTestFixtures {
    private WeeklyDigestTestFixtures() {}

    public static MentorEntryDraft historical(String text, JsonNode facts, String requestId) {
        return new MentorEntryDraft(
                MentorEntry.Kind.DIGEST, MentorEntry.Surface.SYSTEM, text, facts, null, requestId, null);
    }
}
