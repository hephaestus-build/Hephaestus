package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Retires the feedback a newer piece of feedback takes the place of: on the conversation lane only feedback
 * still PREPARED ({@link #supersede}), on the in-app lane the card still open, read or not
 * ({@link #replaceOpen}).
 *
 * <p>On the in-app lane the page, not the ledger, is what must hold one live card per habit: the card still
 * open about a habit is retired by the newer card about it, read or not ({@link #replaceOpen}). A closed card
 * — resolved by the work or the developer, or closed because the practice changed — is never replaced, and
 * only the caller can tell which it is, so only the caller decides.
 *
 * <p>The caller must insert the replacement in the same transaction as the supersession claim.
 * In-context reconciliation is handled separately by {@link FeedbackLedgerRecorder}.
 */
@Component
public class FeedbackSupersession {

    private static final Logger log = LoggerFactory.getLogger(FeedbackSupersession.class);

    // Retry a moved thread head, but bound contention with other producers.
    private static final int MAX_ATTEMPTS = 3;

    private final FeedbackRepository feedbackRepository;

    FeedbackSupersession(FeedbackRepository feedbackRepository) {
        this.feedbackRepository = feedbackRepository;
    }

    /** @param replacesId prior feedback to link, or {@code null} for a standalone piece of feedback */
    public record Outcome(Disposition disposition, @Nullable UUID replacesId) {
        public static Outcome standalone() {
            return new Outcome(Disposition.NEW, null);
        }

        public boolean retiredSomething() {
            return disposition == Disposition.SUPERSEDED;
        }
    }

    public enum Disposition {
        /** Prior feedback was retired; the replacement takes its place. */
        SUPERSEDED,
        /**
         * Prior feedback remains DELIVERED; the replacement links to it as a continuation. Only
         * {@link #supersede} answers this; the in-app lane's {@link #replaceOpen} retires a read card too.
         */
        CONTINUED,
        /** No replacement link was acquired. */
        NEW,
    }

    /**
     * Attempts to retire the latest row on a recipient's channel-specific thread if it is PREPARED.
     * A failed claim does not prevent the caller from preparing new feedback.
     */
    public Outcome supersede(long workspaceId, long recipientUserId, FeedbackChannel channel, String threadKey) {
        UUID lastRefused = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<UUID> target =
                    feedbackRepository.findLatestOnThread(workspaceId, recipientUserId, channel.name(), threadKey);
            if (target.isEmpty()) {
                return Outcome.standalone();
            }
            UUID targetId = target.get();
            if (targetId.equals(lastRefused)) {
                // Do not retry an unchanged, unclaimable head.
                break;
            }
            if (feedbackRepository.markSuperseded(workspaceId, targetId) == 1) {
                return new Outcome(Disposition.SUPERSEDED, targetId);
            }
            if (feedbackRepository.isDelivered(workspaceId, targetId)) {
                log.info(
                        "Supersession target was read first; continuing the thread instead: channel={}, target={}",
                        channel,
                        targetId);
                return new Outcome(Disposition.CONTINUED, targetId);
            }
            lastRefused = targetId;
        }
        // Keep the shared thread key without claiming a replacement link we did not acquire.
        log.info("Supersession found nothing live to claim: channel={}, threadKey={}", channel, threadKey);
        return Outcome.standalone();
    }

    /**
     * Retire the card still open on one in-app thread so a newer card about the same habit can take its
     * place — queued or already read, but never closed, which the caller has established by reading the
     * card the way the page reads it ({@code PreviousInAppFeedback}).
     *
     * <p>Two compare-and-sets, one per state a live card can be in, so a run racing this one claims it at
     * most once; a card another run already retired, or that has since been withheld, matches neither, and
     * the new card is written on its own. Never throws for the same reason {@link #supersede} does not:
     * losing the claim is ordinary, and the composed words are still owed.
     *
     * @param openId the open card, found by the caller on the thread this card is about to be written on
     */
    public Outcome replaceOpen(long workspaceId, UUID openId) {
        if (feedbackRepository.markSuperseded(workspaceId, openId) == 1
                || feedbackRepository.supersedeDelivered(workspaceId, openId) == 1) {
            return new Outcome(Disposition.SUPERSEDED, openId);
        }
        log.info("Open in-app card was already claimed by another run; written as new: target={}", openId);
        return Outcome.standalone();
    }
}
