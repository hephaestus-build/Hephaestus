package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubUserAuthorizationException;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The person who set up an installation on GitHub returns with GitHub's one-time code. The code proves that their
 * GitHub account can access the installation, and that account must be the one linked to the signed-in Hephaestus
 * account. The claim never creates a workspace: under {@code ADMIN_ONLY} it records the installation for an instance
 * admin, who adopts it through the instance-admin API with its sign-in assurance.
 */
@Service
@ConditionalOnServerRole
public class GitHubInstallationClaimService {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationClaimService.class);

    private final GitHubAppUserAuthorizationClient userAuthorization;
    private final AccountIdentityQuery identities;
    private final GitProviderRegistry providers;
    private final GitHubInstallationReader github;
    private final GitHubInstallationRequests gate;
    private final WorkspaceRepository workspaces;
    private final WorkspaceScopeFilter scopeFilter;
    private final TransactionTemplate transactions;

    GitHubInstallationClaimService(
            GitHubAppUserAuthorizationClient userAuthorization,
            AccountIdentityQuery identities,
            GitProviderRegistry providers,
            GitHubInstallationReader github,
            GitHubInstallationRequests gate,
            WorkspaceRepository workspaces,
            WorkspaceScopeFilter scopeFilter,
            PlatformTransactionManager transactionManager) {
        this.userAuthorization = userAuthorization;
        this.identities = identities;
        this.providers = providers;
        this.github = github;
        this.gate = gate;
        this.workspaces = workspaces;
        this.scopeFilter = scopeFilter;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public GitHubInstallationClaimDTO claim(long installationId, String code, long callerAccountId) {
        if (!userAuthorization.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub sign-in for the app is not set up on this instance. Ask your instance operator to set it up.");
        }
        long githubUserId;
        try {
            String userToken = userAuthorization.exchangeCode(code);
            if (userAuthorization
                    .findAccessibleInstallation(userToken, installationId)
                    .isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Your GitHub account cannot access this installation of the app.");
            }
            githubUserId = userAuthorization.userId(userToken);
        } catch (GitHubUserAuthorizationException e) {
            log.warn(
                    "GitHub installation claim not verified: installationId={}, error={}",
                    installationId,
                    e.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "GitHub could not confirm your access to this installation. Install the app again.",
                    e);
        }
        long providerId = providers
                .findProviderId("GITHUB", GitHubInstallationRequests.GITHUB_SERVER_URL)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "GitHub is not set up on this instance."));
        Optional<Long> linkedAccount = identities.resolveActiveAccountId(providerId, Long.toString(githubUserId), null);
        if (linkedAccount.isEmpty() || linkedAccount.get() != callerAccountId) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Sign in to Hephaestus with the GitHub account that installed the app.");
        }

        GitHubInstallationReader.Installation live = github.read(installationId);
        return Objects.requireNonNull(transactions.execute(status -> {
            Optional<Workspace> workspace = workspaces
                    .findByInstallationId(installationId)
                    .filter(w -> w.getStatus() != Workspace.WorkspaceStatus.PURGED);
            if (workspace.isPresent()) {
                return new GitHubInstallationClaimDTO(
                        GitHubInstallationClaimDTO.Outcome.READY,
                        workspace.get().getWorkspaceSlug());
            }
            if (gate.isAdopted(installationId)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The workspace of this installation was deleted. Uninstall the app on GitHub, then install it again.");
            }
            String login = live.account().login();
            if (!gate.requiresApproval()) {
                if (!scopeFilter.isOrganizationAllowed(login)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "This instance does not sync the GitHub account " + login + ".");
                }
                return new GitHubInstallationClaimDTO(GitHubInstallationClaimDTO.Outcome.SETTING_UP, null);
            }
            if (!gate.recordPending(
                    installationId, live.account().id(), login, live.account().kind())) {
                throw GitHubInstallationRequests.accountNotProcessed();
            }
            return new GitHubInstallationClaimDTO(GitHubInstallationClaimDTO.Outcome.AWAITING_APPROVAL, null);
        }));
    }
}
