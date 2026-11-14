package de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle;

import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption.GitHubInstallationRequests;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugService;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns workspace lifecycle mutations for {@code GitHubWorkspaceProvisioningAdapter} webhook events
 * and {@code GitHubInstallationReconciler} reconciliation.
 *
 * <p>Suspension and resumption use the status helpers, not installation lifecycle SPI events.
 */
@Component
@ConditionalOnServerRole
public class GitHubLifecycleListener implements IntegrationLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(GitHubLifecycleListener.class);

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;
    private final IdentityProviderLookup identityProviders;

    private final WorkspaceSlugService workspaceSlugService;
    private final MembershipService workspaceMembershipService;
    private final PeopleRegistry people;

    private final GitHubAppTokenService gitHubAppTokenService;
    private final OrganizationService organizationService;
    private final ConnectionService connectionService;
    private final WorkspaceLifecycleService workspaceLifecycleService;
    private final IntegrationAttentionService attention;
    private final ScmWorkspaceContentEraser contentEraser;
    private final GitHubInstallationRequests installationRequests;

    public GitHubLifecycleListener(
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            UserRepository userRepository,
            IdentityProviderLookup identityProviders,
            WorkspaceSlugService workspaceSlugService,
            MembershipService workspaceMembershipService,
            PeopleRegistry people,
            GitHubAppTokenService gitHubAppTokenService,
            OrganizationService organizationService,
            ConnectionService connectionService,
            WorkspaceLifecycleService workspaceLifecycleService,
            IntegrationAttentionService attention,
            ScmWorkspaceContentEraser contentEraser,
            GitHubInstallationRequests installationRequests) {
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
        this.userRepository = userRepository;
        this.identityProviders = identityProviders;
        this.workspaceSlugService = workspaceSlugService;
        this.workspaceMembershipService = workspaceMembershipService;
        this.people = people;
        this.gitHubAppTokenService = gitHubAppTokenService;
        this.organizationService = organizationService;
        this.connectionService = connectionService;
        this.workspaceLifecycleService = workspaceLifecycleService;
        this.attention = attention;
        this.contentEraser = contentEraser;
        this.installationRequests = installationRequests;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    // SPI hooks (IntegrationLifecycleListener)

    /**
     * SPI hook: bridge to {@link #createOrUpdateFromInstallation} by parsing the
     * installation id from {@link IntegrationRef#instanceKey()}.
     *
     * <p>The {@code initialResources} list is ignored: repository monitor creation lives
     * in {@code GitHubWorkspaceProvisioningAdapter}, which holds the monitor-service dependency
     * that plain SPI dispatch doesn't carry.
     */
    @Override
    public void onInstanceInstalled(InstanceProvisioned event) {
        IntegrationRef ref = event.ref();
        Long installationId = parseInstallationId(ref);
        if (installationId == null) {
            log.warn("GitHub onInstanceInstalled: skipped, reason=invalidInstanceKey, ref={}", ref);
            return;
        }
        TenantAccount account = event.account();
        createOrUpdateFromInstallationInternal(
                installationId,
                account != null ? parseAccountIdNullable(account.externalId()) : null,
                account != null ? account.displayName() : null,
                account != null ? account.kind() : AccountKind.ORGANIZATION,
                account != null ? account.avatarUrl() : null,
                RepositorySelection.SELECTED,
                null);
    }

    /**
     * SPI hook: run the full workspace purge for a vendor-side uninstall.
     * Delegates to {@link #purgeWorkspaceForInstallation(long)} — see there for why a bare
     * status write is not an acceptable substitute.
     */
    @Override
    public void onInstanceUninstalled(IntegrationRef ref) {
        Long installationId = parseInstallationId(ref);
        if (installationId == null) {
            log.warn("GitHub onInstanceUninstalled: skipped, reason=invalidInstanceKey, ref={}", ref);
            return;
        }
        purgeWorkspaceForInstallationInternal(installationId);
    }

    /**
     * SPI hook: log scope delta sizes. The actual repository-monitor reconciliation
     * runs in {@code GitHubWorkspaceProvisioningAdapter.onRepositoriesAdded/Removed} which
     * holds the monitor-service dependency. Empty deltas short-circuit so duplicate
     * webhook deliveries don't flap audit logs.
     */
    @Override
    public void onScopeChanged(IntegrationRef ref, ScopeDelta delta) {
        int added = delta.added() != null ? delta.added().size() : 0;
        int removed =
                delta.removedExternalIds() != null ? delta.removedExternalIds().size() : 0;
        if (added == 0 && removed == 0) {
            return;
        }
        log.info("GitHub scope change: ref={}, added={}, removed={}", ref, added, removed);
    }

    /**
     * SPI hook: full account rename handling — workspace login update, monitor
     * retargeting, repo renaming, event subject rotation. Bridges
     * {@link IntegrationRef#instanceKey()} to {@link #handleAccountRename}.
     */
    @Override
    public void onTenantRenamed(IntegrationRef ref, String oldName, String newName) {
        Long installationId = parseInstallationId(ref);
        if (installationId == null) {
            log.warn("GitHub onTenantRenamed: skipped, reason=invalidInstanceKey, ref={}", ref);
            return;
        }
        handleAccountRenameInternal(installationId, oldName, newName);
    }

    // Public helpers — called by adapter / provisioning service (non-SPI)

    /**
     * Creates or updates a workspace from a GitHub App installation with full account info.
     *
     * @param installationId      the GitHub App installation ID
     * @param accountId           the GitHub account database ID
     * @param accountLogin        the GitHub account login (organization or user)
     * @param accountKind         the account kind (USER or ORGANIZATION)
     * @param avatarUrl           the account's avatar URL
     * @param repositorySelection the repository selection mode (ALL or SELECTED)
     * @return the created or updated workspace, or null if workspace creation was skipped
     */
    @Transactional
    public @Nullable Workspace createOrUpdateFromInstallation(
            long installationId,
            @Nullable Long accountId,
            @Nullable String accountLogin,
            AccountKind accountKind,
            @Nullable String avatarUrl,
            @Nullable RepositorySelection repositorySelection) {
        return createOrUpdateFromInstallationInternal(
                installationId, accountId, accountLogin, accountKind, avatarUrl, repositorySelection, null);
    }

    /** The account that GitHub confirmed an installation is on. */
    public record InstallationAccount(
            long id,
            String login,
            AccountKind kind,
            @Nullable String avatarUrl) {}

    /**
     * An instance admin adopts an installation that GitHub has just confirmed: it joins the workspace that the
     * installation already has, or creates one with the admin as its owner.
     *
     * @return the workspace, or null if the installation cannot have one, such as a purged workspace's organization
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public @Nullable Workspace adoptInstallation(
            long installationId,
            InstallationAccount account,
            RepositorySelection repositorySelection,
            long adminAccountId) {
        return createOrUpdateFromInstallationInternal(
                installationId,
                account.id(),
                account.login(),
                account.kind(),
                account.avatarUrl(),
                repositorySelection,
                adminAccountId);
    }

    private @Nullable Workspace createOrUpdateFromInstallationInternal(
            long installationId,
            @Nullable Long accountId,
            @Nullable String accountLogin,
            AccountKind accountKind,
            @Nullable String avatarUrl,
            @Nullable RepositorySelection repositorySelection,
            @Nullable Long adminAccountId) {
        // Check suspension before any reactivation logic: replayed provider inbox "created" events
        // must not reactivate a suspended workspace and re-trigger failed repository syncs.
        if (gitHubAppTokenService.isInstallationMarkedSuspended(installationId)) {
            log.info("Skipped workspace reactivation: reason=installationSuspended, installationId={}", installationId);
            return null;
        }

        Workspace workspace = workspaceRepository
                .findByInstallationIdForUpdate(installationId)
                .orElse(null);
        if (workspace != null && workspace.getStatus() == Workspace.WorkspaceStatus.PURGED) {
            log.info("Skipped installation event for purged workspace: installationId={}", installationId);
            return null;
        }

        // A login is not unique and a personal account's workspace cannot prove its owner, so only an
        // organization's immutable id finds the workspace that the installation joins.
        if (workspace == null && accountKind == AccountKind.ORGANIZATION && accountId != null) {
            Workspace existing = organizationService
                    .findId(accountId, githubProviderId())
                    .flatMap(workspaceRepository::findByOrganizationIdForUpdate)
                    .orElse(null);
            if (existing != null && existing.getStatus() == Workspace.WorkspaceStatus.PURGED) {
                log.info(
                        "Skipped installation event for purged organization workspace: workspaceId={}, installationId={}",
                        existing.getId(),
                        installationId);
                return null;
            }

            if (existing != null) {
                boolean isPatWorkspace = connectionService
                        .findActiveGitHubPatConfig(existing.getId())
                        .isPresent();
                // Only a PAT workspace runs on a stored token; an App row's blob is not read here.
                boolean hasPatToken = isPatWorkspace
                        && connectionService
                                .findActiveBearerToken(existing.getId(), IntegrationKind.GITHUB)
                                .map(b -> b.token() != null && !b.token().isBlank())
                                .orElse(false);

                if (hasPatToken) {
                    log.info(
                            "Skipped GitHub App installation linking, PAT workspace has stored token: workspaceId={}, installationId={}",
                            existing.getId(),
                            installationId);
                    return existing;
                }

                log.info(
                        isPatWorkspace
                                ? "Promoted PAT workspace to GitHub App, no PAT token stored: workspaceId={}, installationId={}"
                                : "Linked existing workspace to installation: workspaceId={}, installationId={}",
                        existing.getId(),
                        installationId);
                workspace = existing;
            }
        }

        if (workspace == null) {
            if (isBlank(accountLogin)) {
                throw new IllegalArgumentException(
                        "Cannot create workspace from installation " + installationId + " without accountLogin.");
            }
            String login = Objects.requireNonNull(accountLogin);

            if (adminAccountId == null && installationRequests.isAdopted(installationId)) {
                // The admin's adoption created this installation's one workspace, which is gone now.
                log.info(
                        "Skipped workspace creation: reason=adoptedWorkspaceRemoved, installationId={}",
                        installationId);
                return null;
            }
            if (adminAccountId == null && installationRequests.requiresApproval()) {
                installationRequests.recordPending(installationId, accountId, login, accountKind);
                log.info("Installation awaits an instance admin: installationId={}", installationId);
                return null;
            }

            Long ownerAccountId;
            if (adminAccountId != null) {
                ownerAccountId = adminAccountId;
            } else {
                Long ownerUserId = syncGitHubUserForOwnership(installationId, accountId, login, accountKind, avatarUrl);
                if (ownerUserId == null) {
                    // Likely an old or deleted installation whose owner user can no longer be resolved.
                    log.warn("Skipped workspace creation, cannot sync owner user: installationId={}", installationId);
                    return null;
                }
                ownerAccountId = humanAccountOf(ownerUserId);
            }

            AccountType wsAccountType = accountKind == AccountKind.ORGANIZATION ? AccountType.ORG : AccountType.USER;

            String availableSlug = workspaceSlugService.allocate(login, "install-" + installationId + "-" + login);

            // Do NOT redirect the desired slug to the allocated slug: the desired slug may
            // belong to another workspace, and redirecting would leak or hijack it. Callers
            // must surface the allocated slug to the user instead.
            workspace = createWorkspace(availableSlug, login, login, wsAccountType, ownerAccountId);
            log.info(
                    "Created workspace from installation: workspaceId={}, installationId={}, ownerAccountId={}",
                    workspace.getId(),
                    installationId,
                    ownerAccountId);
        }

        if (!isBlank(accountLogin)) {
            workspace.setAccountLogin(Objects.requireNonNull(accountLogin));
        }

        if (repositorySelection != null) {
            workspace.setRepositorySelection(repositorySelection);
        }

        Workspace.WorkspaceStatus previousStatus = workspace.getStatus();
        if (workspace.resume()) {
            log.info(
                    "Reactivated workspace from installation: workspaceId={}, previousStatus={}, installationId={}",
                    workspace.getId(),
                    previousStatus,
                    installationId);
        }

        // Organization must exist before repositories are created so they get organization_id set.
        if (accountKind == AccountKind.ORGANIZATION && accountId != null) {
            Organization org = organizationService.upsertIdentity(
                    accountId, Objects.requireNonNull(accountLogin), githubProviderId());
            workspace.setOrganizationId(org.getId());
            log.debug("Linked organization to workspace: orgId={}, workspaceId={}", org.getId(), workspace.getId());
        }

        Workspace saved = workspaceRepository.save(workspace);

        // upsertGitHubAppConnection retires any non-matching GITHUB connection on this workspace
        // (PAT or stale App) and reuses or creates the App row. Correlation id is stable per
        // installation so webhook redelivery is idempotent.
        connectionService.upsertGitHubAppConnection(
                saved,
                gitHubAppTokenService.getConfiguredAppId(),
                installationId,
                accountLogin,
                "install-bind-" + installationId);

        return saved;
    }

    /**
     * Vendor-side uninstall ({@code installation.deleted}) — runs the SAME purge an admin-initiated
     * workspace deletion runs, via {@link WorkspaceLifecycleService#purgeWorkspace(String)}.
     *
     * <p><b>Why not a status write.</b> {@code PURGED} is a terminal, slug-burning state that the
     * rest of the system reads as "this workspace's data is gone". Setting it without running the
     * purge chain makes that a lie: none of the {@code WorkspacePurgeContributor} beans fire, so
     * mirrored Slack messages, Outline documents, org-tier {@code team} /
     * {@code organization_membership} rows, practices/activity derived rows and still-ACTIVE
     * {@code Connection} rows all survive under a label that says they were erased. An org admin
     * uninstalling the App on github.com is exactly the moment the lawful basis for holding that
     * mirror ends, so it must erase at least as much as an admin disconnect does.
     *
     * <p><b>Idempotent.</b> {@code installation.deleted} can be redelivered; the delegate returns
     * early on an already-{@code PURGED} workspace, and every step underneath is delete-if-present.
     *
     * <p><b>Scope.</b> {@code findByInstallationId} resolves through the {@code Connection} row
     * ({@code kind=GITHUB, instance_key=installationId}) and is at-most-one by construction, so
     * this purges exactly the workspace that installation backs — never a sibling tenant. Shared
     * rows (repositories another workspace still monitors, the {@code Organization} identity) are
     * protected by the orphan guards inside the purge chain, not by this method.
     *
     * <p><b>Transaction.</b> Callers on the webhook-consumer path already run inside the
     * {@code TransactionTemplate} boundary opened by {@code AbstractIntegrationMessageHandler},
     * which the delegate's {@code REQUIRED} propagation joins; the reconciliation path opens its
     * own here. Tenancy needs no ambient workspace context — the purge chain's cross-tenant
     * queries are the {@code @WorkspaceAgnostic} ones that open their own bypass scope.
     *
     * @param installationId the GitHub App installation ID
     * @return the purged workspace, or empty if no workspace is bound to the installation
     */
    @Transactional
    public Optional<Workspace> purgeWorkspaceForInstallation(long installationId) {
        return purgeWorkspaceForInstallationInternal(installationId);
    }

    private Optional<Workspace> purgeWorkspaceForInstallationInternal(long installationId) {
        var workspaceOpt = workspaceRepository.findByInstallationIdForUpdate(installationId);
        if (workspaceOpt.isEmpty()) {
            log.warn(
                    "Skipped installation purge: reason=noWorkspaceForInstallation, installationId={}", installationId);
            return Optional.empty();
        }

        Workspace workspace = workspaceOpt.get();
        IntegrationRef ref =
                new IntegrationRef(IntegrationKind.GITHUB, workspace.getId(), Long.toString(installationId));
        connectionService
                .findReferenced(ref)
                .ifPresent(connection -> connectionService.disconnectForErasure(
                        connection,
                        new ConnectionService.TransitionRequest(
                                IntegrationState.UNINSTALLED,
                                "PROVIDER_UNINSTALLED",
                                "PROVIDER",
                                IntegrationKind.GITHUB.name(),
                                "provider-uninstall-github-" + installationId,
                                "GitHub reported the installation was deleted"),
                        () -> contentEraser.eraseWorkspaceScmMirror(workspace.getId())));
        Workspace purged = workspaceLifecycleService.purgeWorkspace(workspace.getWorkspaceSlug());
        log.info(
                "Purged workspace after vendor-side uninstall: installationId={}, workspaceId={}",
                installationId,
                purged.getId());
        return Optional.of(purged);
    }

    /**
     * Suspends the workspace of an installation that GitHub suspended. A purge is no status change but erases the
     * workspace's data: {@link #purgeWorkspaceForInstallation(long)} does it.
     *
     * @return the workspace, unchanged if it is purged, or empty if the installation has none
     */
    @Transactional
    public Optional<Workspace> suspendForInstallation(long installationId) {
        return changeStatus(installationId, false);
    }

    /**
     * Makes the workspace of an installation that GitHub unsuspended active again.
     *
     * @return the workspace, unchanged if it is purged, or empty if the installation has none
     */
    @Transactional
    public Optional<Workspace> resumeForInstallation(long installationId) {
        return changeStatus(installationId, true);
    }

    private Optional<Workspace> changeStatus(long installationId, boolean active) {
        var workspaceOpt = workspaceRepository.findByInstallationIdForUpdate(installationId);
        if (workspaceOpt.isEmpty()) {
            return workspaceOpt;
        }

        Workspace workspace = workspaceOpt.get();
        if (workspace.getStatus() == Workspace.WorkspaceStatus.PURGED) {
            return Optional.of(workspace);
        }
        if (active ? workspace.resume() : workspace.suspend()) {
            workspace = workspaceRepository.save(workspace);
        }

        long workspaceId = workspace.getId();
        connectionService
                .findActive(workspaceId, IntegrationKind.GITHUB)
                .ifPresent(connection -> attention.report(
                        connection.getId(),
                        workspaceId,
                        IntegrationAttentionChangedEvent.Problem.PROVIDER_SUSPENDED,
                        active));
        return Optional.of(workspace);
    }

    /**
     * Update repository selection for a given installation if provided and
     * different.
     *
     * @param installationId the GitHub App installation ID
     * @param selection      the new repository selection mode
     * @return the updated workspace, or empty if no workspace exists for the installation
     */
    @Transactional
    public Optional<Workspace> updateRepositorySelection(long installationId, RepositorySelection selection) {
        var workspaceOpt = workspaceRepository
                .findByInstallationIdForUpdate(installationId)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE);
        if (workspaceOpt.isEmpty() || selection == null) {
            return workspaceOpt;
        }

        Workspace workspace = workspaceOpt.get();
        if (workspace.getRepositorySelection() != selection) {
            workspace.setRepositorySelection(selection);
            workspace = workspaceRepository.save(workspace);
        }

        return Optional.of(workspace);
    }

    /**
     * Handle a GitHub account rename (installation_target event).
     * Updates the workspace account login, retargets repository monitors,
     * renames tracked repositories, and rotates the provider inbox organization consumer.
     *
     * @param installationId the GitHub App installation ID
     * @param previousLogin  the previous account login (may be null)
     * @param newLogin       the new account login
     */
    @Transactional
    public void handleAccountRename(long installationId, @Nullable String previousLogin, String newLogin) {
        handleAccountRenameInternal(installationId, previousLogin, newLogin);
    }

    private void handleAccountRenameInternal(long installationId, @Nullable String previousLogin, String newLogin) {
        if (isBlank(newLogin)) {
            log.warn("Skipped account rename: reason=missingTargetLogin, installationId={}", installationId);
            return;
        }

        workspaceRepository
                .findByInstallationIdForUpdate(installationId)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .ifPresentOrElse(
                        workspace -> {
                            String oldLogin = !isBlank(previousLogin) ? previousLogin : workspace.getAccountLogin();
                            if (!newLogin.equals(workspace.getAccountLogin())) {
                                workspace.setAccountLogin(newLogin);
                                workspaceRepository.save(workspace);
                            }
                            if (oldLogin != null) {
                                retargetRepositoryMonitors(workspace, oldLogin, newLogin);
                                renameTrackedRepositories(oldLogin, newLogin);
                            }
                        },
                        () -> log.warn(
                                "Skipped account rename: reason=unknownInstallation, installationId={}",
                                installationId));
    }

    // Private helpers

    private void retargetRepositoryMonitors(Workspace workspace, String oldLogin, String newLogin) {
        if (workspace == null || isBlank(oldLogin) || isBlank(newLogin) || oldLogin.equalsIgnoreCase(newLogin)) {
            return;
        }

        String prefixLower = (oldLogin + "/").toLowerCase(Locale.ENGLISH);
        var monitors = repositoryToMonitorRepository.findByWorkspaceId(workspace.getId());
        monitors.forEach(monitor -> {
            String current = monitor.getNameWithOwner();
            if (current == null) {
                return;
            }
            String normalized = current.toLowerCase(Locale.ENGLISH);
            if (!normalized.startsWith(prefixLower)) {
                return;
            }
            int slashIndex = current.indexOf('/');
            if (slashIndex < 0) {
                return;
            }
            String suffix = current.substring(slashIndex);
            String target = newLogin + suffix;
            if (monitors.stream()
                    .anyMatch(other ->
                            !other.getId().equals(monitor.getId()) && target.equals(other.getNameWithOwner()))) {
                log.warn(
                        "Skipped repository monitor rename: reason=nameHeldByAnotherMonitor, workspaceId={}, monitorId={}",
                        workspace.getId(),
                        monitor.getId());
                return;
            }
            monitor.setNameWithOwner(target);
            repositoryToMonitorRepository.save(monitor);
        });
    }

    private void renameTrackedRepositories(String oldLogin, String newLogin) {
        if (isBlank(oldLogin) || isBlank(newLogin) || oldLogin.equalsIgnoreCase(newLogin)) {
            return;
        }

        String prefix = oldLogin + "/";
        Long providerId = identityProviders
                .findId(IdentityProviderType.GITHUB, PersonSourceNamespace.GITHUB_DEFAULT_URL)
                .orElseThrow(() -> new IllegalStateException("IdentityProvider for GitHub not found"));
        var repositories =
                repositoryRepository.findByNameWithOwnerStartingWithIgnoreCaseAndProviderId(prefix, providerId);
        if (repositories.isEmpty()) {
            return;
        }

        repositories.forEach(repository -> {
            String current = repository.getNameWithOwner();
            if (current == null) {
                return;
            }
            int slashIndex = current.indexOf('/');
            if (slashIndex < 0) {
                return;
            }
            String suffix = current.substring(slashIndex);
            repository.setNameWithOwner(newLogin + suffix);
            repository.setHtmlUrl("https://github.com/" + repository.getNameWithOwner());
        });
        repositoryRepository.saveAll(repositories);
    }

    private Workspace createWorkspace(
            String slug,
            String displayName,
            String accountLogin,
            AccountType accountType,
            @Nullable Long ownerAccountId) {
        workspaceSlugService.validate(slug);

        Workspace saved = workspaceRepository.save(Workspace.create(slug, displayName, accountLogin, accountType));
        if (ownerAccountId != null) {
            workspaceMembershipService.createMembership(saved, ownerAccountId, Membership.WorkspaceRole.OWNER);
        }
        return saved;
    }

    /** The account of a personal installation's GitHub user; an organization has no human owner account. */
    private @Nullable Long humanAccountOf(Long userId) {
        User owner = userRepository.findById(userId).orElseThrow();
        if (owner.getType() != User.Type.USER) {
            return null;
        }
        return people.ensureHuman(
                new PersonIdentity(
                        Objects.requireNonNull(owner.getProvider().getId(), "Persisted SCM provider requires an id"),
                        Long.toString(owner.getNativeId()),
                        null),
                owner.getName() == null ? owner.getLogin() : owner.getName());
    }

    /**
     * Upserts the installation account by its immutable GitHub identity; login is only profile metadata.
     *
     * @param installationId the GitHub App installation ID
     * @param accountId      the GitHub account database ID
     * @param accountLogin   the GitHub account login
     * @param accountKind    the account kind (USER or ORGANIZATION)
     * @param avatarUrl      the account's avatar URL
     * @return the user ID, or null if user could not be created
     */
    private @Nullable Long syncGitHubUserForOwnership(
            long installationId,
            @Nullable Long accountId,
            String accountLogin,
            AccountKind accountKind,
            @Nullable String avatarUrl) {
        // Three-step upsert (lock, free conflicts, insert) avoids uk_user_login_lower
        // violations under concurrent installs.
        if (accountId != null) {
            String htmlUrl = "https://github.com/" + accountLogin;
            String typeStr =
                    accountKind == AccountKind.ORGANIZATION ? User.Type.ORGANIZATION.name() : User.Type.USER.name();

            long providerId = githubProviderId();

            userRepository.acquireLoginLock(accountLogin, providerId);
            userRepository.freeLoginConflicts(accountLogin, accountId, providerId);
            userRepository.upsertUser(
                    accountId,
                    providerId,
                    accountLogin,
                    accountLogin, // Use login as fallback name
                    avatarUrl != null ? avatarUrl : "",
                    htmlUrl,
                    typeStr,
                    null, // email
                    null, // createdAt
                    null // updatedAt
                    );
            log.info(
                    "Upserted user for workspace ownership: nativeId={}, userType={}, installationId={}",
                    accountId,
                    typeStr,
                    installationId);
            // upsertUser is a native INSERT that doesn't return the generated id; re-fetch for the
            // PK.
            return userRepository
                    .findByNativeIdAndProviderId(accountId, providerId)
                    .map(User::getId)
                    .orElseThrow(() ->
                            new IllegalStateException("Installation account was not found after its identity upsert"));
        }

        log.warn("Skipped user creation: reason=missingAccountId, installationId={}", installationId);
        return null;
    }

    private long githubProviderId() {
        return identityProviders
                .findId(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow(() -> new IllegalStateException("IdentityProvider for GitHub not found"));
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static @Nullable Long parseInstallationId(@Nullable IntegrationRef ref) {
        if (ref == null || ref.instanceKey() == null) {
            return null;
        }
        try {
            return Long.parseLong(ref.instanceKey());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static @Nullable Long parseAccountIdNullable(@Nullable String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(externalId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
