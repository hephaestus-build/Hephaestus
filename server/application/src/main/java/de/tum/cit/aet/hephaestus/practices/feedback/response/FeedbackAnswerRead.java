package de.tum.cit.aet.hephaestus.practices.feedback.response;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackAnswerDTO;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The developer's current answer to one piece of feedback, as a staff route shows it inside the correction window. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackAnswerRead {
    private final FeedbackResponseRepository responses;
    private final Clock clock;

    public @Nullable FeedbackAnswerDTO current(long workspaceId, UUID feedbackId) {
        var answeredAt = responses.responseTimes(workspaceId, List.of(feedbackId)).stream()
                .map(FeedbackResponseRepository.ResponseTime::answeredAt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return responses
                .findCurrent(workspaceId, feedbackId)
                .map(response -> FeedbackAnswerDTO.of(response, answeredAt, clock.instant()))
                .orElse(null);
    }
}
