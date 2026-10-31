package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.settings.spi.PublicActivityPolicy;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots;
import de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.exception.InsufficientWorkspacePermissionsException;
import de.tum.cit.aet.hephaestus.workspace.exception.LastOwnerRemovalException;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.core.PropertyPath;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class MembershipService {
    private final MembershipRepository memberships;
    private final WorkspaceRepository workspaces;
    private final ConfigAuditPort configAudit;
    private final WorkspaceAccessService access;
    private final ObjectProvider<PeopleRegistry> people;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final GitProviderRegistry providers;
    private final MembershipSearch memberSearch;
    private final WorkspaceActorSelector actors;
    private final PublicActivityPolicy publicActivityPolicy;

    public long accountIdForUser(long scmUserId) {
        var actor = providers
                .actor(scmUserId)
                .filter(GitProviderRegistry.ActorProfile::human)
                .orElseThrow(() -> new IllegalArgumentException("A human provider account is required."));
        return accountIdFor(actor);
    }

    private long accountIdFor(GitProviderRegistry.ActorProfile actor) {
        return people.getObject()
                .ensureHuman(
                        new PersonIdentity(actor.providerId(), actor.providerUserKey(), null), actor.displayName());
    }

    public int ensureMemberships(Workspace workspace, Set<Long> scmUserIds) {
        int inserted = 0;
        for (long scmUserId : scmUserIds) {
            var actor = providers.actor(scmUserId).filter(GitProviderRegistry.ActorProfile::human);
            if (actor.isEmpty()) continue;
            inserted += memberships.insertIfAbsent(
                    workspace.getId(), accountIdFor(actor.get()), Membership.WorkspaceRole.MEMBER.name(), false);
        }
        return inserted;
    }

    public void syncWorkspaceMembers(Workspace workspace, Map<Long, Membership.WorkspaceRole> providerRoles) {
        Map<Long, Membership.WorkspaceRole> desired = new HashMap<>();
        for (var entry : providerRoles.entrySet()) {
            var actor = providers.actor(entry.getKey()).filter(GitProviderRegistry.ActorProfile::human);
            if (actor.isEmpty()) continue;
            desired.merge(accountIdFor(actor.get()), entry.getValue(), Membership.WorkspaceRole::highest);
        }
        for (var entry : desired.entrySet()) {
            var existing = memberships.findByWorkspace_IdAndId_AccountId(workspace.getId(), entry.getKey());
            if (existing.isEmpty()) {
                var member = createMembership(workspace, entry.getKey(), entry.getValue());
                recordProviderGrant(member);
            } else {
                var member = existing.get();
                var before = snapshot(member);
                boolean rejoining = member.getLeftAt() != null;
                if (member.getRole() != Membership.WorkspaceRole.OWNER) member.setRole(entry.getValue());
                member.setLeftAt(null);
                if (rejoining) recordProviderGrant(member);
                else recordProviderRoleChange(member, before);
            }
        }
        for (var member : memberships.findByWorkspace_Id(workspace.getId())) {
            if (member.getRole() != Membership.WorkspaceRole.OWNER
                    && !desired.containsKey(member.getAccountId())
                    && member.getLeftAt() == null) {
                recordProviderDeparture(member);
                member.setLeftAt(clock.instant());
            }
        }
    }

    public void applyProviderGrant(Workspace workspace, Long scmUserId, Membership.@Nullable WorkspaceRole role) {
        long accountId = accountIdForUser(scmUserId);
        var existing = memberships.findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId);
        if (existing.isPresent() && existing.get().getRole() == Membership.WorkspaceRole.OWNER) return;
        if (role == null) {
            existing.filter(member -> member.getLeftAt() == null).ifPresent(member -> {
                recordProviderDeparture(member);
                member.setLeftAt(clock.instant());
            });
        } else if (existing.isPresent()) {
            var member = existing.get();
            var before = snapshot(member);
            boolean rejoining = member.getLeftAt() != null;
            member.setRole(role);
            member.setLeftAt(null);
            if (rejoining) recordProviderGrant(member);
            else recordProviderRoleChange(member, before);
        } else recordProviderGrant(createMembership(workspace, accountId, role));
    }

    private void recordProviderGrant(Membership member) {
        configAudit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.WORKSPACE_ROLE,
                Objects.requireNonNull(member.getAccountId()),
                member.getWorkspace().getId(),
                snapshot(member)));
    }

    private void recordProviderDeparture(Membership member) {
        configAudit.record(ConfigAuditEntry.deleted(
                ConfigAuditEntityType.WORKSPACE_ROLE,
                Objects.requireNonNull(member.getAccountId()),
                member.getWorkspace().getId(),
                snapshot(member)));
    }

    private void recordProviderRoleChange(Membership member, WorkspaceAuditSnapshots.RoleSnapshot before) {
        var after = snapshot(member);
        if (!before.equals(after))
            configAudit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.WORKSPACE_ROLE,
                    Objects.requireNonNull(member.getAccountId()),
                    member.getWorkspace().getId(),
                    before,
                    after));
    }

    public Membership createMembership(Workspace workspace, Long accountId, Membership.WorkspaceRole role) {
        var existing = memberships.findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId);
        if (existing.isPresent()) {
            var member = existing.get();
            if (member.getLeftAt() == null) throw new IllegalArgumentException("This account is already a member.");
            member.setLeftAt(null);
            member.setRole(role);
            return memberships.save(member);
        }
        var account = accounts.find(accountId);
        if (account.isEmpty()) throw new EntityNotFoundException("Account", accountId);
        var member = new Membership();
        member.setWorkspace(workspace);
        member.setId(new Membership.Id(workspace.getId(), accountId));
        member.setRole(role);
        return memberships.save(member);
    }

    public Membership assignRole(Long workspaceId, Long accountId, Membership.WorkspaceRole role) {
        var workspace = lockForMembershipChange(workspaceId);
        requireCanManageRole(workspace, role);
        var existing = memberships.findByWorkspace_IdAndId_AccountId(workspaceId, accountId);
        if (existing.isEmpty()) {
            var member = createMembership(workspace, accountId, role);
            configAudit.record(ConfigAuditEntry.created(
                    ConfigAuditEntityType.WORKSPACE_ROLE, accountId, workspaceId, snapshot(member)));
            return member;
        }
        var member = existing.get();
        requireCanManageRole(workspace, member.getRole());
        if (role != Membership.WorkspaceRole.OWNER) requireNotLastOwner(workspace, member);
        var before = snapshot(member);
        member.setRole(role);
        member.setLeftAt(null);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_ROLE, accountId, workspaceId, before, snapshot(member)));
        return memberships.save(member);
    }

    public void removeMembership(Long workspaceId, Long accountId) {
        var workspace = lockForMembershipChange(workspaceId);
        var member = requireMembership(workspaceId, accountId);
        requireCanManageRole(workspace, member.getRole());
        requireNotLastOwner(workspace, member);
        if (member.getLeftAt() != null) return;
        member.setLeftAt(clock.instant());
        configAudit.record(ConfigAuditEntry.deleted(
                ConfigAuditEntityType.WORKSPACE_ROLE, accountId, workspaceId, snapshot(member)));
    }

    public Membership updateMemberVisibility(Long workspaceId, Long accountId, boolean hidden) {
        var member = requireMembership(workspaceId, accountId);
        var before = snapshot(member);
        member.setHidden(hidden);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_ROLE, accountId, workspaceId, before, snapshot(member)));
        return memberships.save(member);
    }

    @Transactional(readOnly = true)
    public Membership getMembership(Long workspaceId, Long accountId) {
        return requireMembership(workspaceId, accountId);
    }

    private Membership requireMembership(Long workspaceId, Long accountId) {
        return memberships
                .findByWorkspace_IdAndId_AccountId(workspaceId, accountId)
                .orElseThrow(() -> new EntityNotFoundException("Membership", accountId));
    }

    @Transactional(readOnly = true)
    public Page<Membership> listMembers(
            long workspaceId, @Nullable String query, @Nullable Long teamId, Pageable pageable) {
        Sort sort = Sort.unsorted();
        for (var order : pageable.getSort()) {
            sort = sort.and(
                    switch (order.getProperty()) {
                        case "name" ->
                            JpaSort.unsafe(
                                    order.getDirection(),
                                    "LOWER(COALESCE(NULLIF(TRIM(a.displayName),''),NULLIF(TRIM(u.name),''),NULLIF(TRIM(u.login),''),'Unnamed account'))");
                        case "username" -> Sort.by(order.withProperty("u.login"));
                        default ->
                            Sort.by(order.withProperty(PropertyPath.from(order.getProperty(), Membership.class)
                                    .toDotPath()));
                    });
        }
        var page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by("id.accountId")));
        return memberSearch.findMembers(
                workspaceId,
                actors.connectedProviderId(workspaceId).orElse(null),
                query == null || query.isBlank() ? null : query.strip(),
                teamId,
                page);
    }

    /**
     * Whether each current member answered the public activity question here, by account, for a
     * workspace admin where the workspace publishes public activity. It never says which way: an
     * admin learns who is pending, not who hid. Anyone else reads nothing, and a former member is no
     * longer asked here.
     */
    @Transactional(readOnly = true)
    public Map<Long, Boolean> publicActivityAnswered(long workspaceId, Collection<Membership> members) {
        var current =
                members.stream().filter(member -> member.getLeftAt() == null).toList();
        if (current.isEmpty() || !access.isAdmin() || !publishesPublicActivity(workspaceId)) return Map.of();
        Map<Long, Boolean> answered = new HashMap<>();
        for (var member : current) {
            answered.put(Objects.requireNonNull(member.getAccountId()), member.isPublicActivitySeen());
        }
        return answered;
    }

    private boolean publishesPublicActivity(long workspaceId) {
        return publicActivityPolicy.allowed()
                && workspaces
                        .findById(workspaceId)
                        .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE
                                && Boolean.TRUE.equals(workspace.getPublicActivityEnabled()))
                        .isPresent();
    }

    private Workspace lockForMembershipChange(Long workspaceId) {
        return workspaces
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
    }

    private void requireCanManageRole(Workspace workspace, Membership.WorkspaceRole role) {
        var context = WorkspaceContextHolder.getContext();
        if (context == null || !workspace.getId().equals(context.id()) || !access.canManageRole(role))
            throw new InsufficientWorkspacePermissionsException(
                    workspace.getWorkspaceSlug(), "You cannot manage this role in this workspace.");
    }

    private void requireNotLastOwner(Workspace workspace, Membership member) {
        if (member.getLeftAt() == null
                && member.getRole() == Membership.WorkspaceRole.OWNER
                && memberships.countByWorkspace_IdAndRole(workspace.getId(), Membership.WorkspaceRole.OWNER) <= 1)
            throw new LastOwnerRemovalException(workspace.getWorkspaceSlug());
    }

    private static WorkspaceAuditSnapshots.RoleSnapshot snapshot(Membership member) {
        return new WorkspaceAuditSnapshots.RoleSnapshot(member.getRole().name(), member.isHidden());
    }
}
