package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Product-feedback updates address the account that submitted the report")
@RequiredArgsConstructor
public class ProductFeedbackUpdateEmailPreparation {
    private static final Duration MAX_AGE = Duration.ofDays(7);

    private final NotificationSubscriptionService subscriptions;
    private final ApplicationEventPublisher events;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(ProductFeedbackUpdatedEvent event) {
        if (subscriptions.prepareProductFeedbackUpdates(event.accountId())) {
            events.publishEvent(new ProductFeedbackUpdateEmailRequested(
                    event.feedbackId(),
                    event.accountId(),
                    event.kind(),
                    event.issueUrl(),
                    event.publicIssue(),
                    event.detail(),
                    event.at(),
                    event.at().plus(MAX_AGE)));
        }
    }
}
