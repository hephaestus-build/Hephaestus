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
 * Replaces PREPARED in-app and conversation feedback without retiring DELIVERED feedback.
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
        /** Prior feedback remains DELIVERED; the replacement links to it as a continuation. */
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
}
