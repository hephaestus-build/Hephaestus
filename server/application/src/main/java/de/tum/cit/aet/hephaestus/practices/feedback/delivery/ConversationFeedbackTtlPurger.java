package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackOutcomes;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import java.time.Instant;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ConversationFeedbackTtlPurger {
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
    private final FeedbackOutcomes outcomes;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireWorkspaceBefore(long workspaceId, Instant cutoff) {
        var expired = feedback.findExpiredConversation(workspaceId, cutoff);
        int count = 0;
        for (var row : expired.stream()
                .sorted(Comparator.comparing(piece -> piece.getId().toString()))
                .toList()) {
            feedback.lockByIdAndWorkspaceId(row.getId(), workspaceId);
            copies.withholdPlanned(workspaceId, row.getId(), "CONVERSATION_EXPIRED");
            count += outcomes.expireConversation(row, cutoff);
        }
        return count;
    }
}
