package de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationAttentionService;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLocks;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRoots;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the uninstall, suspension, repository-selection and account-rename mutations of a GitHub App installation's
 * workspace for {@code GitHubWorkspaceProvisioningAdapter} webhook events and {@code GitHubInstallationReconciler}
 * reconciliation. {@link GitHubInstallationProvisioning} creates, links or adopts the workspace.
 */
@Component
@ConditionalOnServerRole
public class GitHubInstallationLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationLifecycle.class);

    private final WorkspaceRoots workspaces;

    private final WorkspaceLocks workspaceLocks;
    private final WorkspaceRepositoryMonitors monitors;
    private final RepositoryMonitorCommands monitorCommands;
    private final RepositoryRepository repositoryRepository;
    private final IdentityProviderLookup identityProviders;

    private final ConnectionService connectionService;
    private final WorkspaceLifecycleService workspaceLifecycleService;
    private final IntegrationAttentionService attention;
    private final ScmWorkspaceContentEraser contentEraser;
    private final WorkspaceSummaryQuery workspaceStates;

    public GitHubInstallationLifecycle(
            WorkspaceRoots workspaces,
            WorkspaceLocks workspaceLocks,
            WorkspaceRepositoryMonitors monitors,
            RepositoryMonitorCommands monitorCommands,
            RepositoryRepository repositoryRepository,
            IdentityProviderLookup identityProviders,
            ConnectionService connectionService,
            WorkspaceLifecycleService workspaceLifecycleService,
            IntegrationAttentionService attention,
            ScmWorkspaceContentEraser contentEraser,
            WorkspaceSummaryQuery workspaceStates) {
        this.workspaces = workspaces;
        this.workspaceLocks = workspaceLocks;
        this.monitors = monitors;
        this.monitorCommands = monitorCommands;
        this.repositoryRepository = repositoryRepository;
        this.identityProviders = identityProviders;
        this.connectionService = connectionService;
        this.workspaceLifecycleService = workspaceLifecycleService;
        this.attention = attention;
        this.contentEraser = contentEraser;
        this.workspaceStates = workspaceStates;
    }

    /**
     * Vendor-side uninstall ({@code installation.deleted}) — runs the purge an admin-initiated workspace
     * deletion runs, via {@link WorkspaceLifecycleService#purgeWorkspace(String)}, inside the consumer's transaction.
     * The inbox handler runs {@link #removeAtProvidersBeforePurge(long)} first, so the removals at Slack and Outline
     * happen before the purge erases their credentials.
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
        var workspaceOpt = workspaceLocks.lockByInstallation(installationId);
        if (workspaceOpt.isEmpty()) {
            log.warn(
                    "Skipped installation purge: reason=noWorkspaceForInstallation, installationId={}", installationId);
            return Optional.empty();
        }

        Workspace workspace = workspaceOpt.get();
        recordUninstall(workspace.getId(), installationId);
        Workspace purged = workspaceLifecycleService.purgeWorkspace(workspace.getWorkspaceSlug());
        log.info(
                "Purged workspace after vendor-side uninstall: installationId={}, workspaceId={}",
                installationId,
                purged.getId());
        return Optional.of(purged);
    }

    /**
     * The provider half of {@link #purgeWorkspaceForInstallation(long)}, which the inbox handler runs first with no
     * transaction open: the purge's transaction cannot call providers, and it erases the credentials those calls need.
     * Once the purge guards admit the removal, it records GitHub's uninstall on the connection, then the workspace's
     * other connections, such as Slack and Outline, remove what they set up at their providers. A redelivery runs it
     * again, and each step skips what is done.
     */
    public void removeAtProvidersBeforePurge(long installationId) {
        workspaceStates
                .findStateByInstallationId(installationId)
                .filter(state -> !Workspace.WorkspaceStatus.PURGED.name().equals(state.status()))
                .ifPresent(state -> workspaceLifecycleService.removeAtProvidersBeforePurge(
                        state.id(), () -> recordUninstall(state.id(), installationId)));
    }

    /** Disconnects the installation's connection and erases its mirror; a connection already disconnected stays. */
    private void recordUninstall(long workspaceId, long installationId) {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.GITHUB, workspaceId, Long.toString(installationId));
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
                        () -> contentEraser.eraseWorkspaceScmMirror(workspaceId)));
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
        var workspaceOpt = workspaceLocks.lockByInstallation(installationId);
        if (workspaceOpt.isEmpty()) {
            return workspaceOpt;
        }

        Workspace workspace = workspaceOpt.get();
        if (workspace.getStatus() == Workspace.WorkspaceStatus.PURGED) {
            return Optional.of(workspace);
        }
        if (active) workspaces.resume(workspace.getId());
        else workspaces.suspend(workspace.getId());

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
        var workspaceOpt = workspaceLocks
                .lockByInstallation(installationId)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE);
        if (workspaceOpt.isEmpty() || selection == null) {
            return workspaceOpt;
        }

        Workspace workspace = workspaceOpt.get();
        workspaces.selectRepositories(workspace.getId(), selection);
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
        if (isBlank(newLogin)) {
            log.warn("Skipped account rename: reason=missingTargetLogin, installationId={}", installationId);
            return;
        }

        workspaceLocks
                .lockByInstallation(installationId)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .ifPresentOrElse(
                        workspace -> {
                            String oldLogin = !isBlank(previousLogin) ? previousLogin : workspace.getAccountLogin();
                            workspaces.renameAccount(workspace.getId(), newLogin);
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
        var existing = monitors.monitors(workspace.getId());
        existing.forEach(monitor -> {
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
            if (existing.stream()
                    .anyMatch(other ->
                            !other.getId().equals(monitor.getId()) && target.equals(other.getNameWithOwner()))) {
                log.warn(
                        "Skipped repository monitor rename: reason=nameHeldByAnotherMonitor, workspaceId={}, monitorId={}",
                        workspace.getId(),
                        monitor.getId());
                return;
            }
            monitorCommands.rename(workspace.getId(), monitor.getId(), target);
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

    private boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
