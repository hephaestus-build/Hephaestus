package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.EmailUnsubscribeLinks;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionKind;
import de.tum.cit.aet.hephaestus.notification.preferences.NotificationSubscriptionService;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackNotificationQuery;
import de.tum.cit.aet.hephaestus.productfeedback.notification.ProductFeedbackUpdatedEvent;
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
public class ProductFeedbackUpdateEmailListener implements EmailNotice<ProductFeedbackUpdateEmailRequested> {
    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final ProductFeedbackNotificationQuery feedback;
    private final EmailRenderer renderer;
    private final Emails emails;
    private final EmailUnsubscribeLinks unsubscribeLinks;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ProductFeedbackUpdateEmailRequested event) {
        emails.enqueue(this, event);
    }

    @Override
    public EmailKind kind() {
        return EmailKind.PRODUCT_FEEDBACK_UPDATE;
    }

    @Override
    public Class<ProductFeedbackUpdateEmailRequested> eventType() {
        return ProductFeedbackUpdateEmailRequested.class;
    }

    @Override
    public Address address(ProductFeedbackUpdateEmailRequested event) {
        return new Address(
                event.kind() + ":" + event.feedbackId() + ":" + event.accountId() + ":"
                        + event.requestedAt().toEpochMilli(),
                event.accountId(),
                null,
                event.expiresAt());
    }

    @Override
    public Prepared prepare(ProductFeedbackUpdateEmailRequested event) {
        if (!clock.instant().isBefore(event.expiresAt()) || !feedback.exists(event.feedbackId())) {
            return Prepared.withhold(Outcome.EXPIRED);
        }
        var to = contacts.activeVerifiedPrimaryEmail(event.accountId());
        var token = subscriptions.unsubscribeToken(
                event.accountId(), NotificationSubscriptionKind.PRODUCT_FEEDBACK_UPDATES, event.requestedAt());
        if (token.isEmpty()) {
            return Prepared.withhold(Outcome.UNSUBSCRIBED);
        }
        if (to.isEmpty()) {
            return Prepared.withhold(Outcome.NO_RECIPIENT);
        }
        if (!emails.configured()) {
            return Prepared.withhold(Outcome.NOT_CONFIGURED);
        }
        var model = new HashMap<String, Object>();
        model.put("unsubscribeUrl", unsubscribeLinks.confirmationUrl(token.get()));
        model.put("updateKind", event.kind().name());
        model.put("publicIssue", event.publicIssue());
        // A private issue's address names the private repository; the reporter gets only the instance page.
        String issueUrl = event.issueUrl();
        if (event.publicIssue() && issueUrl != null) model.put("issueUrl", issueUrl);
        // A duplicate's detail is the original issue's address; a release name is never one.
        String detail = event.detail();
        if (detail != null
                && (event.publicIssue() || event.kind() == ProductFeedbackUpdatedEvent.Kind.INCLUDED_IN_RELEASE))
            model.put("detail", detail);
        var rendered = renderer.render(EmailKind.PRODUCT_FEEDBACK_UPDATE, model);
        return Prepared.send(new EmailMessage(
                EmailKind.PRODUCT_FEEDBACK_UPDATE,
                to.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                unsubscribeLinks.url(token.get())));
    }
}
