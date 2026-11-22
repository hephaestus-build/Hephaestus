package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class RepeatedSummaryCheck {
    private final FeedbackRepository feedback;
    private final FeedbackCopyRepository copies;

    /**
     * Whether {@code body} repeats the last summary confirmed on the work. A summary whose note Hephaestus closed as
     * fixed is not repeated by the same words: the problem came back, and only a new note says so where Hephaestus
     * never reopens a thread.
     */
    public boolean repeats(long workspaceId, long itemId, long developerId, String body) {
        return feedback.findLastConfirmedSummary(workspaceId, itemId, developerId)
                .filter(prior -> body.equals(prior.getBody()))
                .filter(prior -> copies.findClosedAsFixed(
                                workspaceId,
                                copies
                                        .findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, prior.getId())
                                        .stream()
                                        .map(FeedbackCopy::getId)
                                        .toList())
                        .isEmpty())
                .isPresent();
    }
}
