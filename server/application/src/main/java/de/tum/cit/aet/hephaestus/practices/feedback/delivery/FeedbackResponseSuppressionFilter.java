package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackResponseSuppressionFilter {
    private final FeedbackBasisRepository basis;

    public Map<UUID, FeedbackStopReason> suppressedProblems(long workspaceId, Collection<UUID> problemIds) {
        if (problemIds.isEmpty()) return Map.of();
        return basis.findSuppressedProblems(workspaceId, problemIds).stream()
                .collect(Collectors.toUnmodifiableMap(
                        FeedbackBasisRepository.SuppressedProblem::getId,
                        row -> row.getResolution() == FeedbackResolution.DISAGREE
                                ? FeedbackStopReason.REACTED_DISAGREE
                                : FeedbackStopReason.REACTED_NOT_APPLICABLE,
                        (left, right) -> left == FeedbackStopReason.REACTED_DISAGREE ? left : right));
    }
}
