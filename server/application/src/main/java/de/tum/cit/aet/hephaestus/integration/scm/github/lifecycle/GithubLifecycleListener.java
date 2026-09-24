package de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
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
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugService;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical GitHub install / uninstall / rename / scope-change write path.
 *
 * <p>Canonical write path: the listener owns workspace creation, status updates,
 * NATS scope-consumer lifecycle, and account-rename retargeting. The two layers
 * above it are thin:
 * <ul>
 *   <li>{@code WorkspaceProvisioningAdapter} — implements the legacy
 *       {@code ProvisioningListener} SPI used by the GitHub webhook handler, and
 *       forwards every call here.</li>
 *   <li>{@code WorkspaceProvisioningService} — orchestrator that calls
 *       {@link #createOrUpdateFromInstallation} directly during {@code /app/installations}
 *       reconciliation.</li>
 * </ul>
 *
 * <p>SPI hook coverage (from {@link IntegrationLifecycleListener}):
 * <ul>
 *   <li>{@link #onInstanceInstalled} — parses installation id from
 *       {@link IntegrationRef#instanceKey()} and delegates to
 *       {@link #createOrUpdateFromInstallation(long, Long, String, AccountKind, String, RepositorySelection)}.</li>
 *   <li>{@link #onInstanceUninstalled} — runs the full workspace purge via
 *       {@link #purgeWorkspaceForInstallation}, which is the same
 *       {@code WorkspaceLifecycleService#purgeWorkspace} chain an admin deletion runs.</li>
 *   <li>{@link #onScopeChanged} — logs delta sizes for audit (repository membership
 *       reconciliation runs in {@code WorkspaceProvisioningAdapter} which has the
 *       monitor-service dependency; pure SPI dispatch arrives here without that
 *       context).</li>
 *   <li>{@link #onTenantRenamed} — full {@code handleAccountRename} body including
 *       monitor retargeting, repo renaming, and NATS subject rotation.</li>
 * </ul>
 *
 * <p>{@code install.suspended} / {@code install.unsuspended} are NOT SPI events —
 * they're state-machine transitions and stay as direct calls on the public helpers
 * ({@link #updateWorkspaceStatus}, {@link #stopNatsForInstallation},
 * {@link #startNatsForInstallation}).
 */
@Component
public class GithubLifecycleListener implements IntegrationLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(GithubLifecycleListener.class);

    private final NatsConnectionProperties natsProperties;

    private final WorkspaceRepository workspaceRepository;
    private final RepositoryToMonitorRepository repositoryToMonitorRepository;
    private final RepositoryRepository repositoryRepository;
    private final UserRepository userRepository;
    private final IdentityProviderRepository gitProviderRepository;

    private final WorkspaceSlugService workspaceSlugService;
    private final WorkspaceMembershipService workspaceMembershipService;
    /**
     * Absent when {@code hephaestus.runtime.server.enabled=false} (e.g., the webhook-server pod)
     * because {@link IntegrationNatsConsumer} is gated by {@code SERVER_PROPERTY}. The webhook
     * profile never invokes the methods that consume this; it boots dead-code but doesn't crash.
     */
    private final ObjectProvider<IntegrationNatsConsumer> natsConsumerService;

    private final GitHubAppTokenService gitHubAppTokenService;
    private final OrganizationService organizationService;
    private final ConnectionService connectionService;
    private final WorkspaceLifecycleService workspaceLifecycleService;
    private final IntegrationAttentionService attention;

    public GithubLifecycleListener(
            NatsConnectionProperties natsProperties,
            WorkspaceRepository workspaceRepository,
            RepositoryToMonitorRepository repositoryToMonitorRepository,
            RepositoryRepository repositoryRepository,
            UserRepository userRepository,
            IdentityProviderRepository gitProviderRepository,
            WorkspaceSlugService workspaceSlugService,
            WorkspaceMembershipService workspaceMembershipService,
            ObjectProvider<IntegrationNatsConsumer> natsConsumerService,
            GitHubAppTokenService gitHubAppTokenService,
            OrganizationService organizationService,
            ConnectionService connectionService,
            WorkspaceLifecycleService workspaceLifecycleService,
            IntegrationAttentionService attention) {
        this.natsProperties = natsProperties;
        this.workspaceRepository = workspaceRepository;
        this.repositoryToMonitorRepository = repositoryToMonitorRepository;
        this.repositoryRepository = repositoryRepository;
        this.userRepository = userRepository;
        this.gitProviderRepository = gitProviderRepository;
        this.workspaceSlugService = workspaceSlugService;
        this.workspaceMembershipService = workspaceMembershipService;
        this.natsConsumerService = natsConsumerService;
        this.gitHubAppTokenService = gitHubAppTokenService;
        this.organizationService = organizationService;
        this.connectionService = connectionService;
        this.workspaceLifecycleService = workspaceLifecycleService;
        this.attention = attention;
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
     * in {@code WorkspaceProvisioningAdapter}, which holds the monitor-service dependency
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
                RepositorySelection.SELECTED);
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
     * runs in {@code WorkspaceProvisioningAdapter.onRepositoriesAdded/Removed} which
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
     * retargeting, repo renaming, NATS subject rotation. Bridges
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
     * Creates or updates a workspace from a GitHub App installation.
     * <p>
     * This method handles several scenarios:
     * <ul>
     *   <li>If a workspace already exists for the installation ID, it updates the workspace</li>
     *   <li>If a PAT workspace exists for the account without a token, it promotes it to GitHub App mode</li>
     *   <li>If a PAT workspace exists with a stored token, it skips linking to preserve the PAT configuration</li>
     *   <li>If no workspace exists, it creates a new one</li>
     * </ul>
     *
     * @param installationId      the GitHub App installation ID
     * @param accountLogin        the GitHub account login (organization or user)
     * @param repositorySelection the repository selection mode (ALL or SELECTED)
     * @return the created or updated workspace, or null if workspace creation was skipped
     */
    @Transactional
    public @Nullable Workspace createOrUpdateFromInstallation(
            long installationId, String accountLogin, @Nullable RepositorySelection repositorySelection) {
        return createOrUpdateFromInstallationInternal(
                installationId, null, accountLogin, AccountKind.ORGANIZATION, null, repositorySelection);
    }

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
                installationId, accountId, accountLogin, accountKind, avatarUrl, repositorySelection);
    }

    private @Nullable Workspace createOrUpdateFromInstallationInternal(
            long installationId,
            @Nullable Long accountId,
            @Nullable String accountLogin,
            AccountKind accountKind,
            @Nullable String avatarUrl,
            @Nullable RepositorySelection repositorySelection) {
        // Check suspension before any reactivation logic: replayed NATS "created" events
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

        if (workspace == null && !isBlank(accountLogin)) {
            String resolvedAccountLogin = Objects.requireNonNull(accountLogin);
            Workspace existingByLogin = workspaceRepository
                    .findByAccountLoginIgnoreCaseForUpdate(resolvedAccountLogin)
                    .orElse(null);
            if (existingByLogin != null && existingByLogin.getStatus() == Workspace.WorkspaceStatus.PURGED) {
                log.info(
                        "Skipped installation event for purged workspace account: accountLogin={}, installationId={}",
                        LoggingUtils.sanitizeForLog(accountLogin),
                        installationId);
                return null;
            }

            // Refuse cross-vendor attach: a GitHub install must not co-tenant onto a workspace
            // whose ACTIVE Connection is GITLAB/SLACK — separate tenants that happen to share
            // the account-login string.
            if (existingByLogin != null) {
                boolean hasNonGithubActive = connectionService
                                .findActive(existingByLogin.getId(), IntegrationKind.GITLAB)
                                .isPresent()
                        || connectionService
                                .findActive(existingByLogin.getId(), IntegrationKind.SLACK)
                                .isPresent();
                if (hasNonGithubActive) {
                    log.info(
                            "Skipped GitHub App installation cross-attach, workspace has non-GITHUB ACTIVE Connection: workspaceId={}, accountLogin={}, installationId={}",
                            existingByLogin.getId(),
                            LoggingUtils.sanitizeForLog(accountLogin),
                            installationId);
                    existingByLogin = null;
                }
            }

            if (existingByLogin != null) {
                boolean isPatWorkspace = connectionService
                        .findActiveGitHubPatConfig(existingByLogin.getId())
                        .isPresent();
                // Only a PAT workspace runs on a stored token; an App row's blob is not read here.
                boolean hasPatToken = isPatWorkspace
                        && connectionService
                                .findActiveBearerToken(existingByLogin.getId(), IntegrationKind.GITHUB)
                                .map(b -> b.token() != null && !b.token().isBlank())
                                .orElse(false);

                if (hasPatToken) {
                    log.info(
                            "Skipped GitHub App installation linking, PAT workspace has stored token: workspaceId={}, accountLogin={}, installationId={}",
                            existingByLogin.getId(),
                            LoggingUtils.sanitizeForLog(accountLogin),
                            installationId);
                    return existingByLogin;
                }

                if (isPatWorkspace) {
                    log.info(
                            "Promoted PAT workspace to GitHub App, no PAT token stored: workspaceId={}, accountLogin={}, installationId={}",
                            existingByLogin.getId(),
                            LoggingUtils.sanitizeForLog(accountLogin),
                            installationId);
                } else {
                    log.info(
                            "Linked existing workspace to installation: workspaceId={}, accountLogin={}, installationId={}",
                            existingByLogin.getId(),
                            LoggingUtils.sanitizeForLog(accountLogin),
                            installationId);
                }

                workspace = existingByLogin;
            }
        }

        if (workspace == null) {
            if (isBlank(accountLogin)) {
                throw new IllegalArgumentException(
                        "Cannot create workspace from installation " + installationId + " without accountLogin.");
            }

            Long ownerUserId = syncGitHubUserForOwnership(
                    installationId, accountId, Objects.requireNonNull(accountLogin), accountKind, avatarUrl);

            if (ownerUserId == null) {
                // Likely an old or deleted installation whose owner user can no longer be resolved.
                log.warn(
                        "Skipped workspace creation, cannot sync owner user: installationId={}, accountLogin={}",
                        installationId,
                        LoggingUtils.sanitizeForLog(accountLogin));
                return null;
            }

            AccountType wsAccountType = accountKind == AccountKind.ORGANIZATION ? AccountType.ORG : AccountType.USER;

            String desiredSlug = Objects.requireNonNull(workspaceSlugService.normalize(accountLogin));
            String availableSlug =
                    workspaceSlugService.allocate(desiredSlug, "install-" + installationId + "-" + accountLogin);

            // Do NOT redirect the desired slug to the allocated slug: the desired slug may
            // belong to another workspace, and redirecting would leak or hijack it. Callers
            // must surface the allocated slug to the user instead.
            workspace = createWorkspace(availableSlug, accountLogin, accountLogin, wsAccountType, ownerUserId);
            log.info(
                    "Created workspace from installation: workspaceSlug={}, installationId={}, ownerUserId={}, requestedSlug={}",
                    LoggingUtils.sanitizeForLog(workspace.getWorkspaceSlug()),
                    installationId,
                    ownerUserId,
                    LoggingUtils.sanitizeForLog(desiredSlug));
        }

        if (!isBlank(accountLogin)) {
            workspace.setAccountLogin(Objects.requireNonNull(accountLogin));
        }

        if (repositorySelection != null) {
            workspace.setRepositorySelection(repositorySelection);
        }

        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE) {
            log.info(
                    "Reactivated workspace from installation: workspaceId={}, previousStatus={}, installationId={}",
                    workspace.getId(),
                    workspace.getStatus(),
                    installationId);
            workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        }

        // Organization must exist before repositories are created so they get organization_id set.
        if (accountKind == AccountKind.ORGANIZATION && accountId != null) {
            Long providerId = Objects.requireNonNull(gitProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                    .orElseThrow(() -> new IllegalStateException("IdentityProvider for GitHub not found"))
                    .getId());
            Organization org =
                    organizationService.upsertIdentity(accountId, Objects.requireNonNull(accountLogin), providerId);
            workspace.setOrganization(org);
            log.debug(
                    "Linked organization to workspace: orgId={}, orgLogin={}, workspaceId={}",
                    org.getId(),
                    LoggingUtils.sanitizeForLog(org.getLogin()),
                    workspace.getId());
        }

        Workspace saved = workspaceRepository.save(workspace);

        // upsertGitHubAppConnection retires any non-matching GITHUB connection on this workspace
        // (PAT or stale App) and reuses or creates the App row. Correlation id is stable per
        // installation so webhook redelivery is idempotent.
        connectionService.upsertGitHubAppConnection(
                saved, installationId, accountLogin, "install-bind-" + installationId);

        return saved;
    }

    /**
     * Used when an installation is deleted, to stop the NATS consumer before monitors are removed.
     *
     * @param installationId the GitHub App installation ID
     */
    public void stopNatsForInstallation(long installationId) {
        workspaceRepository.findByInstallationId(installationId).ifPresent(workspace -> {
            if (shouldUseNats(workspace)) {
                natsConsumerService.ifAvailable(svc -> svc.stopConsumingScope(workspace.getId()));
            }
        });
    }

    /**
     * Used when an installation is unsuspended, to resume webhook processing.
     *
     * @param installationId the GitHub App installation ID
     */
    public void startNatsForInstallation(long installationId) {
        workspaceRepository.findByInstallationId(installationId).ifPresent(workspace -> {
            if (shouldUseNats(workspace)) {
                natsConsumerService.ifAvailable(svc -> svc.startConsumingScope(workspace.getId()));
            }
        });
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
                .ifPresent(connection -> connectionService.transition(
                        connection,
                        new ConnectionService.TransitionRequest(
                                IntegrationState.UNINSTALLED,
                                "PROVIDER_UNINSTALLED",
                                "PROVIDER",
                                IntegrationKind.GITHUB.name(),
                                "provider-uninstall-github-" + installationId,
                                "GitHub reported the installation was deleted")));
        Workspace purged = workspaceLifecycleService.purgeWorkspace(workspace.getWorkspaceSlug());
        log.info(
                "Purged workspace after vendor-side uninstall: installationId={}, workspaceId={}",
                installationId,
                purged.getId());
        return Optional.of(purged);
    }

    /**
     * Update workspace status for a given installation if the status differs.
     *
     * <p>{@code PURGED} is rejected: it is not a status flip but a data-erasure operation, and
     * routing it through here silently skipped every {@code WorkspacePurgeContributor}. Use
     * {@link #purgeWorkspaceForInstallation(long)}.
     *
     * @param installationId the GitHub App installation ID
     * @param status         the new workspace status
     * @return the updated workspace, or empty if no workspace exists for the installation
     * @throws IllegalArgumentException if {@code status} is {@code PURGED}
     */
    @Transactional
    public Optional<Workspace> updateWorkspaceStatus(long installationId, Workspace.WorkspaceStatus status) {
        if (status == Workspace.WorkspaceStatus.PURGED) {
            throw new IllegalArgumentException(
                    "PURGED must go through purgeWorkspaceForInstallation so the purge contributors run: installationId="
                            + installationId);
        }

        var workspaceOpt = workspaceRepository.findByInstallationIdForUpdate(installationId);
        if (workspaceOpt.isEmpty() || status == null) {
            return workspaceOpt;
        }

        Workspace workspace = workspaceOpt.get();
        if (workspace.getStatus() == Workspace.WorkspaceStatus.PURGED) {
            return Optional.of(workspace);
        }
        if (status != workspace.getStatus()) {
            workspace.setStatus(status);
            workspace = workspaceRepository.save(workspace);
        }

        if (status == Workspace.WorkspaceStatus.SUSPENDED || status == Workspace.WorkspaceStatus.ACTIVE) {
            long workspaceId = workspace.getId();
            connectionService
                    .findActive(workspaceId, IntegrationKind.GITHUB)
                    .ifPresent(connection -> attention.report(
                            connection.getId(),
                            workspaceId,
                            IntegrationAttentionChangedEvent.Problem.PROVIDER_SUSPENDED,
                            status == Workspace.WorkspaceStatus.ACTIVE));
        }
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
     * renames tracked repositories, and rotates the NATS organization consumer.
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
                                rotateOrganizationConsumer(workspace, oldLogin, newLogin);
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
        repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()).forEach(monitor -> {
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
            monitor.setNameWithOwner(newLogin + suffix);
            repositoryToMonitorRepository.save(monitor);
        });

        if (shouldUseNats(workspace)) {
            natsConsumerService.ifAvailable(svc -> svc.updateScopeConsumer(workspace.getId()));
        }
    }

    private void renameTrackedRepositories(String oldLogin, String newLogin) {
        if (isBlank(oldLogin) || isBlank(newLogin) || oldLogin.equalsIgnoreCase(newLogin)) {
            return;
        }

        String prefix = oldLogin + "/";
        var repositories = repositoryRepository.findByNameWithOwnerStartingWithIgnoreCase(prefix);
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

    private void rotateOrganizationConsumer(Workspace workspace, String oldLogin, String newLogin) {
        if (!natsProperties.enabled()
                || workspace == null
                || isBlank(oldLogin)
                || isBlank(newLogin)
                || oldLogin.equalsIgnoreCase(newLogin)) {
            return;
        }

        natsConsumerService.ifAvailable(svc -> svc.updateScopeConsumer(workspace.getId()));
    }

    private Workspace createWorkspace(
            String slug, String displayName, String accountLogin, AccountType accountType, Long ownerUserId) {
        workspaceSlugService.validate(slug);

        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(displayName);
        workspace.setIsPubliclyViewable(false);
        workspace.setAccountLogin(accountLogin);
        workspace.setAccountType(accountType);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);

        Workspace saved = workspaceRepository.save(workspace);
        workspaceMembershipService.createMembership(saved, ownerUserId, WorkspaceMembership.WorkspaceRole.OWNER);
        return saved;
    }

    /**
     * Looks up an existing user by login, or creates one from installation webhook account info.
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
        var existingUser = userRepository.findByLogin(accountLogin);
        if (existingUser.isPresent()) {
            log.info(
                    "Found existing user for workspace ownership: userLogin={}, userId={}",
                    LoggingUtils.sanitizeForLog(accountLogin),
                    existingUser.get().getId());
            return existingUser.get().getId();
        }

        // Three-step upsert (lock, free conflicts, insert) avoids uk_user_login_lower
        // violations under concurrent installs.
        if (accountId != null) {
            String htmlUrl = "https://github.com/" + accountLogin;
            String typeStr =
                    accountKind == AccountKind.ORGANIZATION ? User.Type.ORGANIZATION.name() : User.Type.USER.name();

            IdentityProvider provider = gitProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                    .orElseThrow(() -> new IllegalStateException("IdentityProvider for GitHub not found"));
            Long providerId = Objects.requireNonNull(provider.getId());

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
                    "Upserted user for workspace ownership: userLogin={}, userId={}, userType={}, installationId={}",
                    LoggingUtils.sanitizeForLog(accountLogin),
                    accountId,
                    typeStr,
                    installationId);
            // upsertUser is a native INSERT that doesn't return the generated id; re-fetch for the PK.
            return userRepository
                    .findByLogin(accountLogin)
                    .map(User::getId)
                    .orElseThrow(() -> new IllegalStateException("User not found after upsert: login=" + accountLogin));
        }

        log.warn(
                "Skipped user creation: reason=missingAccountId, userLogin={}, installationId={}",
                LoggingUtils.sanitizeForLog(accountLogin),
                installationId);
        return null;
    }

    private boolean shouldUseNats(@Nullable Workspace workspace) {
        return natsProperties.enabled() && workspace != null;
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
