package de.tum.cit.aet.hephaestus.workspace;

import static de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RowSweepAdmission;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceLifecycleViolationException;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProviderRemoval;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeGuard;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceResumeGuard;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkspaceLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceLifecycleService.class);

    private final ConfigAuditPort configAudit;
    private final WorkspaceRepository workspaceRepository;

    /** Absent under webhook profile (server.enabled=false). */

    // Repositories for workspace-scoped data cleanup
    private final MembershipRepository workspaceMembershipRepository;

    private final WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository;

    private final WorkspaceSlugService slugs;

    private final List<WorkspacePurgeGuard> purgeGuards;
    private final List<WorkspacePurgeContributor> purgeContributors;
    private final List<WorkspaceResumeGuard> resumeGuards;

    /** Empty in a role without provider removal. */
    private final List<WorkspaceProviderRemoval> providerRemovals;

    private final TransactionTemplate transactions;

    private final PrivacyJournal journal;
    private final RowSweepAdmission sweepAdmission;

    public WorkspaceLifecycleService(
            WorkspaceRepository workspaceRepository,
            MembershipRepository workspaceMembershipRepository,
            WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository,
            WorkspaceSlugService slugs,
            List<WorkspacePurgeGuard> purgeGuards,
            List<WorkspacePurgeContributor> purgeContributors,
            List<WorkspaceResumeGuard> resumeGuards,
            List<WorkspaceProviderRemoval> providerRemovals,
            PlatformTransactionManager transactionManager,
            ConfigAuditPort configAudit,
            PrivacyJournal journal,
            RowSweepAdmission sweepAdmission) {
        this.sweepAdmission = sweepAdmission;
        this.journal = journal;
        this.configAudit = configAudit;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.workspaceSlugHistoryRepository = workspaceSlugHistoryRepository;
        this.slugs = slugs;
        this.purgeGuards = purgeGuards;
        this.purgeContributors = purgeContributors;
        this.resumeGuards = resumeGuards;
        this.providerRemovals = providerRemovals;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Suspend a workspace, preventing new sync cycles and making it read-only. Idempotent: calling
     * suspend on an already suspended workspace is a no-op.
     *
     * @param workspaceSlug the workspace slug
     * @return the suspended workspace
     * @throws EntityNotFoundException if workspace does not exist
     * @throws WorkspaceLifecycleViolationException if workspace is already purged
     */
    @Transactional
    public Workspace suspendWorkspace(String workspaceSlug) {
        return suspendWorkspaceInTransaction(workspaceSlug);
    }

    private Workspace suspendWorkspaceInTransaction(String workspaceSlug) {
        Workspace workspace = workspaceRepository
                .findByWorkspaceSlugForUpdate(workspaceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceSlug));

        WorkspaceStatus previous = workspace.getStatus();
        if (workspace.suspend()) {
            workspace = workspaceRepository.save(workspace);
            recordStatusChange(workspace, previous, WorkspaceStatus.SUSPENDED);
            log.info("Suspended workspace: workspaceId={}", workspace.getId());
        }

        return workspace;
    }

    @Transactional
    public Workspace suspendWorkspace(WorkspaceContext workspaceContext) {
        return suspendWorkspaceInTransaction(requireSlug(workspaceContext));
    }

    /**
     * Resume a suspended workspace, making it active again. Idempotent: calling resume on an
     * already active workspace is a no-op.
     *
     * @param workspaceSlug the workspace slug
     * @return the resumed workspace
     * @throws EntityNotFoundException if workspace does not exist
     * @throws WorkspaceLifecycleViolationException if workspace is purged (cannot resume purged)
     */
    @Transactional
    public Workspace resumeWorkspace(String workspaceSlug) {
        return resumeWorkspaceInTransaction(workspaceSlug);
    }

    private Workspace resumeWorkspaceInTransaction(String workspaceSlug) {
        Workspace workspace = workspaceRepository
                .findByWorkspaceSlugForUpdate(workspaceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceSlug));

        WorkspaceStatus previous = workspace.getStatus();
        if (previous == WorkspaceStatus.SUSPENDED) {
            long workspaceId = workspace.getId();
            resumeGuards.forEach(guard -> guard.verifyResumable(workspaceId));
        }
        if (workspace.resume()) {
            workspace = workspaceRepository.save(workspace);
            recordStatusChange(workspace, previous, WorkspaceStatus.ACTIVE);
            log.info("Resumed workspace: workspaceId={}", workspace.getId());
        }

        return workspace;
    }

    @Transactional
    public Workspace resumeWorkspace(WorkspaceContext workspaceContext) {
        return resumeWorkspaceInTransaction(requireSlug(workspaceContext));
    }

    /** Purges an idle workspace. Repeated calls after a successful purge are no-ops. */
    @Transactional
    public Workspace purgeWorkspace(String workspaceSlug) {
        return purgeWorkspaceInTransaction(workspaceSlug);
    }

    private Workspace purgeWorkspaceInTransaction(String workspaceSlug) {
        Workspace workspace = workspaceRepository
                .findByWorkspaceSlugForUpdate(workspaceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceSlug));

        if (workspace.getStatus() == WorkspaceStatus.PURGED) {
            var target = Map.<String, Object>of("workspaceId", workspace.getId());
            journal.confirmPending(PrivacyJournalKind.CONNECTION_ERASED, target);
            journal.confirmPending(PrivacyJournalKind.WORKSPACE_PURGED, target);
            log.debug("Skipped workspace purge: reason=alreadyPurged, workspaceId={}", workspace.getId());
            return workspace;
        }

        Long workspaceId = workspace.getId();
        purgeGuards.forEach(guard -> guard.verifyQuiescent(workspaceId));
        return purge(workspace);
    }

    /**
     * Purges again a workspace that a database restore brought back. The quiescence guards protect against live
     * writers; erasure recovery runs with background work off and before the server takes requests, so no writer runs,
     * and the restored in-flight rows that the guards would wait for are deleted with the rest.
     *
     * @return whether it purged: false when the restore has no such workspace or it is purged already
     */
    @Transactional
    public boolean purgeAgain(long workspaceId) {
        var workspace = workspaceRepository.findByIdForUpdate(workspaceId).orElse(null);
        if (workspace == null || workspace.getStatus() == WorkspaceStatus.PURGED) return false;
        purge(workspace);
        return true;
    }

    private Workspace purge(Workspace workspace) {
        Long workspaceId = workspace.getId();
        deleteWorkspaceData(workspaceId);

        // Bulk deletion would leave these managed children queued for a second orphan-removal
        // delete.
        workspace.getRepositoriesToMonitor().clear();

        workspaceSlugHistoryRepository.deleteAllByWorkspaceId(workspaceId);
        workspace.setOrganizationId(null);

        workspace.setUsersSyncedAt(null);
        workspace.setTeamsSyncedAt(null);
        workspace.setMembersSyncedAt(null);
        workspace.setSubIssuesSyncedAt(null);
        workspace.setIssueTypesSyncedAt(null);
        workspace.setIssueDependenciesSyncedAt(null);

        WorkspaceStatus previousStatus = workspace.getStatus();
        workspace.markPurged();
        Workspace purged = workspaceRepository.save(workspace);
        recordStatusChange(purged, previousStatus, WorkspaceStatus.PURGED);
        journal.record(PrivacyJournalKind.WORKSPACE_PURGED, Map.of("workspaceId", workspaceId));

        // An erasure recovery manifest lists the purges after its recovery point (backup-restore.mdx), so only a
        // committed purge is recorded.
        TransactionCallbacks.afterCommit(() -> log.atInfo()
                .addKeyValue("event.name", "workspace.purged")
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, workspaceId)
                .log("Purged workspace"));
        return purged;
    }

    /**
     * Ends the account's personal space, in the transaction of the account's erasure. The cleanup owners of a workspace
     * purge remove what lives in the space, with the space's configuration trail, then the space itself goes. Its address
     * stays reserved only as a keyed token, so the database keeps no plaintext of it, and a new personal space never
     * draws it ({@link WorkspaceSlugService#isReserved}).
     *
     * <p>No purge guard runs: the erasure has already closed admission, as the account is {@code DELETING} and its
     * tokens are revoked. The row lock makes a writer that still names the space wait, then fail on its absence. The
     * conversation copy store deletes the space's physical sandboxes before this step and keeps custody of a failed
     * deletion; a sandbox row that is still here goes with the purge, and the reaper deletes its claim. A repeated call
     * finds no space and does nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void erasePersonalSpace(long accountId) {
        var space = workspaceRepository.lockPersonalSpace(accountId).orElse(null);
        if (space == null) return;
        // Suspended here too, for a path that skipped suspendPersonalSpaceForErasure, such as a restore's recovery.
        workspaceRepository.suspendPersonalSpace(space.getId());
        deleteWorkspaceData(space.getId());
        // Its trail would hold the space's row: config_audit_event's workspace key has no delete action.
        configAudit.erasePersonalSpaceTrail(space.getId());
        workspaceRepository.deletePersonalSpace(space.getId());
        slugs.retire(space.getSlug());
    }

    /**
     * Closes a personal space before its erasure removes anything (ADR 0126): the space is {@code SUSPENDED}, so no
     * request enters it and no queued run is claimed, and no new sandbox starts between the deletion of the space's
     * sandboxes and {@link #erasePersonalSpace}. It writes no status-change audit row: the teardown deletes the space's
     * trail, and a row written now would only be deleted with it.
     * A repeated call changes nothing.
     */
    @Transactional
    public void suspendPersonalSpaceForErasure(long workspaceId) {
        workspaceRepository.suspendPersonalSpace(workspaceId);
    }

    /** Runs every cleanup owner in its order, then removes the memberships, which the owners' rows may reference. */
    private void deleteWorkspaceData(long workspaceId) {
        sweepAdmission.admitWorkspacePurge(workspaceId);
        purgeContributors.stream()
                .sorted(Comparator.comparingInt(WorkspacePurgeContributor::getOrder))
                .forEach(contributor -> contributor.deleteWorkspaceData(workspaceId));
        workspaceMembershipRepository.deleteAllByWorkspaceId(workspaceId);
    }

    /** The owner's workspace removal: {@link #removeAtProvidersBeforePurge(long, Runnable)}, then the purge. */
    public Workspace purgeWorkspace(WorkspaceContext workspaceContext) {
        String slug = requireSlug(workspaceContext);
        long workspaceId = Objects.requireNonNull(transactions.execute(status -> workspaceRepository
                .findByWorkspaceSlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", slug))
                .getId()));
        removeAtProvidersBeforePurge(workspaceId, () -> {});
        return Objects.requireNonNull(transactions.execute(status -> purgeWorkspaceInTransaction(slug)));
    }

    /**
     * The provider half of a workspace removal. Provider calls cannot run inside the purge's transaction, and the purge
     * erases the credentials they need, so a caller runs this first, with no transaction open: the connections
     * disconnect and send their provider removals, each in its own transaction. A purge without it, from inside a
     * caller's transaction ({@link #purgeWorkspace(String)}), leaves each removal recorded for authorization instead.
     * It does nothing for a purged workspace.
     *
     * @param afterGuards runs once the purge guards admit the removal and before the provider removals, such as the
     *     erasure that a provider's own uninstall records; a refusing guard leaves it unrun
     */
    public void removeAtProvidersBeforePurge(long workspaceId, Runnable afterGuards) {
        boolean pending = Boolean.TRUE.equals(transactions.execute(status -> workspaceRepository
                .findById(workspaceId)
                .filter(workspace -> workspace.getStatus() != WorkspaceStatus.PURGED)
                .map(workspace -> {
                    purgeGuards.forEach(guard -> guard.verifyQuiescent(workspaceId));
                    return true;
                })
                .orElse(false)));
        if (!pending) return;
        afterGuards.run();
        providerRemovals.forEach(removal -> removal.removeBeforePurge(workspaceId));
    }

    /**
     * Get the current status of a workspace.
     *
     * @param workspaceSlug the workspace slug
     * @return the workspace status
     * @throws EntityNotFoundException if workspace does not exist
     */
    public WorkspaceStatus getWorkspaceStatus(String workspaceSlug) {
        Workspace workspace = workspaceRepository
                .findByWorkspaceSlug(workspaceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceSlug));
        return workspace.getStatus();
    }

    public WorkspaceStatus getWorkspaceStatus(WorkspaceContext workspaceContext) {
        return getWorkspaceStatus(requireSlug(workspaceContext));
    }

    /**
     * Update the lifecycle status for the workspace.
     *
     * <p>{@code PURGED} is rejected here: purge is owner-only and irreversible, and routing it
     * through the admin-level status endpoint would bypass the owner requirement.
     */
    @Transactional
    public Workspace updateStatus(String workspaceSlug, WorkspaceStatus targetStatus) {
        return updateStatusInTransaction(workspaceSlug, targetStatus);
    }

    private Workspace updateStatusInTransaction(String workspaceSlug, WorkspaceStatus targetStatus) {
        return switch (targetStatus) {
            case ACTIVE -> resumeWorkspaceInTransaction(workspaceSlug);
            case SUSPENDED -> suspendWorkspaceInTransaction(workspaceSlug);
            case PURGED ->
                throw new WorkspaceLifecycleViolationException(
                        "You cannot purge a workspace with the status endpoint. Use DELETE"
                                + " /workspaces/{workspaceSlug}. This request needs the OWNER"
                                + " role.");
        };
    }

    @Transactional
    public Workspace updateStatus(WorkspaceContext workspaceContext, WorkspaceStatus targetStatus) {
        return updateStatusInTransaction(requireSlug(workspaceContext), targetStatus);
    }

    private String requireSlug(WorkspaceContext workspaceContext) {
        if (workspaceContext == null) {
            throw new EntityNotFoundException("Workspace", "context");
        }

        String slug = workspaceContext.slug();
        if (slug == null || slug.isBlank()) {
            throw new EntityNotFoundException("Workspace", "context");
        }

        return slug;
    }

    private void recordStatusChange(Workspace workspace, WorkspaceStatus from, WorkspaceStatus to) {
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_STATUS,
                workspace.getId(),
                workspace.getId(),
                new WorkspaceAuditSnapshots.StatusSnapshot(from == null ? null : from.name()),
                new WorkspaceAuditSnapshots.StatusSnapshot(to == null ? null : to.name())));
    }
}
