package de.tum.cit.aet.hephaestus.core.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A committed change to an account's instance admin role or to its role in one workspace. It is published inside the
 * changing transaction and carries identifiers only: the {@code notification} module resolves names and addresses
 * when it writes the email, so an erased account is never named.
 *
 * @param changeId the stable identity of this change, retained when its publication is delivered again
 * @param accountId the account whose role changed
 * @param workspaceId the workspace whose role changed, or {@code null} for the instance admin role
 * @param previousRole the role before the change, or {@code null} when the account held none there
 * @param role the role after the change, or {@code null} when the account holds none there now
 * @param actorAccountId the account that made the change, or {@code null} when no person did: operator configuration
 *     for the instance admin role, the provider sync for a workspace role
 */
public record RoleChangedEvent(
        UUID changeId,
        @Nullable Long accountId,
        @Nullable Long workspaceId,
        @Nullable Role previousRole,
        @Nullable Role role,
        @Nullable Long actorAccountId,
        Instant occurredAt,
        boolean actorAnonymized) {

    /** Removes identity from a retained security notice while preserving its change and recipient keys. */
    public RoleChangedEvent anonymize(long erasedAccountId) {
        boolean erasedActor = Objects.equals(actorAccountId, erasedAccountId);
        return new RoleChangedEvent(
                changeId,
                Objects.equals(accountId, erasedAccountId) ? null : accountId,
                workspaceId,
                previousRole,
                role,
                erasedActor ? null : actorAccountId,
                occurredAt,
                actorAnonymized || erasedActor);
    }

    /**
     * A workspace role, or {@link #ADMIN} alone for the instance admin role. A stored publication reads it by name, so
     * a constant is never renamed in place.
     */
    public enum Role {
        MEMBER,
        ADMIN,
        OWNER
    }

    public static RoleChangedEvent instanceAdmin(
            long accountId, boolean wasAdmin, boolean isAdmin, @Nullable Long actorAccountId, Instant occurredAt) {
        return new RoleChangedEvent(
                UUID.randomUUID(),
                accountId,
                null,
                wasAdmin ? Role.ADMIN : null,
                isAdmin ? Role.ADMIN : null,
                actorAccountId,
                occurredAt,
                false);
    }
}
