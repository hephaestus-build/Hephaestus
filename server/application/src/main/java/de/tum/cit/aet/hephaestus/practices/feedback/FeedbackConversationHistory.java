package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads conversation links for feedback already selected for its developer. It binds the developer to the caller's
 * transaction, so a transaction bound to another owner is refused, and a caller that reads for several developers reads
 * each in its own transaction.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@OwnerScope.StoredOwnerOperation
class FeedbackConversationHistory {
    private final FeedbackQueryRepository queries;
    private final OwnerScope owners;

    @Transactional(readOnly = true)
    public List<FeedbackQueryRepository.FeedbackThread> threads(long workspaceId, List<Feedback> ownFeedback) {
        long developer = ownFeedback.getFirst().getDeveloperId();
        if (ownFeedback.stream().anyMatch(row -> row.getDeveloperId() != developer)) {
            throw new IllegalArgumentException("A conversation history read requires one developer.");
        }
        owners.ofJob(developer);
        return queries.threadIds(
                workspaceId, ownFeedback.stream().map(Feedback::getId).toList());
    }
}
