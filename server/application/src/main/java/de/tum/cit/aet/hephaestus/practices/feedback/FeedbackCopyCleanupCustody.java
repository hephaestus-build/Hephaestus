package de.tum.cit.aet.hephaestus.practices.feedback;

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
        for (UUID id : feedbackIds.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList()) {
            if (feedback.lockByIdAndWorkspaceId(id, workspaceId).isEmpty()) continue;
            for (var copy : copies.lockByFeedbackId(workspaceId, id)) {
                // A copy that readback proved absent needs no cleanup, whether it failed or was then withheld.
                boolean absent =
                        (copy.getState() == FeedbackCopyState.FAILED || copy.getState() == FeedbackCopyState.WITHHELD)
                                && copy.getReadback() == FeedbackCopyReadback.ABSENT;
                if (!absent
                        && copy.getPostedAt() == null
                        && (copy.getWriteStartedAt() != null || copy.getState() == FeedbackCopyState.WRITE_STARTED))
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "An unresolved feedback copy still needs its cleanup receipt.");
            }
        }
    }
}
