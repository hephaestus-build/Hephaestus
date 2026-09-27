package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots;
import de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.exception.InsufficientWorkspacePermissionsException;
import de.tum.cit.aet.hephaestus.workspace.exception.LastOwnerRemovalException;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing workspace memberships.
 * <p>
 * Handles CRUD operations for workspace memberships, including:
 * <ul>
 * <li>Creating and removing memberships</li>
 * <li>Updating member roles</li>
 * <li>Syncing GitHub organization members with workspace memberships</li>
 * </ul>
 */
@Service
public class WorkspaceMembershipService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMembershipService.class);

    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final WorkspaceRepository workspaceRepository;

    private final EntityManager entityManager;

    private final ConfigAuditPort configAudit;
    private final WorkspaceAccessService accessService;

    public WorkspaceMembershipService(
            WorkspaceMembershipRepository workspaceMembershipRepository,
            WorkspaceRepository workspaceRepository,
            EntityManager entityManager,
            ConfigAuditPort configAudit,
            WorkspaceAccessService accessService) {
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.workspaceRepository = workspaceRepository;
        this.entityManager = entityManager;
        this.configAudit = configAudit;
        this.accessService = accessService;
    }

    @Transactional(readOnly = true)
    public Optional<User> findMemberByLogin(Long workspaceId, String login) {
        // A login is not unique across providers; the request's own actor is the one it means.
        Optional<Long> actorId = CurrentScmIdentityHolder.getLogin()
                .filter(login::equalsIgnoreCase)
                .flatMap(actorLogin -> CurrentScmIdentityHolder.getUserId());
        if (actorId.isPresent()) {
            return workspaceMembershipRepository
                    .findByWorkspace_IdAndUser_Id(workspaceId, actorId.get())
                    .map(WorkspaceMembership::getUser);
        }
        return workspaceMembershipRepository
                .findFirstByWorkspace_IdAndUser_LoginIgnoreCaseOrderByUser_Id(workspaceId, login)
                .map(WorkspaceMembership::getUser);
    }

    /**
     * Human members of the workspace, team memberships fetched — the roster of workspace activity. Scoped by
     * {@code workspace_id}, not by the org-login string, so it cannot leak members between workspaces that share
     * an {@code account_login}. Empty for a null id.
     */
    @Transactional(readOnly = true)
    public List<User> getHumanMembersWithTeams(Long workspaceId) {
        if (workspaceId == null) {
            return List.of();
        }
        return workspaceMembershipRepository.findHumanUsersWithTeamsByWorkspaceId(workspaceId);
    }

    /**
     * Additively ensures that every given user has a {@link WorkspaceMembership} in
     * the workspace, without touching existing members or roles.
     * <p>
     * Uses the race-safe native upsert in
     * {@link WorkspaceMembershipRepository#insertIfAbsent}: if a membership already
     * exists for the user, it is left untouched (role and hidden flag preserved). Only missing
     * memberships are created, with role {@code MEMBER}.
     * <p>
     * Intended for reconciliation paths that discover users via the team graph or
     * other side channels and must never downgrade existing OWNER/ADMIN roles.
     *
     * @param workspace the workspace to ensure memberships in
     * @param userIds   the set of user IDs that should have a membership
     * @return the number of users processed (0 if workspace or userIds are null/empty)
     */
    @Transactional
    public int ensureMemberships(Workspace workspace, Set<Long> userIds) {
        if (workspace == null || workspace.getId() == null) {
            return 0;
        }
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }

        Long workspaceId = workspace.getId();
        int inserted = 0;
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            inserted += workspaceMembershipRepository.insertIfAbsent(
                    workspaceId, userId, WorkspaceMembership.WorkspaceRole.MEMBER.name());
        }

        if (inserted > 0) {
            log.info(
                    "Ensured workspace memberships from team graph: workspaceId={}, considered={}, created={}",
                    workspaceId,
                    userIds.size(),
                    inserted);
        }
        return inserted;
    }

    @Transactional
    public void syncWorkspaceMembers(Workspace workspace, Map<Long, WorkspaceMembership.WorkspaceRole> desiredRoles) {
        if (workspace == null || workspace.getId() == null) {
            return;
        }

        Map<Long, WorkspaceMembership.WorkspaceRole> normalizedRoles =
                desiredRoles == null ? Collections.<Long, WorkspaceMembership.WorkspaceRole>emptyMap() : desiredRoles;

        List<WorkspaceMembership> existingMembers = workspaceMembershipRepository.findByWorkspace_Id(workspace.getId());
        Map<Long, WorkspaceMembership> existingByUserId = existingMembers.stream()
                .filter(member -> member.getUser() != null && member.getUser().getId() != null)
                .collect(Collectors.toMap(member -> member.getUser().getId(), Function.identity()));

        Set<Long> desiredUserIds = new HashSet<>(normalizedRoles.keySet());

        List<WorkspaceMembership> toCreate = new ArrayList<>();
        List<WorkspaceMembership> toUpdate = new ArrayList<>();
        List<WorkspaceMembership> toDelete = new ArrayList<>();

        for (Map.Entry<Long, WorkspaceMembership.WorkspaceRole> entry : normalizedRoles.entrySet()) {
            Long userId = entry.getKey();
            if (userId == null) {
                continue;
            }
            WorkspaceMembership.WorkspaceRole desiredRole =
                    Optional.ofNullable(entry.getValue()).orElse(WorkspaceMembership.WorkspaceRole.MEMBER);
            WorkspaceMembership existing = existingByUserId.get(userId);
            if (existing == null) {
                // Use find() instead of getReference() to avoid lazy EntityNotFoundException
                User user = entityManager.find(User.class, userId);
                if (user == null) {
                    log.warn(
                            "Skipped workspace membership creation: reason=userNotFound, userId={}, workspaceId={}",
                            userId,
                            workspace.getId());
                    continue;
                }
                WorkspaceMembership member = createMembershipInternal(workspace, user, desiredRole);
                toCreate.add(member);
            } else if (existing.getRole() != desiredRole) {
                var beforeSync = new WorkspaceAuditSnapshots.RoleSnapshot(
                        existing.getRole() == null ? null : existing.getRole().name(), existing.isHidden());
                existing.setRole(desiredRole);
                toUpdate.add(existing);
                // Recorded with a SYSTEM actor: a role can change without an admin ever touching this
                // instance, and "when did X become ADMIN" must not answer confidently from the
                // admin-initiated rows alone. Creates and deletions are deliberately not recorded —
                // see ConfigAuditEntityType.WORKSPACE_ROLE for that boundary.
                configAudit.record(ConfigAuditEntry.updated(
                        ConfigAuditEntityType.WORKSPACE_ROLE,
                        userId,
                        workspace.getId(),
                        beforeSync,
                        new WorkspaceAuditSnapshots.RoleSnapshot(desiredRole.name(), existing.isHidden())));
            }
        }

        for (WorkspaceMembership member : existingMembers) {
            Long memberUserId = member.getUser() != null ? member.getUser().getId() : null;
            if (memberUserId == null || desiredUserIds.contains(memberUserId)) {
                continue;
            }
            // Preserve memberships an admin has explicitly hidden from workspace activity.
            // `hidden=true` is a sticky, admin-authored signal that must survive org-sync
            // churn (transient API gaps, webhook reorder, remove-then-re-add). Deleting
            // the row would lose that signal on re-creation and silently un-hide the user.
            if (member.isHidden()) {
                log.debug(
                        "Preserved hidden workspace membership during sync: workspaceId={}, userId={}",
                        workspace.getId(),
                        memberUserId);
                continue;
            }
            toDelete.add(member);
        }

        if (!toCreate.isEmpty()) {
            workspaceMembershipRepository.saveAll(toCreate);
        }
        if (!toUpdate.isEmpty()) {
            workspaceMembershipRepository.saveAll(toUpdate);
        }
        if (!toDelete.isEmpty()) {
            workspaceMembershipRepository.deleteAll(toDelete);
        }
    }

    @Transactional
    public WorkspaceMembership createMembership(
            Workspace workspace, Long userId, WorkspaceMembership.WorkspaceRole role) {
        if (workspace == null || workspace.getId() == null) {
            throw new IllegalArgumentException("Workspace must not be null and must have an ID");
        }
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }

        User userReference = entityManager.find(User.class, userId);
        if (userReference == null) {
            throw new IllegalArgumentException("User not found with ID: " + userId);
        }

        // Check if membership already exists
        Optional<WorkspaceMembership> existing =
                workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspace.getId(), userId);
        if (existing.isPresent()) {
            throw new IllegalArgumentException(
                    "Membership already exists for workspace " + workspace.getId() + " and user " + userId);
        }

        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setWorkspace(workspace);
        membership.setUser(userReference);
        membership.setRole(role);
        membership.setId(new WorkspaceMembership.Id(workspace.getId(), userId));

        return workspaceMembershipRepository.save(membership);
    }

    @Transactional
    public WorkspaceMembership assignRole(Long workspaceId, Long userId, WorkspaceMembership.WorkspaceRole role) {
        Workspace workspace = lockForMembershipChange(workspaceId);
        requireCanManageRole(workspace, role);

        var membershipOpt = workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspaceId, userId);

        if (membershipOpt.isPresent()) {
            WorkspaceMembership membership = membershipOpt.get();
            requireCanManageRole(workspace, membership.getRole());
            if (role != WorkspaceMembership.WorkspaceRole.OWNER) {
                requireNotLastOwner(workspace, membership);
            }
            var beforeRole = new WorkspaceAuditSnapshots.RoleSnapshot(
                    membership.getRole() == null ? null : membership.getRole().name(), membership.isHidden());
            membership.setRole(role);
            configAudit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.WORKSPACE_ROLE,
                    userId,
                    workspaceId,
                    beforeRole,
                    new WorkspaceAuditSnapshots.RoleSnapshot(role.name(), membership.isHidden())));
            log.info("Updated membership role: userId={}, workspaceId={}, role={}", userId, workspaceId, role);
            return workspaceMembershipRepository.save(membership);
        } else {
            User user = entityManager.find(User.class, userId);
            if (user == null) {
                throw new IllegalArgumentException("User not found with ID: " + userId);
            }

            WorkspaceMembership membership = createMembershipInternal(workspace, user, role);
            configAudit.record(ConfigAuditEntry.created(
                    ConfigAuditEntityType.WORKSPACE_ROLE,
                    userId,
                    workspaceId,
                    new WorkspaceAuditSnapshots.RoleSnapshot(role.name(), membership.isHidden())));
            log.info("Created membership: userId={}, workspaceId={}, role={}", userId, workspaceId, role);
            return workspaceMembershipRepository.save(membership);
        }
    }

    @Transactional
    public void removeMembership(Long workspaceId, Long userId) {
        Workspace workspace = lockForMembershipChange(workspaceId);
        var membership = workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(workspaceId, userId)
                .orElseThrow(() -> new EntityNotFoundException("WorkspaceMembership", userId));
        requireCanManageRole(workspace, membership.getRole());
        requireNotLastOwner(workspace, membership);

        var beforeRole = new WorkspaceAuditSnapshots.RoleSnapshot(
                membership.getRole() == null ? null : membership.getRole().name(), membership.isHidden());
        workspaceMembershipRepository.delete(membership);
        configAudit.record(
                ConfigAuditEntry.deleted(ConfigAuditEntityType.WORKSPACE_ROLE, userId, workspaceId, beforeRole));
        log.info("Removed membership: userId={}, workspaceId={}", userId, workspaceId);
    }

    private WorkspaceMembership createMembershipInternal(
            Workspace workspace, User user, WorkspaceMembership.WorkspaceRole role) {
        WorkspaceMembership member = new WorkspaceMembership();
        member.setWorkspace(workspace);
        member.setUser(user);
        member.setRole(role);
        member.setId(new WorkspaceMembership.Id(workspace.getId(), user.getId()));
        return member;
    }

    // Hidden member methods

    /**
     * Toggle the hidden flag for a workspace member.
     *
     * @param workspaceId Workspace ID
     * @param userId      User ID
     * @param hidden      whether the member should be hidden
     * @return Updated membership
     */
    @Transactional
    public WorkspaceMembership updateMemberVisibility(Long workspaceId, Long userId, boolean hidden) {
        WorkspaceMembership membership = workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(workspaceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace membership not found"));
        var before = new WorkspaceAuditSnapshots.RoleSnapshot(
                membership.getRole() == null ? null : membership.getRole().name(), membership.isHidden());
        membership.setHidden(hidden);
        // Not cosmetic: syncWorkspaceMembers deliberately preserves hidden memberships, so this flag
        // decides whether a member keeps workspace access after leaving the upstream org.
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_ROLE,
                userId,
                workspaceId,
                before,
                new WorkspaceAuditSnapshots.RoleSnapshot(
                        membership.getRole() == null
                                ? null
                                : membership.getRole().name(),
                        hidden)));
        return workspaceMembershipRepository.save(membership);
    }

    /**
     * Returns the set of user IDs that are hidden in a workspace.
     *
     * @param workspaceId Workspace ID
     * @return Set of hidden user IDs
     */
    @Transactional(readOnly = true)
    public Set<Long> getHiddenMemberIds(Long workspaceId) {
        return workspaceMembershipRepository.findHiddenUserIdsByWorkspaceId(workspaceId);
    }

    // Query methods for controller

    /**
     * Gets a workspace membership by workspace and user ID.
     *
     * @param workspaceId Workspace ID
     * @param userId User ID
     * @return The membership
     * @throws IllegalArgumentException if membership not found
     */
    @Transactional(readOnly = true)
    public WorkspaceMembership getMembership(Long workspaceId, Long userId) {
        return workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(workspaceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace membership not found"));
    }

    /**
     * Gets a workspace membership by workspace and user ID, or empty if not found.
     *
     * @param workspaceId Workspace ID
     * @param userId User ID
     * @return Optional containing the membership if found
     */
    @Transactional(readOnly = true)
    public Optional<WorkspaceMembership> findMembership(Long workspaceId, Long userId) {
        return workspaceMembershipRepository.findByWorkspace_IdAndUser_Id(workspaceId, userId);
    }

    /**
     * Lists all members of a workspace with pagination.
     *
     * @param workspaceId Workspace ID
     * @param pageable Pagination parameters
     * @return Page of workspace memberships
     */
    @Transactional(readOnly = true)
    public Page<WorkspaceMembership> listMembers(Long workspaceId, Pageable pageable) {
        return workspaceMembershipRepository.findAllByWorkspace_Id(workspaceId, pageable);
    }

    private Workspace lockForMembershipChange(Long workspaceId) {
        // Serialize manual ownership changes so competing requests cannot remove the final owners.
        return workspaceRepository
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
    }

    private void requireCanManageRole(Workspace workspace, WorkspaceMembership.WorkspaceRole role) {
        var context = WorkspaceContextHolder.getContext();
        if (context == null || !workspace.getId().equals(context.id()) || !accessService.canManageRole(role)) {
            throw new InsufficientWorkspacePermissionsException(
                    workspace.getWorkspaceSlug(), "You cannot manage the " + role + " role");
        }
    }

    private void requireNotLastOwner(Workspace workspace, WorkspaceMembership membership) {
        if (membership.getRole() == WorkspaceMembership.WorkspaceRole.OWNER
                && workspaceMembershipRepository.countByWorkspace_IdAndRole(
                                workspace.getId(), WorkspaceMembership.WorkspaceRole.OWNER)
                        <= 1) {
            throw new LastOwnerRemovalException(workspace.getWorkspaceSlug());
        }
    }
}
