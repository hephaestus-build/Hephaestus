package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryMetrics;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailGateway;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.EmailUnsubscribeLinks;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationEmailFrequency;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackNotificationQuery;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Product-feedback notifications address instance administrators")
@RequiredArgsConstructor
public class ProductFeedbackEmailListener {
    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final ProductFeedbackNotificationQuery feedback;
    private final EmailRenderer renderer;
    private final EmailGateway gateway;
    private final EmailDeliveryMetrics metrics;
    private final EmailUnsubscribeLinks unsubscribeLinks;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ProductFeedbackEmailRequested event) {
        if (!clock.instant().isBefore(event.expiresAt()) || !feedback.exists(event.feedbackId())) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK, Outcome.EXPIRED);
            return;
        }
        var to = contacts.activeVerifiedAdministratorEmail(event.accountId());
        var token = subscriptions.unsubscribeToken(event.accountId(), NotificationSubscriptionKind.PRODUCT_FEEDBACK);
        if (!subscriptions.hasFrequency(event.accountId(), NotificationEmailFrequency.IMMEDIATE) || token.isEmpty()) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK, Outcome.UNSUBSCRIBED);
            return;
        }
        if (to.isEmpty()) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK, Outcome.NO_RECIPIENT);
            return;
        }
        var rendered = renderer.render(
                EmailKind.PRODUCT_FEEDBACK, Map.of("unsubscribeUrl", unsubscribeLinks.confirmationUrl(token.get())));
        var result = gateway.send(new EmailMessage(
                EmailKind.PRODUCT_FEEDBACK,
                to.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                unsubscribeLinks.url(token.get())));
        if (result.outcome().retryable()) {
            throw new NotificationDeliveryUnavailableException(EmailKind.PRODUCT_FEEDBACK, event.accountId());
        }
    }
}
