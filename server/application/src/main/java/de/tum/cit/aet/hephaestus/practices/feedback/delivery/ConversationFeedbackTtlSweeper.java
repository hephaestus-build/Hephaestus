package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.SweepRowFailures;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ConversationFeedbackTtlSweeper {
    static final int TTL_DAYS = 14;
    private static final Logger log = LoggerFactory.getLogger(ConversationFeedbackTtlSweeper.class);
    private final FeedbackRepository feedback;
    private final ConversationFeedbackTtlPurger expiry;
    private final Clock clock;

    @WorkspaceAgnostic("Finds expired conversation workspace ids globally and expires each workspace" + " separately")
    @Scheduled(fixedDelayString = "PT1H")
    public void sweep() {
        var cutoff = clock.instant().minus(Duration.ofDays(TTL_DAYS));
        for (long workspaceId : feedback.expiredConversationWorkspaces(cutoff))
            expireWorkspaceBefore(workspaceId, cutoff);
    }

    /** Each row expires in its own transaction, so a row that cannot expire holds back only itself. */
    public int expireWorkspaceBefore(long workspaceId, Instant cutoff) {
        int count = 0;
        for (UUID id : feedback.findExpiredConversation(workspaceId, cutoff).stream()
                .map(Feedback::getId)
                .sorted(Comparator.comparing(UUID::toString))
                .toList()) {
            try {
                count += expiry.expire(workspaceId, id, cutoff);
            } catch (RuntimeException failure) {
                SweepRowFailures.skipUnlessTransient(log, "conversation-feedback-expiry", workspaceId, id, failure);
            }
        }
        return count;
    }
}
