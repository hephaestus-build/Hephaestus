package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.*;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackNotificationQuery;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Daily summary of the instance administrator product-feedback inbox")
@RequiredArgsConstructor
public class ProductFeedbackDigestListener {
    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final ProductFeedbackNotificationQuery feedback;
    private final EmailRenderer renderer;
    private final EmailGateway gateway;
    private final EmailDeliveryMetrics metrics;
    private final EmailUnsubscribeLinks links;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ProductFeedbackDigestRequested event) {
        if (!clock.instant().isBefore(event.until().plus(Duration.ofDays(7)))) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK_DIGEST, Outcome.EXPIRED);
            return;
        }
        var token = subscriptions.unsubscribeToken(event.accountId(), NotificationSubscriptionKind.PRODUCT_FEEDBACK);
        if (!subscriptions.isDigestWindowCurrent(event.accountId(), event.from()) || token.isEmpty()) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK_DIGEST, Outcome.UNSUBSCRIBED);
            return;
        }
        var to = contacts.activeVerifiedAdministratorEmail(event.accountId());
        if (to.isEmpty()) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK_DIGEST, Outcome.NO_RECIPIENT);
            return;
        }
        long count = feedback.countBetween(event.from(), event.until());
        if (count == 0) return;
        var rendered = renderer.render(
                EmailKind.PRODUCT_FEEDBACK_DIGEST,
                Map.of("reportCount", count, "unsubscribeUrl", links.confirmationUrl(token.get())));
        var result = gateway.send(new EmailMessage(
                EmailKind.PRODUCT_FEEDBACK_DIGEST,
                to.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                links.url(token.get())));
        if (result.outcome().retryable()) {
            throw new NotificationDeliveryUnavailableException(EmailKind.PRODUCT_FEEDBACK_DIGEST, event.accountId());
        }
    }
}
