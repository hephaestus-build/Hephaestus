package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.event.RoleChangedEvent;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Records membership changes in the configuration audit, and tells the people a role change affects. */
@Component
@RequiredArgsConstructor
class MembershipAudit {
    private final ConfigAuditPort configAudit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Records unchanged roles and the initial provider roster, without a role-change event. */
    void record(ConfigAuditEntry entry) {
        configAudit.record(entry);
    }

    /**
     * Records a change of a member's role: the {@code WORKSPACE_ROLE} audit row, and the event that tells the people
     * the change affects once it commits. Every role change goes through here, whoever or whatever made it.
     *
     * @param member the membership as it is after the change; a member who left holds no role
     * @param previousRole the role before the change, or {@code null} when the account was not a current member
     * @param actingAccountId the person who made the change, or {@code null} for the provider sync
     */
    void roleChanged(
            ConfigAuditEntry entry,
            Membership member,
            Membership.@Nullable WorkspaceRole previousRole,
            @Nullable Long actingAccountId) {
        configAudit.record(entry);
        var role = member.getLeftAt() == null ? member.getRole() : null;
        if (previousRole == role) return;
        events.publishEvent(new RoleChangedEvent(
                UUID.randomUUID(),
                Objects.requireNonNull(member.getAccountId()),
                member.getWorkspace().getId(),
                eventRole(previousRole),
                eventRole(role),
                actingAccountId,
                clock.instant(),
                false));
    }

    private static RoleChangedEvent.@Nullable Role eventRole(Membership.@Nullable WorkspaceRole role) {
        return role == null
                ? null
                : switch (role) {
                    case OWNER -> RoleChangedEvent.Role.OWNER;
                    case ADMIN -> RoleChangedEvent.Role.ADMIN;
                    case MEMBER -> RoleChangedEvent.Role.MEMBER;
                };
    }
}
