package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Redacts the Hephaestus answers that may show feedback about an erased person, in the caller's transaction. The
 * erasure of a feedback copy calls it before the copy, and with it the link to the answer, goes.
 */
@FunctionalInterface
public interface PersonFeedbackAnswerRedaction {
    /**
     * Redacts every answer of the claimed turns and the bound answers, with the answers that derive from them. A turn
     * that claimed the feedback can show its words in any of its answers, whatever its receipt says.
     *
     * @param claimedTurns the turns that claimed a copy, by the workspace of the copy
     * @param boundAnswers the answers that a receipt bound to a copy
     */
    void redactClaimedAnswers(Map<Long, ? extends Collection<UUID>> claimedTurns, Collection<UUID> boundAnswers);
}
