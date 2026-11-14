package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestTransport;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestWritten;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
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
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The email note that a weekly digest is ready, for a developer with no Slack identity in the workspace. It names
 * the week and the workspace and links to the digest, and nothing else (see the {@code agent.conversation.weeklydigest}
 * package).
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("The note names its workspace; delivery rechecks the account's current membership")
public class WeeklyDigestEmailListener implements WeeklyDigestTransport, EmailNotice<WeeklyDigestWritten> {

    /** A note older than this is no news any more; the digest stays in the app. */
    static final Duration EXPIRES_AFTER = Duration.ofHours(24);

    private final NotificationSubscriptionService subscriptions;
    private final AccountContactQuery contacts;
    private final AccountMembershipQuery memberships;
    private final EmailRenderer renderer;
    private final Emails emails;
    private final EmailUnsubscribeLinks links;
    private final Clock clock;

    @Override
    public WeeklyDigestPointer pointer() {
        return WeeklyDigestPointer.EMAIL;
    }

    @Override
    public boolean reaches(long workspaceId, long accountId) {
        return emails.configured()
                && contacts.activeVerifiedPrimaryEmail(accountId).isPresent();
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(WeeklyDigestWritten event) {
        if (event.pointer() == WeeklyDigestPointer.EMAIL) {
            emails.enqueue(this, event);
        }
    }

    @Override
    public EmailKind kind() {
        return EmailKind.WEEKLY_DIGEST;
    }

    @Override
    public Class<WeeklyDigestWritten> eventType() {
        return WeeklyDigestWritten.class;
    }

    @Override
    public Address address(WeeklyDigestWritten event) {
        return new Address(
                event.entryId() + ":" + event.accountId(),
                event.accountId(),
                event.workspaceId(),
                event.requestedAt().plus(EXPIRES_AFTER));
    }

    @Override
    public Prepared prepare(WeeklyDigestWritten event) {
        if (!clock.instant().isBefore(event.requestedAt().plus(EXPIRES_AFTER))) {
            return Prepared.withhold(Outcome.EXPIRED);
        }
        var token = subscriptions.unsubscribeToken(
                event.accountId(), NotificationSubscriptionKind.WEEKLY_DIGEST, event.requestedAt());
        if (token.isEmpty()) {
            return Prepared.withhold(Outcome.UNSUBSCRIBED);
        }
        var recipient = contacts.activeVerifiedPrimaryEmail(event.accountId());
        var workspace = memberships.membershipsForAccount(event.accountId()).stream()
                .filter(membership -> membership.workspaceId() == event.workspaceId())
                .findFirst();
        if (recipient.isEmpty() || workspace.isEmpty()) {
            return Prepared.withhold(Outcome.NO_RECIPIENT);
        }
        if (!emails.configured()) {
            return Prepared.withhold(Outcome.NOT_CONFIGURED);
        }
        var rendered = renderer.render(
                EmailKind.WEEKLY_DIGEST,
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
        return Prepared.send(new EmailMessage(
                EmailKind.WEEKLY_DIGEST,
                recipient.get(),
                rendered.subject(),
                rendered.text(),
                rendered.html(),
                links.url(token.get())));
    }
}
