package de.tum.cit.aet.hephaestus.workspace;

import static de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceLifecycleViolationException;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamLabelFilterRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettingsRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamSettingsRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeGuard;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service coordinating workspace lifecycle state transitions and validation.
 * Manages suspend, resume, and purge operations with proper guardrails.
 */
@Service
public class WorkspaceLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceLifecycleService.class);

    private final ConfigAuditPort configAudit;
    private final NatsConnectionProperties natsProperties;
    private final WorkspaceRepository workspaceRepository;
    /** Absent under webhook profile (server.enabled=false). */
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;

    // Repositories for workspace-scoped data cleanup
    private final WorkspaceMembershipRepository workspaceMembershipRepository;
    private final WorkspaceTeamSettingsRepository workspaceTeamSettingsRepository;
    private final WorkspaceTeamLabelFilterRepository workspaceTeamLabelFilterRepository;
    private final WorkspaceTeamRepositorySettingsRepository workspaceTeamRepositorySettingsRepository;
    private final WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository;

    private final List<WorkspacePurgeGuard> purgeGuards;
    private final List<WorkspacePurgeContributor> purgeContributors;

    public WorkspaceLifecycleService(
            NatsConnectionProperties natsProperties,
            WorkspaceRepository workspaceRepository,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService,
            WorkspaceMembershipRepository workspaceMembershipRepository,
            WorkspaceTeamSettingsRepository workspaceTeamSettingsRepository,
            WorkspaceTeamLabelFilterRepository workspaceTeamLabelFilterRepository,
            WorkspaceTeamRepositorySettingsRepository workspaceTeamRepositorySettingsRepository,
            WorkspaceSlugHistoryRepository workspaceSlugHistoryRepository,
            List<WorkspacePurgeGuard> purgeGuards,
            List<WorkspacePurgeContributor> purgeContributors,
            ConfigAuditPort configAudit) {
        this.configAudit = configAudit;
        this.natsProperties = natsProperties;
        this.workspaceRepository = workspaceRepository;
        this.natsConsumerService = natsConsumerService;
        this.workspaceMembershipRepository = workspaceMembershipRepository;
        this.workspaceTeamSettingsRepository = workspaceTeamSettingsRepository;
        this.workspaceTeamLabelFilterRepository = workspaceTeamLabelFilterRepository;
        this.workspaceTeamRepositorySettingsRepository = workspaceTeamRepositorySettingsRepository;
        this.workspaceSlugHistoryRepository = workspaceSlugHistoryRepository;
        this.purgeGuards = purgeGuards;
        this.purgeContributors = purgeContributors;
    }

    /**
     * Suspend a workspace, preventing new sync cycles and making it read-only.
     * Idempotent: calling suspend on an already suspended workspace is a no-op.
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
                .findByWorkspaceSlug(workspaceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceSlug));

        if (workspace.getStatus() == WorkspaceStatus.PURGED) {
            throw new WorkspaceLifecycleViolationException(
                    "Workspace '" + workspaceSlug + "' is purged. You cannot suspend it.");
        }

        if (workspace.getStatus() != WorkspaceStatus.SUSPENDED) {
            WorkspaceStatus previous = workspace.getStatus();
            workspace.setStatus(WorkspaceStatus.SUSPENDED);
            workspace = workspaceRepository.save(workspace);
            recordStatusChange(workspace, previous, WorkspaceStatus.SUSPENDED);
            log.info("Suspended workspace: workspaceSlug={}", LoggingUtils.sanitizeForLog(workspaceSlug));
            stopNatsForWorkspace(workspace);
        }

        return workspace;
    }

    @Transactional
    public Workspace suspendWorkspace(WorkspaceContext workspaceContext) {
        return suspendWorkspaceInTransaction(requireSlug(workspaceContext));
    }

    /**
     * Resume a suspended workspace, making it active again.
     * Idempotent: calling resume on an already active workspace is a no-op.
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
                .findByWorkspaceSlug(workspaceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceSlug));

        if (workspace.getStatus() == WorkspaceStatus.PURGED) {
            throw new WorkspaceLifecycleViolationException(
                    "Workspace '" + workspaceSlug + "' is purged. You cannot resume it.");
        }

        if (workspace.getStatus() != WorkspaceStatus.ACTIVE) {
            WorkspaceStatus previous = workspace.getStatus();
            workspace.setStatus(WorkspaceStatus.ACTIVE);
            workspace = workspaceRepository.save(workspace);
            recordStatusChange(workspace, previous, WorkspaceStatus.ACTIVE);
            log.info("Resumed workspace: workspaceSlug={}", LoggingUtils.sanitizeForLog(workspaceSlug));
            startNatsForWorkspace(workspace);
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
            log.debug(
                    "Skipped workspace purge: reason=alreadyPurged, workspaceSlug={}",
                    LoggingUtils.sanitizeForLog(workspaceSlug));
            return workspace;
        }

        Long workspaceId = workspace.getId();
        String sanitizedSlug = LoggingUtils.sanitizeForLog(workspaceSlug);

        purgeGuards.forEach(guard -> guard.verifyQuiescent(workspaceId));

        stopNatsForWorkspacePurge(workspace);

        workspaceTeamLabelFilterRepository.deleteAllByWorkspaceId(workspaceId);
        workspaceTeamRepositorySettingsRepository.deleteAllByWorkspaceId(workspaceId);
        workspaceTeamSettingsRepository.deleteAllByWorkspaceId(workspaceId);

        workspaceMembershipRepository.deleteAllByWorkspaceId(workspaceId);

        purgeContributors.stream()
                .sorted(Comparator.comparingInt(WorkspacePurgeContributor::getOrder))
                .forEach(contributor -> contributor.deleteWorkspaceData(workspaceId));

        // Bulk deletion would leave these managed children queued for a second orphan-removal delete.
        workspace.getRepositoriesToMonitor().clear();

        workspaceSlugHistoryRepository.deleteAllByWorkspaceId(workspaceId);
        workspace.setOrganization(null);

        workspace.setUsersSyncedAt(null);
        workspace.setTeamsSyncedAt(null);
        workspace.setMembersSyncedAt(null);
        workspace.setSubIssuesSyncedAt(null);
        workspace.setIssueTypesSyncedAt(null);
        workspace.setIssueDependenciesSyncedAt(null);

        WorkspaceStatus previousStatus = workspace.getStatus();
        workspace.setStatus(WorkspaceStatus.PURGED);
        workspace = workspaceRepository.save(workspace);
        recordStatusChange(workspace, previousStatus, WorkspaceStatus.PURGED);

        log.info("Purged workspace: workspaceSlug={}, workspaceId={}", sanitizedSlug, workspaceId);
        return workspace;
    }

    @Transactional
    public Workspace purgeWorkspace(WorkspaceContext workspaceContext) {
        return purgeWorkspaceInTransaction(requireSlug(workspaceContext));
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
     * <p>{@code PURGED} is rejected here: purge is owner-only and irreversible, and routing it through
     * the admin-level status endpoint would bypass the owner requirement.
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
                        "You cannot purge a workspace with the status endpoint. Use DELETE /workspaces/{workspaceSlug}. This request needs the OWNER role.");
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

    // NATS consumer lifecycle helpers

    /**
     * Stop NATS consumer for a workspace.
     * Applies to all provider types (GitHub App, GitLab PAT, etc.) when NATS is enabled.
     */
    private void stopNatsForWorkspace(Workspace workspace) {
        if (shouldUseNats(workspace)) {
            natsConsumerService.ifAvailable(svc -> svc.stopConsumingScope(workspace.getId()));
        }
    }

    private void stopNatsForWorkspacePurge(Workspace workspace) {
        if (shouldUseNats(workspace)) {
            natsConsumerService.ifAvailable(svc -> svc.stopConsumingScopeForPurge(workspace.getId()));
        }
    }

    /**
     * Start NATS consumer for a workspace.
     * Applies to all provider types (GitHub App, GitLab PAT, etc.) when NATS is enabled.
     */
    private void startNatsForWorkspace(Workspace workspace) {
        if (shouldUseNats(workspace)) {
            natsConsumerService.ifAvailable(svc -> svc.startConsumingScope(workspace.getId()));
        }
    }

    /**
     * Checks if NATS should be used for the given workspace.
     * Must match the logic in {@code WorkspaceActivationService.shouldUseNats()}.
     */
    private boolean shouldUseNats(Workspace workspace) {
        return natsProperties.enabled() && workspace != null;
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
