package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceFeaturesRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.events.WorkspaceCreatedEvent;
import de.tum.cit.aet.hephaestus.workspace.exception.*;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamSettingsService;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Central service for workspace management operations.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li><b>Workspace creation:</b> Creates workspaces with owner membership</li>
 *   <li><b>Slug management:</b> Renames with redirect history via {@link WorkspaceSlugService}</li>
 *   <li><b>Settings delegation:</b> Forwards to {@link WorkspaceSettingsService}</li>
 *   <li><b>League points:</b> Triggers recalculation via {@link LeaguePointsRecalculator}</li>
 * </ul>
 *
 * <h2>Related Services</h2>
 * For other workspace operations, use the specialized services:
 * <ul>
 *   <li>{@link WorkspaceQueryService} – Read-only queries and lookups</li>
 *   <li>{@link WorkspaceLifecycleService} – Status transitions (suspend/resume/purge)</li>
 *   <li>{@link de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GithubLifecycleListener} – GitHub App installation handling</li>
 *   <li>{@link WorkspaceRepositoryMonitorService} – Repository monitoring configuration</li>
 *   <li>{@link WorkspaceActivationService} – Activation/startup orchestration</li>
 *   <li>{@link WorkspaceTeamSettingsService} – Workspace-scoped team settings</li>
 *   <li>{@link WorkspaceTeamLabelService} – Team/label associations</li>
 * </ul>
 *
 * <h2>Multi-Tenancy Note</h2>
 * This service is workspace-agnostic: it manages workspaces themselves (the tenant root),
 * not data within workspaces. Methods take workspace slug or {@link WorkspaceContext} as parameters.
 *
 * @see Workspace
 * @see WorkspaceContext
 * @see WorkspaceTeamSettingsService
 */
@Service
@WorkspaceAgnostic("Manages workspaces themselves - the tenant root, not data within workspaces")
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private static final boolean DEFAULT_PUBLIC_VISIBILITY = false;

    // Core repositories
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final CurrentAccountUsers currentAccountUsers;

    // Services
    private final WorkspaceSlugService workspaceSlugService;
    private final WorkspaceSettingsService workspaceSettingsService;
    private final LeaguePointsRecalculator leaguePointsRecalculator;
    private final WorkspaceMembershipService workspaceMembershipService;
    private final ConnectionService connectionService;

    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            UserRepository userRepository,
            CurrentAccountUsers currentAccountUsers,
            WorkspaceSlugService workspaceSlugService,
            WorkspaceSettingsService workspaceSettingsService,
            LeaguePointsRecalculator leaguePointsRecalculator,
            WorkspaceMembershipService workspaceMembershipService,
            ConnectionService connectionService,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.currentAccountUsers = currentAccountUsers;
        this.workspaceSlugService = workspaceSlugService;
        this.workspaceSettingsService = workspaceSettingsService;
        this.leaguePointsRecalculator = leaguePointsRecalculator;
        this.workspaceMembershipService = workspaceMembershipService;
        this.connectionService = connectionService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // Workspace Lookup

    public Optional<Workspace> getWorkspaceBySlug(String slug) {
        return workspaceRepository.findByWorkspaceSlug(slug);
    }

    // Workspace Creation

    @Transactional
    public Workspace createWorkspace(
            String rawSlug,
            String displayName,
            String accountLogin,
            AccountType accountType,
            @Nullable Long ownerUserId) {
        return createWorkspaceInTransaction(rawSlug, displayName, accountLogin, accountType, ownerUserId);
    }

    private Workspace createWorkspaceInTransaction(
            String rawSlug,
            String displayName,
            String accountLogin,
            AccountType accountType,
            @Nullable Long ownerUserId) {
        String slug = Objects.requireNonNull(workspaceSlugService.normalize(rawSlug));
        workspaceSlugService.validate(slug);

        if (!workspaceSlugService.isAvailable(slug)) {
            throw new WorkspaceSlugConflictException(slug);
        }

        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(displayName);
        workspace.setIsPubliclyViewable(DEFAULT_PUBLIC_VISIBILITY);
        workspace.setAccountLogin(accountLogin);
        workspace.setAccountType(accountType);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);

        try {
            Workspace saved = workspaceRepository.save(workspace);
            createOwnerRole(saved, ownerUserId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Unique constraint violation on slug
            throw new WorkspaceSlugConflictException(slug, e);
        }
    }

    /**
     * Creates a workspace from a DTO and provisions the matching {@link IntegrationKind}
     * Connection. The PAT travels through {@code ConnectionService.rotateBearerToken}
     * which encrypts at rest under the registry's AES-256-GCM converter.
     */
    @Transactional
    public Workspace createWorkspace(CreateWorkspaceRequestDTO request) {
        return createWorkspaceInTransaction(request);
    }

    // Trusted in-process provisioners still supply an owner without an HTTP authentication context.
    @SuppressWarnings("removal")
    private Workspace createWorkspaceInTransaction(CreateWorkspaceRequestDTO request) {
        String workspaceSlug = Objects.requireNonNull(request.workspaceSlug(), "workspaceSlug is required");
        String displayName = Objects.requireNonNull(request.displayName(), "displayName is required");
        String accountLogin = Objects.requireNonNull(request.accountLogin(), "accountLogin is required");
        AccountType accountType = Objects.requireNonNull(request.accountType(), "accountType is required");
        IntegrationKind kind = Objects.requireNonNull(request.kind(), "kind is required");
        String personalAccessToken =
                Objects.requireNonNull(request.personalAccessToken(), "personalAccessToken is required");

        // Creation has no workspace identity yet. Resolve the account's verified actors directly.
        Long ownerUserId = SecurityUtils.getCurrentAccountId().isPresent()
                ? currentAccountUsers.resolve().stream()
                        .map(user -> Objects.requireNonNull(user.getId()))
                        .findFirst()
                        .orElseThrow(() ->
                                new AccessForbiddenException("Connect an SCM account before creating a workspace"))
                : request.ownerUserId();

        Workspace workspace =
                createWorkspaceInTransaction(workspaceSlug, displayName, accountLogin, accountType, ownerUserId);

        boolean isGitLab = kind == IntegrationKind.GITLAB;

        if (isGitLab) {
            // GitLab PAT workspaces monitor all repositories in the group by default.
            workspace.setRepositorySelection(RepositorySelection.ALL);
            workspaceRepository.save(workspace);

            String serverUrl =
                    (request.serverUrl() != null && !request.serverUrl().isBlank())
                            ? request.serverUrl().trim()
                            : null;
            connectionService.provisionPatConnection(
                    workspace,
                    IntegrationKind.GITLAB,
                    serverUrl,
                    new ConnectionConfig.GitLabConfig(
                            serverUrl,
                            /* gitlabGroupId */ null,
                            /* gitlabWebhookId */ null,
                            ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                            Set.of()),
                    personalAccessToken,
                    "create-workspace-" + workspace.getId());
        } else {
            // kind == GITHUB → PAT-backed; App installations bypass this DTO entirely
            // (they arrive via GithubLifecycleListener.createOrUpdateFromInstallation).
            // The DTO validator rejects any other kind.
            workspaceRepository.save(workspace);
            String serverUrl =
                    (request.serverUrl() != null && !request.serverUrl().isBlank())
                            ? request.serverUrl().trim()
                            : null;
            connectionService.provisionPatConnection(
                    workspace,
                    IntegrationKind.GITHUB,
                    "pat",
                    new ConnectionConfig.GitHubPatConfig(accountLogin, serverUrl, Set.of()),
                    personalAccessToken,
                    "create-workspace-" + workspace.getId());
        }

        return workspace;
    }

    /**
     * Creates a workspace and triggers async GitLab initialization if applicable.
     *
     * @param request the workspace creation request
     * @return the created workspace
     */
    public Workspace createWorkspaceWithInitialization(CreateWorkspaceRequestDTO request) {
        Workspace workspace =
                Objects.requireNonNull(transactionTemplate.execute(status -> createWorkspaceInTransaction(request)));

        eventPublisher.publishEvent(new WorkspaceCreatedEvent(
                workspace.getId(), Objects.requireNonNull(request.kind(), "kind is required")));

        return workspace;
    }

    private void createOwnerRole(Workspace workspace, @Nullable Long ownerUserId) {
        if (ownerUserId == null) {
            throw new IllegalStateException("Cannot create workspace without an owner. "
                    + "The authenticated user must have a corresponding git provider User entity. "
                    + "workspaceSlug="
                    + workspace.getWorkspaceSlug());
        }
        workspaceMembershipService.createMembership(workspace, ownerUserId, WorkspaceMembership.WorkspaceRole.OWNER);
    }

    // Workspace Account Login Management

    @Transactional
    public Workspace updateAccountLogin(Long workspaceId, String accountLogin) {
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspaceId));

        if (!Objects.equals(workspace.getAccountLogin(), accountLogin)) {
            workspace.setAccountLogin(accountLogin);
            workspace = workspaceRepository.save(workspace);
        }

        return workspace;
    }

    // League Points Recalculation

    /**
     * Reset and recalculate league points for all users by replaying their
     * contributions from the first recorded activity until now.
     */
    @Transactional
    public void resetAndRecalculateLeagues(String slug) {
        Workspace workspace = requireWorkspace(slug);
        log.info(
                "Reset league points: workspaceId={}, workspaceSlug={}",
                workspace.getId(),
                workspace.getWorkspaceSlug());
        resetAndRecalculateLeaguesInternal(workspace.getId());
    }

    public void resetAndRecalculateLeagues(WorkspaceContext workspaceContext) {
        Workspace workspace = requireWorkspace(requireSlug(workspaceContext));
        resetAndRecalculateLeaguesInternal(workspace.getId());
    }

    private void resetAndRecalculateLeaguesInternal(Long workspaceId) {
        log.debug("Recalculating league points: workspaceId={}", workspaceId);

        if (workspaceId == null) {
            log.warn("Skipped league recalculation: reason=workspaceIdIsNull");
            return;
        }

        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            log.warn("Skipped league recalculation: reason=workspaceNotFound, workspaceId={}", workspaceId);
            return;
        }

        leaguePointsRecalculator.recalculate(workspace);
    }

    // Settings Delegation

    public Workspace updateSchedule(WorkspaceContext workspaceContext, Integer day, String time) {
        Workspace workspace = requireWorkspace(requireSlug(workspaceContext));
        return workspaceSettingsService.updateSchedule(workspace.getId(), day, time);
    }

    public Workspace updateNotifications(
            String slug, @Nullable Boolean enabled, @Nullable String team, @Nullable String channelId) {
        Workspace workspace = requireWorkspace(slug);
        return workspaceSettingsService.updateNotifications(workspace.getId(), enabled, team, channelId);
    }

    public Workspace updateNotifications(
            WorkspaceContext workspaceContext,
            @Nullable Boolean enabled,
            @Nullable String team,
            @Nullable String channelId) {
        return updateNotifications(requireSlug(workspaceContext), enabled, team, channelId);
    }

    public Workspace updateLeaderboardDigest(
            String slug, Integer day, String time, Boolean enabled, @Nullable String team, @Nullable String channelId) {
        Workspace workspace = requireWorkspace(slug);
        return workspaceSettingsService.updateLeaderboardDigest(workspace.getId(), day, time, enabled, team, channelId);
    }

    public Workspace updateLeaderboardDigest(
            WorkspaceContext workspaceContext,
            Integer day,
            String time,
            Boolean enabled,
            @Nullable String team,
            @Nullable String channelId) {
        return updateLeaderboardDigest(requireSlug(workspaceContext), day, time, enabled, team, channelId);
    }

    public Workspace updateToken(String slug, String personalAccessToken) {
        Workspace workspace = requireWorkspace(slug);
        return workspaceSettingsService.updateToken(workspace.getId(), personalAccessToken);
    }

    public Workspace updateToken(WorkspaceContext workspaceContext, String personalAccessToken) {
        return updateToken(requireSlug(workspaceContext), personalAccessToken);
    }

    public Workspace updatePublicVisibility(String slug, Boolean isPubliclyViewable) {
        Workspace workspace = requireWorkspace(slug);
        return workspaceSettingsService.updatePublicVisibility(workspace.getId(), isPubliclyViewable);
    }

    public Workspace updatePublicVisibility(WorkspaceContext workspaceContext, Boolean isPubliclyViewable) {
        return updatePublicVisibility(requireSlug(workspaceContext), isPubliclyViewable);
    }

    public Workspace updateFeatures(WorkspaceContext workspaceContext, UpdateWorkspaceFeaturesRequestDTO request) {
        Workspace workspace = requireWorkspace(requireSlug(workspaceContext));
        return workspaceSettingsService.updateFeatures(workspace.getId(), request);
    }

    // Slug Renaming

    @Transactional
    public Workspace renameSlug(WorkspaceContext workspaceContext, String newSlug) {
        Objects.requireNonNull(workspaceContext, "WorkspaceContext must not be null");

        Long workspaceId = workspaceContext.id();
        if (workspaceId == null) {
            throw new EntityNotFoundException("Workspace", "context");
        }

        return renameSlugInTransaction(workspaceId, newSlug);
    }

    @Transactional
    public Workspace renameSlug(Long workspaceId, String newSlug) {
        return renameSlugInTransaction(workspaceId, newSlug);
    }

    private Workspace renameSlugInTransaction(Long workspaceId, String newSlug) {
        workspaceSlugService.validate(newSlug);

        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));

        String currentSlug = workspace.getWorkspaceSlug();

        if (currentSlug.equals(newSlug)) {
            log.debug(
                    "Skipped workspace rename: reason=slugUnchanged, workspaceId={}, slug={}",
                    workspaceId,
                    LoggingUtils.sanitizeForLog(newSlug));
            return workspace;
        }

        if (workspaceRepository.existsByWorkspaceSlug(newSlug)) {
            throw new WorkspaceSlugConflictException(newSlug);
        }

        if (!workspaceSlugService.isAvailable(newSlug)) {
            throw new WorkspaceSlugConflictException(newSlug);
        }

        workspaceSlugService.recordRename(workspace, currentSlug, newSlug);

        workspace.setWorkspaceSlug(newSlug);
        Workspace saved = workspaceRepository.save(workspace);

        log.info(
                "Renamed workspace: workspaceId={}, oldSlug={}, newSlug={}",
                workspaceId,
                LoggingUtils.sanitizeForLog(currentSlug),
                LoggingUtils.sanitizeForLog(newSlug));

        return saved;
    }

    // Helper Methods

    private Workspace requireWorkspace(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Workspace slug must not be blank.");
        }
        return workspaceRepository
                .findByWorkspaceSlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", slug));
    }

    private String requireSlug(WorkspaceContext workspaceContext) {
        Objects.requireNonNull(workspaceContext, "WorkspaceContext must not be null");
        String slug = workspaceContext.slug();
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Workspace context slug must not be blank.");
        }
        return slug;
    }
}
