package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyOutcome;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The receipts that one attempt's conversation claims are due, published in the transaction that records how the
 * attempt or its Slack reply ended. The event publication registry keeps it until every receipt is stored, so a crash
 * after that commit or a failed receipt write is settled later ({@link ConversationTurnCompletion#settle}).
 *
 * @param attemptStart when the attempt started: its claims are those made from then on
 * @param until the last instant a claim of the attempt counts
 * @param shown the feedback that may be delivered, when the answer's successful {@code show_feedback} call showed it;
 *     {@code null} for all that the answer showed
 * @param unconfirmed the feedback that stays unconfirmed when it is not delivered
 * @param otherwise the outcome of every other claim
 */
public record ConversationReceiptsDue(
        long workspaceId,
        UUID turnId,
        Instant attemptStart,
        Instant until,
        @Nullable Set<UUID> shown,
        Set<UUID> unconfirmed,
        ConversationCopyOutcome otherwise,
        String unconfirmedReason,
        String failedReason) {

    public ConversationReceiptsDue {
        shown = shown == null ? null : Set.copyOf(shown);
        unconfirmed = Set.copyOf(unconfirmed);
    }

    ConversationCopyOutcome outcome(UUID feedbackId, boolean shownByAnswer) {
        if (shownByAnswer && (shown == null || shown.contains(feedbackId))) return ConversationCopyOutcome.DELIVERED;
        return unconfirmed.contains(feedbackId) ? ConversationCopyOutcome.UNCONFIRMED : otherwise;
    }
}
