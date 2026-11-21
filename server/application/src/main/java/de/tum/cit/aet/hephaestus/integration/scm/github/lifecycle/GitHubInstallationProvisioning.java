package de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle;

import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption.GitHubInstallationAccountKind;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption.GitHubInstallationRequests;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLocks;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRoots;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, links or adopts the workspace of a GitHub App installation, for {@code GitHubWorkspaceProvisioningAdapter}
 * webhook events, {@code GitHubInstallationReconciler} reconciliation and an instance admin's adoption. For a new
 * workspace that no admin adopted, it upserts the installation account, an organization or a user, and makes a
 * personal account the owner. It also links the organization and binds the installation's GitHub App connection. {@link GitHubInstallationLifecycle} owns the later uninstall, suspension,
 * repository-selection and rename changes.
 */
@Component
@ConditionalOnServerRole
public class GitHubInstallationProvisioning {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationProvisioning.class);

    private final WorkspaceRoots workspaces;

    private final WorkspaceLocks workspaceLocks;
    private final UserRepository userRepository;
    private final IdentityProviderLookup identityProviders;

    private final WorkspaceSlugService workspaceSlugService;
    private final PeopleRegistry people;

    private final GitHubAppTokenService gitHubAppTokenService;
    private final OrganizationService organizationService;
    private final ConnectionService connectionService;
    private final GitHubInstallationRequests installationRequests;

    public GitHubInstallationProvisioning(
            WorkspaceRoots workspaces,
            WorkspaceLocks workspaceLocks,
            UserRepository userRepository,
            IdentityProviderLookup identityProviders,
            WorkspaceSlugService workspaceSlugService,
            PeopleRegistry people,
            GitHubAppTokenService gitHubAppTokenService,
            OrganizationService organizationService,
            ConnectionService connectionService,
            GitHubInstallationRequests installationRequests) {
        this.workspaces = workspaces;
        this.workspaceLocks = workspaceLocks;
        this.userRepository = userRepository;
        this.identityProviders = identityProviders;
        this.workspaceSlugService = workspaceSlugService;
        this.people = people;
        this.gitHubAppTokenService = gitHubAppTokenService;
        this.organizationService = organizationService;
        this.connectionService = connectionService;
        this.installationRequests = installationRequests;
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
            GitHubInstallationAccountKind accountKind,
            @Nullable String avatarUrl,
            @Nullable RepositorySelection repositorySelection) {
        return createOrUpdateFromInstallationInternal(
                installationId, accountId, accountLogin, accountKind, avatarUrl, repositorySelection, null);
    }

    /** The account that GitHub confirmed an installation is on. */
    public record InstallationAccount(
            long id,
            String login,
            GitHubInstallationAccountKind kind,
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
            GitHubInstallationAccountKind accountKind,
            @Nullable String avatarUrl,
            @Nullable RepositorySelection repositorySelection,
            @Nullable Long adminAccountId) {
        // Check suspension before any reactivation logic: replayed provider inbox "created" events
        // must not reactivate a suspended workspace and re-trigger failed repository syncs.
        if (gitHubAppTokenService.isInstallationMarkedSuspended(installationId)) {
            log.info("Skipped workspace reactivation: reason=installationSuspended, installationId={}", installationId);
            return null;
        }

        Workspace workspace = workspaceLocks.lockByInstallation(installationId).orElse(null);
        if (workspace != null && workspace.getStatus() == Workspace.WorkspaceStatus.PURGED) {
            log.info("Skipped installation event for purged workspace: installationId={}", installationId);
            return null;
        }

        // A login is not unique and a personal account's workspace cannot prove its owner, so only an
        // organization's immutable id finds the workspace that the installation joins.
        if (workspace == null && accountKind == GitHubInstallationAccountKind.ORGANIZATION && accountId != null) {
            Workspace existing = organizationService
                    .findId(accountId, githubProviderId())
                    .flatMap(workspaceLocks::lockByOrganization)
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

            AccountType wsAccountType =
                    accountKind == GitHubInstallationAccountKind.ORGANIZATION ? AccountType.ORG : AccountType.USER;

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
            workspaces.renameAccount(workspace.getId(), Objects.requireNonNull(accountLogin));
        }

        if (repositorySelection != null) {
            workspaces.selectRepositories(workspace.getId(), repositorySelection);
        }

        Workspace.WorkspaceStatus previousStatus = workspace.getStatus();
        if (workspaces.resume(workspace.getId())) {
            log.info(
                    "Reactivated workspace from installation: workspaceId={}, previousStatus={}, installationId={}",
                    workspace.getId(),
                    previousStatus,
                    installationId);
        }

        // Organization must exist before repositories are created so they get organization_id set.
        if (accountKind == GitHubInstallationAccountKind.ORGANIZATION && accountId != null) {
            Organization org = organizationService.upsertIdentity(
                    accountId, Objects.requireNonNull(accountLogin), githubProviderId());
            workspaces.linkOrganization(workspace.getId(), org.getId());
            log.debug("Linked organization to workspace: orgId={}, workspaceId={}", org.getId(), workspace.getId());
        }

        // upsertGitHubAppConnection retires any non-matching GITHUB connection on this workspace
        // (PAT or stale App) and reuses or creates the App row. Correlation id is stable per
        // installation so webhook redelivery is idempotent.
        connectionService.upsertGitHubAppConnection(
                workspace,
                gitHubAppTokenService.getConfiguredAppId(),
                installationId,
                accountLogin,
                "install-bind-" + installationId);

        return workspace;
    }

    private Workspace createWorkspace(
            String slug,
            String displayName,
            String accountLogin,
            AccountType accountType,
            @Nullable Long ownerAccountId) {
        workspaceSlugService.validate(slug);

        return workspaces.create(slug, displayName, accountLogin, accountType, ownerAccountId);
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
            GitHubInstallationAccountKind accountKind,
            @Nullable String avatarUrl) {
        // Three-step upsert (lock, free conflicts, insert) avoids uk_user_login_lower
        // violations under concurrent installs.
        if (accountId != null) {
            String htmlUrl = "https://github.com/" + accountLogin;
            String typeStr = accountKind == GitHubInstallationAccountKind.ORGANIZATION
                    ? User.Type.ORGANIZATION.name()
                    : User.Type.USER.name();

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
}
