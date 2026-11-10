package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitHubInstallationDecisionAudit;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubWorkspaceDataSyncTrigger;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * An instance admin's adopt and decline decisions on a GitHub App installation. Adopt reads the installation from
 * GitHub before it opens a database transaction, then checks it again against the locked record.
 */
@Service
@ConditionalOnServerRole
public class GitHubInstallationAdoptionService {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationAdoptionService.class);

    private final GitHubInstallationRequestRepository requests;
    private final GitHubInstallationRequests gate;
    private final GitHubInstallationReader github;
    private final GitHubLifecycleListener lifecycle;
    private final GitHubInstallationDecisionAudit audit;
    private final GitHubWorkspaceDataSyncTrigger dataSync;
    private final AsyncTaskExecutor monitoringExecutor;
    private final TransactionTemplate transactions;

    GitHubInstallationAdoptionService(
            GitHubInstallationRequestRepository requests,
            GitHubInstallationRequests gate,
            GitHubInstallationReader github,
            GitHubLifecycleListener lifecycle,
            GitHubInstallationDecisionAudit audit,
            GitHubWorkspaceDataSyncTrigger dataSync,
            @Qualifier("monitoringExecutor") AsyncTaskExecutor monitoringExecutor,
            PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.gate = gate;
        this.github = github;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.dataSync = dataSync;
        this.monitoringExecutor = monitoringExecutor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<GitHubInstallationRequestDTO> list() {
        return requests.findAllViews();
    }

    /**
     * Creates the installation's workspace with the admin as its owner, or joins the workspace it already has. A
     * declined installation can be adopted later; an adopted one is decided.
     */
    public GitHubInstallationRequestDTO adopt(long installationId, long adminAccountId) {
        if (requests.findStatus(installationId).isEmpty()) {
            throw notFound();
        }
        GitHubInstallationReader.Installation live = github.read(installationId);
        long accountId = live.account().id();
        Workspace workspace = Objects.requireNonNull(transactions.execute(status -> {
            if (!gate.admit(accountId, live.kind())) {
                throw GitHubInstallationRequests.accountNotProcessed();
            }
            GitHubInstallationRequest request =
                    requests.findForUpdate(installationId).orElseThrow(GitHubInstallationAdoptionService::notFound);
            if (request.getStatus() == GitHubInstallationRequestStatus.ADOPTED) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "An instance admin has already adopted this installation.");
            }
            Long recordedAccountId = request.getAccountId();
            if (request.getAccountKind() != live.kind()
                    || (recordedAccountId != null && recordedAccountId != accountId)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This installation is now on another GitHub account. Ask the account owner to install the app again.");
            }
            Workspace adopted = lifecycle.adoptInstallation(
                    installationId, live.account(), live.repositorySelection(), adminAccountId);
            if (adopted == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Hephaestus cannot create a workspace for this installation. A deleted workspace used its GitHub account, or the installation is suspended.");
            }
            request.adopt(adminAccountId, accountId, live.account().login(), adopted.getId(), Instant.now());
            if (!audit.adopted(adminAccountId, installationId, adopted.getId())) {
                throw new IllegalStateException("The adoption was not audited, so it was not saved");
            }
            return adopted;
        }));
        log.info(
                "Adopted GitHub App installation: installationId={}, workspaceId={}, adminAccountId={}",
                installationId,
                workspace.getId(),
                adminAccountId);
        // The repositories come from GitHub, so they are read after the workspace commits, as at startup.
        long workspaceId = workspace.getId();
        monitoringExecutor.execute(() -> dataSync.syncOnStartup(workspaceId, true));
        return view(installationId);
    }

    /** Declines a pending installation. It stays listed, so an admin can still adopt it. */
    public GitHubInstallationRequestDTO decline(long installationId, long adminAccountId) {
        transactions.executeWithoutResult(status -> {
            GitHubInstallationRequest request =
                    requests.findForUpdate(installationId).orElseThrow(GitHubInstallationAdoptionService::notFound);
            if (request.getStatus() != GitHubInstallationRequestStatus.PENDING) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Only an installation that waits for a decision can be declined.");
            }
            request.decline(adminAccountId, Instant.now());
            if (!audit.declined(adminAccountId, installationId)) {
                throw new IllegalStateException("The decline was not audited, so it was not saved");
            }
        });
        log.info(
                "Declined GitHub App installation: installationId={}, adminAccountId={}",
                installationId,
                adminAccountId);
        return view(installationId);
    }

    private GitHubInstallationRequestDTO view(long installationId) {
        return requests.findView(installationId).orElseThrow(GitHubInstallationAdoptionService::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Hephaestus has no record of this installation.");
    }
}
