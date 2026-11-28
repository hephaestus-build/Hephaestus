package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class FeedbackCopyCleanupCustody {
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;

    @Transactional
    public void requireAcknowledged(long workspaceId, Collection<UUID> feedbackIds) {
        require(workspaceId, feedbackIds, false);
    }

    /**
     * As {@link #requireAcknowledged}, for a person's erasure. A conversation copy whose receipt settled it passes, also
     * unconfirmed: the same step redacts the answers of its turn, and the erasure's admission decides whether a Slack
     * reply needs an administrator's removal first (#5440). A claim that no receipt settled yet still refuses.
     */
    @Transactional
    public void requireAcknowledgedForPersonErasure(long workspaceId, Collection<UUID> feedbackIds) {
        require(workspaceId, feedbackIds, true);
    }

    private void require(long workspaceId, Collection<UUID> feedbackIds, boolean settledConversationCopiesPass) {
        for (UUID id : feedbackIds.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList()) {
            if (feedback.lockByIdAndWorkspaceId(id, workspaceId).isEmpty()) continue;
            for (var copy : copies.lockByFeedbackId(workspaceId, id)) {
                // A copy that readback proved absent needs no cleanup, whether it failed or was then withheld.
                boolean absent =
                        (copy.getState() == FeedbackCopyState.FAILED || copy.getState() == FeedbackCopyState.WITHHELD)
                                && copy.getReadback() == FeedbackCopyReadback.ABSENT;
                // A receipt that did not post the copy always records why; a claim leaves last_error empty.
                boolean settledConversationCopy = settledConversationCopiesPass
                        && copy.getKind() == FeedbackCopyKind.CHAT_TURN
                        && copy.getLastError() != null;
                if (!absent
                        && !settledConversationCopy
                        && copy.getPostedAt() == null
                        && (copy.getWriteStartedAt() != null || copy.getState() == FeedbackCopyState.WRITE_STARTED))
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "An unresolved feedback copy still needs its cleanup receipt.");
            }
        }
    }
}
