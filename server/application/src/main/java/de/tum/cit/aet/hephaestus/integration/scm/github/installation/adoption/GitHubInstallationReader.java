package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception.InstallationNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService.AppInstallation;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener.InstallationAccount;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * What GitHub says about an installation, read with the App's own credentials. The id that a person or a record
 * brings is only a claim; adopt and claim decide on this read. It runs before any database transaction opens.
 */
@Component
@ConditionalOnServerRole
class GitHubInstallationReader {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationReader.class);

    private final GitHubAppTokenService appTokens;

    GitHubInstallationReader(GitHubAppTokenService appTokens) {
        this.appTokens = appTokens;
    }

    record Installation(
            InstallationAccount account, GitHubInstallationAccountKind kind, RepositorySelection repositorySelection) {}

    /** Refuses, with the reason, an installation that is gone, suspended, or on no user or organization account. */
    Installation read(long installationId) {
        AppInstallation installation;
        try {
            installation = appTokens.installation(installationId);
        } catch (InstallationNotFoundException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "GitHub no longer has this installation. It was uninstalled.", e);
        } catch (UncheckedIOException | IllegalStateException e) {
            log.warn(
                    "GitHub installation read failed: installationId={}, error={}",
                    installationId,
                    e.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "We could not read the installation from GitHub. Try again.", e);
        }
        if (installation.suspendedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This installation is suspended on GitHub. Unsuspend it, then try again.");
        }
        AppInstallation.Account account = installation.account();
        GitHubInstallationAccountKind kind = GitHubInstallationAccountKind.ofTargetType(installation.targetType())
                .orElse(null);
        String login = account == null ? null : account.login();
        if (installation.id() != installationId
                || account == null
                || login == null
                || login.isBlank()
                || kind == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Hephaestus supports installations on a GitHub user or organization account only.");
        }
        return new Installation(
                new InstallationAccount(account.id(), login, kind.accountKind(), account.avatarUrl()),
                kind,
                "all".equals(installation.repositorySelection())
                        ? RepositorySelection.ALL
                        : RepositorySelection.SELECTED);
    }
}
