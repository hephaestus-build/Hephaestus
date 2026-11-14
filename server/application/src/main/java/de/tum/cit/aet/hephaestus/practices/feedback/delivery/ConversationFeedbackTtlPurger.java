package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackOutcomes;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ConversationFeedbackTtlPurger {
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;
    private final FeedbackOutcomes outcomes;

    /** Expires one row. A row that left {@code READY} after it was selected keeps its state and its planned copies. */
    @Transactional
    public int expire(long workspaceId, UUID id, Instant cutoff) {
        return feedback.lockByIdAndWorkspaceId(id, workspaceId)
                .map(row -> {
                    int expired = outcomes.expireConversation(row, cutoff);
                    if (expired == 1) copies.withholdPlanned(workspaceId, id, "CONVERSATION_EXPIRED");
                    return expired;
                })
                .orElse(0);
    }
}
