package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackNotificationQuery;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance administrators receive notifications for the instance-wide product-feedback inbox")
class ProductFeedbackNotificationQueryService implements ProductFeedbackNotificationQuery {
    private final ProductFeedbackRepository feedback;
    private final ProductFeedbackCleanupRepository cleanups;

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID feedbackId) {
        return feedback.existsById(feedbackId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> waitingCleanupIssues(List<UUID> cleanupIds) {
        return cleanups.findAllByIdInAndManualSinceNotNullOrderByRepositoryAscIssueNumberAsc(cleanupIds).stream()
                .map(row -> row.getIssueNumber() == null
                        ? row.getRepository() + ": the issue whose body contains "
                                + IssueRenderer.markerText(row.getMarker())
                        : "https://github.com/" + row.getRepository() + "/issues/" + row.getIssueNumber())
                .toList();
    }
}
