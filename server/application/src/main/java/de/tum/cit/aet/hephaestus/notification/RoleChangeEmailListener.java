package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.event.RoleChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailKind;
import de.tum.cit.aet.hephaestus.notification.email.EmailMessage;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice;
import de.tum.cit.aet.hephaestus.notification.email.EmailRenderer;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells one recipient who changed which role, when, and where to review it. The email names people only while
 * their accounts permit it. The personal-data policy owns anonymization of retained notices.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Delivery rechecks the recipient against the change's own workspace or the instance admin role")
public class RoleChangeEmailListener implements EmailNotice<RoleChangeEmailRequested> {
    static final Duration EXPIRES_AFTER = Duration.ofDays(7);
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm z", EmailRenderer.LOCALE);

    /** The region id, not {@code ZoneOffset.UTC}, so the email reads “UTC” and not “Z”. */
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final AccountContactQuery contacts;
    private final AccountDirectory accounts;
    private final AccountMembershipQuery memberships;
    private final EmailRenderer renderer;
    private final Emails emails;
    private final Clock clock;

    @ApplicationModuleListener
    public void on(RoleChangeEmailRequested event) {
        var change = event.change();
        var referenced = new TreeSet<Long>();
        referenced.add(event.accountId());
        if (change.accountId() != null) referenced.add(change.accountId());
        if (change.actorAccountId() != null) referenced.add(change.actorAccountId());
        for (long accountId : referenced) {
            if (!contacts.lockForNotification(accountId, accountId == event.accountId())) {
                if (accountId == event.accountId()) return;
                change = change.anonymize(accountId);
            }
        }
        emails.enqueue(this, new RoleChangeEmailRequested(change, event.accountId()));
    }

    @Override
    public EmailKind kind() {
        return EmailKind.ROLE_CHANGED;
    }

    @Override
    public Class<RoleChangeEmailRequested> eventType() {
        return RoleChangeEmailRequested.class;
    }

    @Override
    public Address address(RoleChangeEmailRequested event) {
        var change = event.change();
        Long workspaceId = change.workspaceId();
        return new Address(
                change.changeId() + ":" + event.accountId(),
                event.accountId(),
                workspaceId,
                change.occurredAt().plus(EXPIRES_AFTER));
    }

    @Override
    public Prepared prepare(RoleChangeEmailRequested event) {
        var change = event.change();
        if (!clock.instant().isBefore(change.occurredAt().plus(EXPIRES_AFTER))) {
            return Prepared.withhold(Outcome.EXPIRED);
        }
        var affected = change.accountId() == null
                ? Optional.<AccountDirectory.AccountView>empty()
                : active(change.accountId());
        Long actorId = change.actorAccountId();
        var actor = actorId == null ? Optional.<AccountDirectory.AccountView>empty() : active(actorId);
        Long workspaceId = change.workspaceId();
        var workspace = workspaceId == null
                ? Optional.<AccountMembershipQuery.WorkspaceView>empty()
                : memberships.workspace(workspaceId);
        if (workspaceId != null && workspace.isEmpty()) {
            return Prepared.withhold(Outcome.EXPIRED);
        }
        boolean self = Objects.equals(event.accountId(), change.accountId());
        var recipient = recipientAddress(event, workspaceId);
        if (recipient.isEmpty()) {
            return Prepared.withhold(Outcome.NO_RECIPIENT);
        }

        String who = self
                ? "You"
                : affected.map(AccountDirectory.AccountView::displayName).orElse("An account");
        String where = workspace
                .map(view -> "the workspace “" + view.workspaceName() + "”")
                .orElse("this Hephaestus instance");
        String byWhom = actor.map(AccountDirectory.AccountView::displayName)
                .orElse(
                        actorId != null || change.actorAnonymized()
                                ? "An administrator"
                                : workspaceId == null
                                        ? "The instance’s bootstrap admin setting"
                                        : "The sync with GitHub or GitLab");
        String when =
                WHEN.withZone(accounts.timeZone(event.accountId()).orElse(UTC)).format(change.occurredAt());
        boolean reviews = workspaceId == null
                ? contacts.activeVerifiedAdministratorEmail(event.accountId()).isPresent()
                : memberships.isAdministrator(workspaceId, event.accountId());
        var model = new HashMap<String, Object>();
        model.put("summary", summary(who, self, where, change));
        model.put("attribution", byWhom + " made this change on " + when + ".");
        model.put(
                "advice",
                !self
                        ? "If you did not expect this change, review it and change the role back."
                        : workspaceId == null
                                ? "If you did not expect this change, contact another instance admin."
                                : "If you did not expect this change, contact an owner of the workspace.");
        // A person who left the workspace can no longer open it, so their link goes to the instance.
        boolean linksWorkspace = workspace.isPresent()
                && (reviews || memberships.isActiveVisibleMember(Objects.requireNonNull(workspaceId), event.accountId()));
        model.put(
                "reviewLabel",
                reviews
                        ? (workspaceId == null ? "Review the instance audit log" : "Review the workspace audit log")
                        : linksWorkspace ? "Open the workspace" : "Open Hephaestus");
        model.put("reviewPath", reviews ? "/admin/audit" : "");
        if (linksWorkspace) model.put("workspaceSlug", workspace.get().workspaceSlug());
        var rendered = renderer.render(EmailKind.ROLE_CHANGED, model);
        return Prepared.send(EmailMessage.of(EmailKind.ROLE_CHANGED, recipient.get(), rendered));
    }

    /**
     * The affected person hears of the change wherever their role now stands. Anyone else must still own the
     * workspace, or still be an instance admin, to hear of it.
     */
    private Optional<String> recipientAddress(RoleChangeEmailRequested event, @Nullable Long workspaceId) {
        long recipient = event.accountId();
        if (Objects.equals(recipient, event.change().accountId()))
            return contacts.activeVerifiedPrimaryEmail(recipient);
        if (workspaceId == null) return contacts.activeVerifiedAdministratorEmail(recipient);
        return memberships.ownerAccountIds(workspaceId).contains(recipient)
                ? contacts.activeVerifiedPrimaryEmail(recipient)
                : Optional.empty();
    }

    private Optional<AccountDirectory.AccountView> active(long accountId) {
        return accounts.find(accountId).filter(view -> !view.erased());
    }

    /** What happened, in the past tense: delivery can lag the change, and the role may have changed again since. */
    static String summary(String who, boolean self, String where, RoleChangedEvent change) {
        RoleChangedEvent.Role previous = change.previousRole();
        RoleChangedEvent.Role role = change.role();
        String whose = self ? "Your" : who + "’s";
        if (change.workspaceId() == null) {
            return role != null ? who + " became an instance admin." : whose + " instance admin role was removed.";
        }
        if (previous == null && role != null) return who + " became " + name(role) + " of " + where + ".";
        if (role == null && previous != null) {
            return who + " " + (self ? "were" : "was") + " removed from " + where + ", where "
                    + (self ? "you were " : "they were ") + name(previous) + ".";
        }
        return whose + " role in " + where + " changed from " + bare(previous) + " to " + bare(role) + ".";
    }

    private static String name(RoleChangedEvent.Role role) {
        return switch (role) {
            case OWNER -> "an owner";
            case ADMIN -> "an admin";
            case MEMBER -> "a member";
        };
    }

    private static String bare(RoleChangedEvent.@Nullable Role role) {
        return role == null ? "none" : role.name().toLowerCase(EmailRenderer.LOCALE);
    }
}
