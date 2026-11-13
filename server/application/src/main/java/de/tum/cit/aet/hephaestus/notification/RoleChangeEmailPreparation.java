package de.tum.cit.aet.hephaestus.notification;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.event.RoleChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.LinkedHashSet;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Decides who learns about a role change: the affected account and the other owners of its workspace, or for the
 * instance admin role the other instance admins. A person who grants themselves a higher role is told too.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic(
        "Each change names its workspace, or none for the instance admin role, and that decides the recipients")
public class RoleChangeEmailPreparation {
    private final AccountContactQuery contacts;
    private final AccountMembershipQuery memberships;
    private final ApplicationEventPublisher events;

    // Each recipient publication commits with the change. An async parent could duplicate the fan-out on retry.
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(RoleChangedEvent change) {
        if (!notifies(change)) return;
        var recipients = new LinkedHashSet<Long>();
        if (change.accountId() != null) recipients.add(change.accountId());
        Long workspaceId = change.workspaceId();
        recipients.addAll(
                workspaceId == null
                        ? contacts.activeVerifiedAdministratorIds()
                        : memberships.ownerAccountIds(workspaceId));
        Long actor = change.actorAccountId();
        if (actor != null && !Objects.equals(actor, change.accountId())) recipients.remove(actor);
        for (long accountId : recipients) events.publishEvent(new RoleChangeEmailRequested(change, accountId));
    }

    /**
     * Whether anyone hears of the change: it grants or removes ADMIN or OWNER, and someone other than the affected
     * person made it, or it raises their own role. A change only between MEMBER and no role, and a person lowering
     * their own role, stay in the audit log alone. The initial provider roster import publishes no new-member grant.
     */
    static boolean notifies(RoleChangedEvent change) {
        Long actor = change.actorAccountId();
        boolean selfChange = actor != null && Objects.equals(actor, change.accountId());
        return change.previousRole() != change.role()
                && (!selfChange || rank(change.role()) > rank(change.previousRole()))
                && (elevated(change.previousRole()) || elevated(change.role()));
    }

    private static int rank(RoleChangedEvent.@Nullable Role role) {
        return role == null
                ? -1
                : switch (role) {
                    case MEMBER -> 0;
                    case ADMIN -> 1;
                    case OWNER -> 2;
                };
    }

    private static boolean elevated(RoleChangedEvent.@Nullable Role role) {
        return role == RoleChangedEvent.Role.ADMIN || role == RoleChangedEvent.Role.OWNER;
    }
}
