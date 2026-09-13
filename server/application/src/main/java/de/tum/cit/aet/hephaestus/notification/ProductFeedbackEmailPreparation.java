package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationEmailFrequency;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackSubmittedEvent;
import java.time.Duration;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Product-feedback notifications address instance administrators")
@RequiredArgsConstructor
public class ProductFeedbackEmailPreparation {
    private static final Duration MAX_AGE = Duration.ofDays(7);

    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final ApplicationEventPublisher events;

    // Each recipient publication commits with the feedback. An async parent could duplicate the fan-out on retry.
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(ProductFeedbackSubmittedEvent event) {
        var subscribed =
                new HashSet<>(subscriptions.subscribedAccountIds(NotificationSubscriptionKind.PRODUCT_FEEDBACK));
        for (long accountId : contacts.activeVerifiedAdministratorIds()) {
            if (subscribed.contains(accountId)
                    && subscriptions.hasFrequency(accountId, NotificationEmailFrequency.IMMEDIATE)) {
                events.publishEvent(new ProductFeedbackEmailRequested(
                        event.feedbackId(), accountId, event.submittedAt().plus(MAX_AGE)));
            }
        }
    }
}
