package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener.AccountKind;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceProperties;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Decides whether a GitHub App installation without a workspace may get one now, and records it for an instance
 * admin when it may not. Under {@code ADMIN_ONLY} only an instance admin's adoption creates a workspace.
 */
@Service
@ConditionalOnServerRole
public class GitHubInstallationRequests {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationRequests.class);
    static final String GITHUB_SERVER_URL = "https://github.com";

    private final GitHubInstallationRequestRepository requests;
    private final WorkspaceProperties workspaceProperties;
    private final GitProviderRegistry providers;
    private final PersonDataWriteFence fence;
    private final WorkspaceRepository workspaces;

    public GitHubInstallationRequests(
            GitHubInstallationRequestRepository requests,
            WorkspaceProperties workspaceProperties,
            GitProviderRegistry providers,
            PersonDataWriteFence fence,
            WorkspaceRepository workspaces) {
        this.requests = requests;
        this.workspaceProperties = workspaceProperties;
        this.providers = providers;
        this.fence = fence;
        this.workspaces = workspaces;
    }

    /** Whether a new installation waits for an instance admin instead of creating its workspace. */
    public boolean requiresApproval() {
        return workspaceProperties.creationPolicy() == WorkspaceProperties.CreationPolicy.ADMIN_ONLY;
    }

    /** An adopted installation got its one workspace from the admin; an event never creates another. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isAdopted(long installationId) {
        return requests.findStatus(installationId)
                .filter(status -> status == GitHubInstallationRequestStatus.ADOPTED)
                .isPresent();
    }

    /**
     * Records the installation for an instance admin, once. A personal account's login is personal data, so a
     * person whose data Hephaestus no longer processes is not recorded.
     *
     * @return whether the installation is recorded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordPending(long installationId, @Nullable Long accountId, String accountLogin, AccountKind kind) {
        return record(installationId, accountId, accountLogin, kind);
    }

    private boolean record(long installationId, @Nullable Long accountId, String accountLogin, AccountKind kind) {
        GitHubInstallationAccountKind accountKind = GitHubInstallationAccountKind.of(kind);
        if (!admit(accountId, accountKind)) {
            log.info("Skipped installation record: reason=accountNotAdmitted, installationId={}", installationId);
            return false;
        }
        requests.recordPending(installationId, accountId, accountLogin, accountKind.name());
        return true;
    }

    /**
     * Holds the person-data write fence for a personal account until the caller's transaction ends. Hold it before
     * the record is written or locked, in the order that erasure takes the same locks.
     */
    boolean admit(@Nullable Long accountId, GitHubInstallationAccountKind kind) {
        if (kind == GitHubInstallationAccountKind.ORGANIZATION) {
            return true;
        }
        if (accountId == null) {
            return false;
        }
        long providerId = providers
                .findProviderId("GITHUB", GITHUB_SERVER_URL)
                .orElseThrow(() -> new IllegalStateException("GitHub is not set up on this instance."));
        return fence.holdForWrite(List.of(new PersonIdentity(providerId, accountId.toString(), null)));
    }

    /**
     * The allowed-organizations filter limits what Hephaestus syncs, not what an instance admin can see: an installation
     * that it filters out and that has no workspace still waits for the admin's decision.
     */
    @Transactional
    public void recordFiltered(long installationId, @Nullable Long accountId, String accountLogin, AccountKind kind) {
        if (requiresApproval()
                && workspaces.findByInstallationId(installationId).isEmpty()) {
            record(installationId, accountId, accountLogin, kind);
        }
    }

    static ResponseStatusException accountNotProcessed() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "Hephaestus no longer processes the personal data of this GitHub account.");
    }

    /**
     * Locks the installation's record for an uninstall, before the uninstall looks for its workspace. An adoption holds
     * the same lock while it creates the workspace, so an uninstall either finds that workspace or finds no record to
     * adopt.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForUninstall(long installationId) {
        requests.findForUpdate(installationId);
    }

    /** The decision on an installation, if Hephaestus recorded it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<GitHubInstallationRequestStatus> status(long installationId) {
        return requests.findStatus(installationId);
    }

    /** An uninstalled installation that no admin adopted leaves nothing to decide. */
    @Transactional
    public void forgetUnadopted(long installationId) {
        requests.deleteUnadopted(installationId);
    }
}
