package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure.Reason;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** One connection job per target: a provider outage never serializes unrelated organizations behind it. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessSyncAdapter implements IntegrationSyncRunner {
    private final GitHubAccessReconciliation reconciliation;
    private final GitHubAccessClient github;
    private final WorkspaceRepository workspaces;

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB_ACCESS;
    }

    @Override
    @SuppressWarnings("PMD.PreserveStackTrace")
    public void reconcile(IntegrationRef ref, SyncExecutionHandle handle, SyncJobType type) {
        var previous = WorkspaceContextHolder.getContext();
        long connectionId = Objects.requireNonNull(ref.connectionId());
        try {
            var workspace = workspaces.findById(ref.workspaceId()).orElseThrow();
            WorkspaceContextHolder.setContext(WorkspaceContext.fromWorkspace(workspace, Set.of(), null));
            var input = reconciliation.snapshot(ref.workspaceId(), connectionId, type == SyncJobType.INITIAL);
            reconciliation.retainDepartures(input);
            var credential = reconciliation.installation(input);
            var authorization = Objects.requireNonNull(input.authorization());
            var session = github.open(
                            credential.installationId(),
                            Objects.requireNonNull(input.organizationId()),
                            Objects.requireNonNull(input.scopeId()))
                    .withProgress(handle);
            github.requireOrganizationOwner(session, authorization.githubUserId());
            if (input.preview()) {
                reconciliation.publishPreview(input, github.inventory(session, handle));
                return;
            }
            int processed = 0;
            boolean warnings = false;
            for (var person : input.people()) {
                handle.progress(
                        processed,
                        input.people().size(),
                        SyncProgress.of(
                                SyncPhase.TEAMS,
                                "Reconciling linked identities and confirming pending GitHub access changes"));
                var observed = github.inspect(session, person.githubUserId());
                Long actionId = reconciliation.prepare(input, person, observed);
                if (actionId != null) {
                    try {
                        reconciliation.execute(input, person, actionId, session);
                    } catch (GitHubAccessFailure failure) {
                        reconciliation.failedAction(input, actionId, failure);
                        warnings = true;
                        if (failure.reason() == Reason.RATE_LIMITED
                                || failure.reason() == Reason.UNAVAILABLE
                                || failure.reason() == Reason.CREDENTIALS
                                || failure.reason() == Reason.CANCELLED) throw failure;
                    }
                }
                processed++;
            }
            if (reconciliation.finish(input) || warnings) handle.reportWarnings();
        } catch (RuntimeException error) {
            var failure = error instanceof GitHubAccessFailure known
                    ? known
                    : new GitHubAccessFailure(
                            Reason.INCOMPLETE,
                            "GitHub reconciliation could not finish; inspect current policy, directory evidence and identity links before retrying");
            reconciliation.failed(ref.workspaceId(), connectionId, failure);
            throw failure;
        } finally {
            WorkspaceContextHolder.setContext(previous);
        }
    }
}
