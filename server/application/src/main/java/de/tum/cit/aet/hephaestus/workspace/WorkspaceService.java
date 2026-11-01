package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.identity.GitLabWorkspaceInstance;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup.RepositoryIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.SelectedRepositoryVerifier;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceFeaturesRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.events.WorkspaceCreatedEvent;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceSlugConflictException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@WorkspaceAgnostic("Manages workspaces themselves - the tenant root, not data within workspaces")
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private static final boolean DEFAULT_PUBLIC_ACTIVITY_ENABLED = false;

    // Core repositories
    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final CurrentAccountUsers currentAccountUsers;
    private final GitLabWorkspaceInstance gitLabWorkspaceInstance;

    // Services
    private final WorkspaceSlugService workspaceSlugService;
    private final WorkspaceSettingsService workspaceSettingsService;
    private final MembershipService workspaceMembershipService;
    private final ConnectionService connectionService;
    private final List<SelectedRepositoryVerifier> repositoryVerifiers;

    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            CurrentAccountUsers currentAccountUsers,
            GitLabWorkspaceInstance gitLabWorkspaceInstance,
            WorkspaceSlugService workspaceSlugService,
            WorkspaceSettingsService workspaceSettingsService,
            MembershipService workspaceMembershipService,
            ConnectionService connectionService,
            List<SelectedRepositoryVerifier> repositoryVerifiers,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.currentAccountUsers = currentAccountUsers;
        this.gitLabWorkspaceInstance = gitLabWorkspaceInstance;
        this.workspaceSlugService = workspaceSlugService;
        this.workspaceSettingsService = workspaceSettingsService;
        this.workspaceMembershipService = workspaceMembershipService;
        this.connectionService = connectionService;
        this.repositoryVerifiers = repositoryVerifiers;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Optional<Workspace> getWorkspaceBySlug(String slug) {
        return workspaceRepository.findByWorkspaceSlug(slug);
    }

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
            String slug, String displayName, String accountLogin, AccountType accountType, @Nullable Long ownerUserId) {
        workspaceSlugService.validate(slug);

        if (!workspaceSlugService.isAvailable(slug)) {
            throw new WorkspaceSlugConflictException(slug);
        }

        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(displayName);
        workspace.setPublicActivityEnabled(DEFAULT_PUBLIC_ACTIVITY_ENABLED);
        workspace.setAccountLogin(accountLogin);
        workspace.setAccountType(accountType);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);

        try {
            Workspace saved = workspaceRepository.saveAndFlush(workspace);
            createOwnerRole(saved, ownerUserId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Unique constraint violation on slug
            throw new WorkspaceSlugConflictException(slug, e);
        }
    }

    /**
     * Creates a workspace from a DTO and provisions the matching {@link IntegrationKind}
     * Connection. The PAT travels through {@code ConnectionService.rotateBearerToken} which
     * encrypts at rest under the registry's AES-256-GCM converter.
     *
     * <p>Selected GitLab projects are checked with GitLab before the creation transaction opens, so
     * no provider request holds it open and a refused project creates nothing.
     */
    public Workspace createWorkspace(CreateWorkspaceRequestDTO request) {
        @Nullable List<RepositoryIdentity> selected = verifiedSelection(request);
        return Objects.requireNonNull(transactionTemplate.execute(
                status -> createWorkspaceInTransaction(request, selected, authenticatedOwner(request))));
    }

    /**
     * Creates a workspace from a DTO for an owner that an in-process caller already resolved. The HTTP
     * path uses {@link #createWorkspace(CreateWorkspaceRequestDTO)}, which takes the owner from the
     * authenticated account.
     */
    public Workspace createWorkspace(CreateWorkspaceRequestDTO request, Long ownerUserId) {
        @Nullable List<RepositoryIdentity> selected = verifiedSelection(request);
        return Objects.requireNonNull(
                transactionTemplate.execute(status -> createWorkspaceInTransaction(request, selected, ownerUserId)));
    }

    /**
     * The authenticated account's verified actor on the workspace's provider, never one of its other
     * links. Creation has no workspace identity yet.
     */
    private Long authenticatedOwner(CreateWorkspaceRequestDTO request) {
        if (Objects.requireNonNull(request.kind(), "kind is required") == IntegrationKind.GITLAB) {
            String serverUrl = gitLabWorkspaceInstance.require(request.serverUrl());
            var owners = currentAccountUsers.resolve().stream()
                    .filter(actor -> actor.providerType().equals(IdentityProviderType.GITLAB.name())
                            && ScmOrigin.of(actor.serverUrl()).equals(ScmOrigin.of(serverUrl)))
                    .map(actor -> actor.actorId())
                    .toList();
            if (owners.size() != 1)
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Connect exactly one GitLab account on this instance before creating a workspace.");
            return owners.getFirst();
        }
        return currentAccountUsers.resolve().stream()
                .filter(user -> user.providerType().equals(IdentityProviderType.GITHUB.name()))
                .map(user -> user.actorId())
                .findFirst()
                .orElseThrow(() -> new AccessForbiddenException(
                        "Connect your GitHub account before you create" + " a GitHub workspace."));
    }

    /**
     * The projects a GitLab request selected, as GitLab reports them to the request's token, or
     * {@code null} when the workspace follows every project of its group.
     */
    private @Nullable List<RepositoryIdentity> verifiedSelection(CreateWorkspaceRequestDTO request) {
        if (!request.hasConsistentRepositorySelection()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Choose all projects without project IDs, or choose SELECTED with at least one project ID."
                            + " Only GitLab workspaces choose projects.");
        }
        List<Long> projectIds = request.selectedProjectIds();
        if (request.repositorySelection() != RepositorySelection.SELECTED || projectIds == null) {
            return null;
        }
        SelectedRepositoryVerifier verifier = repositoryVerifiers.stream()
                .filter(candidate -> candidate.kind() == IntegrationKind.GITLAB)
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("GitLab project selection is not available on this server"));
        return verifier.verify(
                Objects.requireNonNull(request.personalAccessToken(), "personalAccessToken is required"),
                request.serverUrl(),
                Objects.requireNonNull(request.accountLogin(), "accountLogin is required"),
                new LinkedHashSet<>(projectIds));
    }

    private Workspace createWorkspaceInTransaction(
            CreateWorkspaceRequestDTO request, @Nullable List<RepositoryIdentity> selected, Long ownerUserId) {
        String workspaceSlug = Objects.requireNonNull(request.workspaceSlug(), "workspaceSlug is required");
        String displayName = Objects.requireNonNull(request.displayName(), "displayName is required");
        String accountLogin = Objects.requireNonNull(request.accountLogin(), "accountLogin is required");
        AccountType accountType = Objects.requireNonNull(request.accountType(), "accountType is required");
        IntegrationKind kind = Objects.requireNonNull(request.kind(), "kind is required");
        String personalAccessToken =
                Objects.requireNonNull(request.personalAccessToken(), "personalAccessToken is required");

        boolean isGitLab = kind == IntegrationKind.GITLAB;
        String serverUrl = isGitLab
                ? gitLabWorkspaceInstance.require(request.serverUrl())
                : (request.serverUrl() != null && !request.serverUrl().isBlank())
                        ? request.serverUrl().trim()
                        : null;

        Workspace workspace =
                createWorkspaceInTransaction(workspaceSlug, displayName, accountLogin, accountType, ownerUserId);

        if (isGitLab) {
            // GitLab PAT workspaces monitor all repositories in the group by default. Initialization
            // starts once the connection's ACTIVE transition commits, so a selection is in place first.
            workspace.setRepositorySelection(selected == null ? RepositorySelection.ALL : RepositorySelection.SELECTED);
            workspaceRepository.save(workspace);
            for (RepositoryIdentity project : selected == null ? List.<RepositoryIdentity>of() : selected) {
                RepositoryToMonitor monitor = new RepositoryToMonitor();
                monitor.setNameWithOwner(project.fullPath());
                monitor.setNativeId(project.nativeId());
                monitor.setWorkspace(workspace);
                repositoryToMonitorRepository.save(monitor);
                workspace.getRepositoriesToMonitor().add(monitor);
            }

            connectionService.provisionPatConnection(
                    workspace,
                    IntegrationKind.GITLAB,
                    serverUrl,
                    new ConnectionConfig.GitLabConfig(
                            serverUrl, /* gitlabGroupId */ null, /* gitlabWebhookId */ null, Set.of(), null),
                    personalAccessToken,
                    "create-workspace-" + workspace.getId());
        } else {
            // kind == GITHUB → PAT-backed; App installations bypass this DTO entirely
            // (they arrive via GitHubLifecycleListener.createOrUpdateFromInstallation).
            // The DTO validator rejects any other kind.
            workspaceRepository.save(workspace);
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
        Workspace workspace = createWorkspace(request);

        eventPublisher.publishEvent(new WorkspaceCreatedEvent(
                workspace.getId(), Objects.requireNonNull(request.kind(), "kind is required")));

        return workspace;
    }

    private void createOwnerRole(Workspace workspace, @Nullable Long ownerUserId) {
        if (ownerUserId == null) {
            log.warn("Workspace has no owner user: workspaceSlug={}", workspace.getWorkspaceSlug());
            throw new IllegalStateException("We could not create this workspace because your account has no connected"
                    + " GitHub or GitLab account. Connect one in User settings, then try"
                    + " again.");
        }
        workspaceMembershipService.createMembership(
                workspace, workspaceMembershipService.accountIdForUser(ownerUserId), Membership.WorkspaceRole.OWNER);
    }

    @Transactional
    public Workspace updateAccountLogin(Long workspaceId, String accountLogin) {
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("We could not find that workspace. It may have been"
                        + " deleted. Reload the page to see what is"
                        + " current."));

        if (!Objects.equals(workspace.getAccountLogin(), accountLogin)) {
            workspace.setAccountLogin(accountLogin);
            workspace = workspaceRepository.save(workspace);
        }

        return workspace;
    }

    public Workspace updateToken(String slug, String personalAccessToken) {
        Workspace workspace = requireWorkspace(slug);
        return workspaceSettingsService.updateToken(workspace.getId(), personalAccessToken);
    }

    public Workspace updateToken(WorkspaceContext workspaceContext, String personalAccessToken) {
        return updateToken(requireSlug(workspaceContext), personalAccessToken);
    }

    public Workspace updatePublicActivity(String slug, Boolean publicActivityEnabled, boolean allowSearchEngines) {
        Workspace workspace = requireWorkspace(slug);
        return workspaceSettingsService.updatePublicActivity(
                workspace.getId(), publicActivityEnabled, allowSearchEngines);
    }

    public Workspace updatePublicActivity(
            WorkspaceContext workspaceContext, Boolean publicActivityEnabled, boolean allowSearchEngines) {
        return updatePublicActivity(requireSlug(workspaceContext), publicActivityEnabled, allowSearchEngines);
    }

    public Workspace updateFeatures(WorkspaceContext workspaceContext, UpdateWorkspaceFeaturesRequestDTO request) {
        Workspace workspace = requireWorkspace(requireSlug(workspaceContext));
        return workspaceSettingsService.updateFeatures(workspace.getId(), request);
    }

    @Transactional
    public Workspace renameSlug(Long workspaceId, String newSlug) {
        workspaceSlugService.validate(newSlug);

        Workspace workspace = workspaceRepository
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));

        String currentSlug = workspace.getWorkspaceSlug();

        if (currentSlug.equals(newSlug)) {
            log.debug(
                    "Skipped workspace rename: reason=slugUnchanged, workspaceId={}, slug={}",
                    workspaceId,
                    LoggingUtils.sanitizeForLog(newSlug));
            return workspace;
        }

        if (!workspaceSlugService.isAvailable(newSlug)) {
            throw new WorkspaceSlugConflictException(newSlug);
        }

        workspaceSlugService.recordRename(workspace, currentSlug, newSlug);

        workspace.setWorkspaceSlug(newSlug);
        Workspace saved;
        try {
            saved = workspaceRepository.saveAndFlush(workspace);
        } catch (DataIntegrityViolationException exception) {
            throw new WorkspaceSlugConflictException(newSlug, exception);
        }

        log.info(
                "Renamed workspace: workspaceId={}, oldSlug={}, newSlug={}",
                workspaceId,
                LoggingUtils.sanitizeForLog(currentSlug),
                LoggingUtils.sanitizeForLog(newSlug));

        return saved;
    }

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
