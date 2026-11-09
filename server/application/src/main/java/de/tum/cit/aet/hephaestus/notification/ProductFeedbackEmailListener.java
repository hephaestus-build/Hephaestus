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
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Product-feedback notifications address instance administrators")
@RequiredArgsConstructor
public class ProductFeedbackEmailListener implements EmailNotice<ProductFeedbackEmailRequested> {
    private final AccountContactQuery contacts;
    private final NotificationSubscriptionService subscriptions;
    private final ProductFeedbackNotificationQuery feedback;
    private final EmailRenderer renderer;
    private final Emails emails;
    private final EmailUnsubscribeLinks unsubscribeLinks;
    private final Clock clock;

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(ProductFeedbackEmailRequested event) {
        emails.enqueue(this, event);
    }

    @Override
    public EmailKind kind() {
        return EmailKind.PRODUCT_FEEDBACK;
    }

    @Override
    public Class<ProductFeedbackEmailRequested> eventType() {
        return ProductFeedbackEmailRequested.class;
    }

    @Override
    public Address address(ProductFeedbackEmailRequested event) {
        return new Address(
                event.kind() + ":" + event.feedbackId() + ":" + event.accountId() + ":"
                        + event.requestedAt().toEpochMilli(),
                event.accountId(),
                null,
                event.expiresAt());
    }

    @Override
    public Prepared prepare(ProductFeedbackEmailRequested event) {
        boolean cleanup = event.kind() == ProductFeedbackEmailRequested.Kind.CLEANUP;
        // Only the steps still waiting are listed; an admin may have confirmed some before a retry.
        List<String> waiting = cleanup ? feedback.waitingCleanupIssues(listed(event)) : List.of();
        boolean current = cleanup ? !waiting.isEmpty() : feedback.exists(event.feedbackId());
        if (!clock.instant().isBefore(event.expiresAt()) || !current) {
            return Prepared.withhold(Outcome.EXPIRED);
        }
        var to = contacts.activeVerifiedAdministratorEmail(event.accountId());
        var token = subscriptions.unsubscribeToken(
                event.accountId(), NotificationSubscriptionKind.PRODUCT_FEEDBACK, event.requestedAt());
        if (token.isEmpty()) {
            return Prepared.withhold(Outcome.UNSUBSCRIBED);
        }
        if (to.isEmpty()) {
            return Prepared.withhold(Outcome.NO_RECIPIENT);
        }
        if (!emails.configured()) {
            return Prepared.withhold(Outcome.NOT_CONFIGURED);
        }
        var rendered = renderer.render(
                EmailKind.PRODUCT_FEEDBACK,
                Map.of(
                        "unsubscribeUrl",
                        unsubscribeLinks.confirmationUrl(token.get()),
                        "feedbackKind",
                        event.kind().name(),
                        "cleanupIssues",
                        waiting));
        return Prepared.send(new EmailMessage(
                EmailKind.PRODUCT_FEEDBACK,
                to.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                unsubscribeLinks.url(token.get())));
    }

    private static List<UUID> listed(ProductFeedbackEmailRequested event) {
        List<UUID> ids = event.cleanupIds();
        return ids == null ? List.of(event.feedbackId()) : ids;
    }
}
