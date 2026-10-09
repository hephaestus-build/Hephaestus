package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestTransport;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestWritten;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
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
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * The email note that a weekly digest is ready, for a developer with no Slack identity in the workspace. It names
 * the week and the workspace and links to the digest, and nothing else (see the {@code agent.mentor.weeklydigest}
 * package).
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("The note names its workspace; delivery rechecks the account's current membership")
public class WeeklyDigestEmailListener implements WeeklyDigestTransport {

    /** A note older than this is no news any more; the digest stays in the app. */
    static final Duration EXPIRES_AFTER = Duration.ofHours(24);

    private final NotificationSubscriptionService subscriptions;
    private final AccountContactQuery contacts;
    private final AccountWorkspaceMembershipQuery memberships;
    private final EmailRenderer renderer;
    private final EmailGateway gateway;
    private final EmailUnsubscribeLinks links;
    private final EmailDeliveryMetrics metrics;
    private final Clock clock;

    @Override
    public WeeklyDigestPointer pointer() {
        return WeeklyDigestPointer.EMAIL;
    }

    @Override
    public boolean reaches(long workspaceId, long accountId, long developerId) {
        return gateway.configured()
                && contacts.activeVerifiedPrimaryEmail(accountId).isPresent();
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void on(WeeklyDigestWritten event) {
        if (event.pointer() != WeeklyDigestPointer.EMAIL) {
            return;
        }
        if (!clock.instant().isBefore(event.requestedAt().plus(EXPIRES_AFTER))) {
            metrics.record(EmailKind.MENTOR_DIGEST, Outcome.EXPIRED);
            return;
        }
        var token = subscriptions.unsubscribeToken(
                event.accountId(), NotificationSubscriptionKind.MENTOR_DIGEST, event.requestedAt());
        if (token.isEmpty()) {
            metrics.record(EmailKind.MENTOR_DIGEST, Outcome.UNSUBSCRIBED);
            return;
        }
        var recipient = contacts.activeVerifiedPrimaryEmail(event.accountId());
        var workspace = memberships.membershipsForAccount(event.accountId()).stream()
                .filter(membership -> membership.workspaceId() == event.workspaceId())
                .findFirst();
        if (recipient.isEmpty() || workspace.isEmpty()) {
            metrics.record(EmailKind.MENTOR_DIGEST, Outcome.NO_RECIPIENT);
            return;
        }
        if (!gateway.configured()) {
            metrics.record(EmailKind.MENTOR_DIGEST, Outcome.NOT_CONFIGURED);
            return;
        }
        var rendered = renderer.render(
                EmailKind.MENTOR_DIGEST,
                Map.of(
                        "workspaceSlug",
                        workspace.get().workspaceSlug(),
                        "workspaceName",
                        workspace.get().workspaceName(),
                        "weekDays",
                        event.weekDays(),
                        "entryId",
                        event.entryId().toString(),
                        "unsubscribeUrl",
                        links.confirmationUrl(token.get())));
        var result = gateway.send(new EmailMessage(
                EmailKind.MENTOR_DIGEST,
                recipient.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                links.url(token.get())));
        if (result.outcome().retryable()) {
            throw new NotificationDeliveryUnavailableException(EmailKind.MENTOR_DIGEST, event.accountId());
        }
    }
}
