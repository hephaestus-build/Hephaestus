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
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackNotificationQuery;
import java.time.Clock;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Product-feedback notifications address the account that submitted the report")
@RequiredArgsConstructor
public class ProductFeedbackUpdateEmailListener {
    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final ProductFeedbackNotificationQuery feedback;
    private final EmailRenderer renderer;
    private final EmailGateway gateway;
    private final EmailDeliveryMetrics metrics;
    private final EmailUnsubscribeLinks unsubscribeLinks;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ProductFeedbackUpdateEmailRequested event) {
        if (!clock.instant().isBefore(event.expiresAt()) || !feedback.exists(event.feedbackId())) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK_UPDATE, Outcome.EXPIRED);
            return;
        }
        var to = contacts.activeVerifiedPrimaryEmail(event.accountId());
        var token = subscriptions.unsubscribeToken(
                event.accountId(), NotificationSubscriptionKind.PRODUCT_FEEDBACK_UPDATES, event.requestedAt());
        if (token.isEmpty()) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK_UPDATE, Outcome.UNSUBSCRIBED);
            return;
        }
        if (to.isEmpty()) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK_UPDATE, Outcome.NO_RECIPIENT);
            return;
        }
        if (!gateway.configured()) {
            metrics.record(EmailKind.PRODUCT_FEEDBACK_UPDATE, Outcome.NOT_CONFIGURED);
            return;
        }
        var model = new HashMap<String, Object>();
        model.put("unsubscribeUrl", unsubscribeLinks.confirmationUrl(token.get()));
        model.put("updateKind", event.kind().name());
        model.put("publicIssue", event.publicIssue());
        // A private issue's address names the private repository; the reporter gets only the instance page.
        String issueUrl = event.issueUrl();
        if (event.publicIssue() && issueUrl != null) model.put("issueUrl", issueUrl);
        String detail = event.detail();
        if (event.publicIssue() && detail != null) model.put("detail", detail);
        var rendered = renderer.render(EmailKind.PRODUCT_FEEDBACK_UPDATE, model);
        var result = gateway.send(new EmailMessage(
                EmailKind.PRODUCT_FEEDBACK_UPDATE,
                to.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                unsubscribeLinks.url(token.get())));
        if (result.outcome().retryable()) {
            throw new NotificationDeliveryUnavailableException(EmailKind.PRODUCT_FEEDBACK_UPDATE, event.accountId());
        }
    }
}
