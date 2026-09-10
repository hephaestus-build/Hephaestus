package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationSyncRunner;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.directory.KeycloakDirectoryClient;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Executes complete directory captures through the shared connection-job runtime. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class DirectorySyncAdapter implements IntegrationSyncRunner {
    private final DirectoryPolicyService policies;
    private final KeycloakDirectoryClient directory;
    private final WorkspaceRepository workspaces;

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.KEYCLOAK_DIRECTORY;
    }

    // The shared job runtime logs thrown causes; retain only the redacted operational reason here.
    @SuppressWarnings("PMD.PreserveStackTrace")
    @Override
    public void reconcile(IntegrationRef ref, SyncExecutionHandle handle, SyncJobType type) {
        DirectoryPolicyService.@Nullable CaptureInput input = null;
        var previousContext = WorkspaceContextHolder.getContext();
        try {
            var workspace = workspaces
                    .findById(ref.workspaceId())
                    .orElseThrow(
                            () -> new KeycloakDirectoryClient.ReadFailure("The directory workspace no longer exists"));
            WorkspaceContextHolder.setContext(WorkspaceContext.fromWorkspace(workspace, Set.of(), null));
            input = policies.prepareCapture(
                    ref.workspaceId(), Objects.requireNonNull(ref.connectionId()), type == SyncJobType.INITIAL);
            var capture = directory.capture(new KeycloakDirectoryClient.Request(
                    input.source().issuer(), input.credentials(), input.groupIds(), input.previousSubjects(), handle));
            policies.completeCapture(input, capture);
        } catch (RuntimeException failure) {
            // Provider bodies and credentials must not reach either the job ledger or the policy UI.
            String reason = failure instanceof KeycloakDirectoryClient.ReadFailure
                    ? Objects.requireNonNullElse(failure.getMessage(), "Directory capture failed")
                    : "Directory capture could not be applied; check source approval, workspace status and policy changes before retrying";
            policies.failedCapture(ref.workspaceId(), ref.connectionId(), input, reason);
            throw new KeycloakDirectoryClient.ReadFailure(reason);
        } finally {
            WorkspaceContextHolder.setContext(previousContext);
        }
    }
}
