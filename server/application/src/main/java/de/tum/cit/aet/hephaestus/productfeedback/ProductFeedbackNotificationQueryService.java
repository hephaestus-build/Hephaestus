package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackNotificationQuery;
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
    public boolean cleanupNeedsAction(UUID cleanupId) {
        return cleanups.findByIdAndManualSinceNotNull(cleanupId).isPresent();
    }
}
