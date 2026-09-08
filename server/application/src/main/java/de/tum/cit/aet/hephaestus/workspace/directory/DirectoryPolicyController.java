package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.ClientCredentials;
import de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncJobDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequestMapping("/directory-access")
@RequiredArgsConstructor
public class DirectoryPolicyController {
    private final DirectoryPolicyService policies;
    private final DirectoryPolicyViewService views;
    private final DirectoryReconciliationService reconciliation;

    @GetMapping
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getDirectoryPolicy", summary = "Inspect workspace directory management and its evidence")
    public DirectoryAccessStateDTO get(WorkspaceContext context) {
        return new DirectoryAccessStateDTO(views.view(context.id()).orElse(null));
    }

    public record DirectoryAccessStateDTO(@Nullable DirectoryPolicyDTO policy) {}

    @GetMapping("/sources")
    @RequireWorkspaceOwner
    @Operation(operationId = "getDirectorySources", summary = "List operator-approved directory sources")
    public List<DirectorySourceDTO> sources(WorkspaceContext context) {
        return policies.availableSources(context.id()).stream()
                .map(source -> new DirectorySourceDTO(
                        source.registrationId(), source.displayName(), source.issuer(), source.groupIds()))
                .toList();
    }

    @PutMapping
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "DIRECTORY_POLICY")
    @Operation(
            operationId = "configureDirectoryPolicy",
            summary = "Save directory setup or revise its draft groups and credentials")
    public DirectoryPolicyDTO configure(WorkspaceContext context, @Valid @RequestBody DirectoryConfigurationDTO input) {
        ClientCredentials credentials = input.credentials() == null
                ? null
                : new ClientCredentials(
                        input.credentials().clientId(), input.credentials().clientSecret());
        policies.configure(
                context.id(),
                new DirectoryPolicyService.Configuration(input.registrationId(), input.groupIds(), credentials));
        return views.view(context.id()).orElseThrow();
    }

    @PostMapping("/previews")
    @RequireWorkspaceOwner
    @AuditExempt(reason = "Read-only preview recorded in the existing sync job ledger; approval is audited separately")
    @Operation(operationId = "previewDirectoryPolicy", summary = "Capture a complete preview without changing access")
    public ResponseEntity<SyncJobDTO> preview(WorkspaceContext context) {
        return ResponseEntity.accepted().body(reconciliation.request(context.id(), true));
    }

    @PostMapping("/reconciliations")
    @RequireAtLeastWorkspaceAdmin
    @AuditExempt(
            reason =
                    "Reconciliation is recorded in the sync job ledger; resulting membership mutations are configuration-audited")
    @Operation(operationId = "reconcileDirectoryPolicy", summary = "Reconcile the owner-approved directory policy")
    public ResponseEntity<SyncJobDTO> reconcile(WorkspaceContext context) {
        return ResponseEntity.accepted().body(reconciliation.request(context.id(), false));
    }

    @PostMapping("/approvals")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "DIRECTORY_POLICY")
    @Operation(operationId = "approveDirectoryPolicy", summary = "Approve one fresh configuration preview")
    public DirectoryPolicyDTO approve(WorkspaceContext context, @Valid @RequestBody DirectoryApprovalDTO input) {
        policies.approve(context.id(), input.configurationVersion());
        return views.view(context.id()).orElseThrow();
    }

    @PatchMapping("/status")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "DIRECTORY_POLICY")
    @Operation(operationId = "changeDirectoryPolicyStatus", summary = "Pause, resume or end directory management")
    public DirectoryPolicyDTO status(WorkspaceContext context, @Valid @RequestBody DirectoryStatusDTO input) {
        policies.changeStatus(context.id(), input.status());
        return views.view(context.id()).orElseThrow();
    }

    @PutMapping("/members/{accountId}")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_ROLE")
    @Operation(
            operationId = "adoptDirectoryMember",
            summary = "Explicitly adopt existing non-owner access into directory management")
    public DirectoryPolicyDTO adopt(WorkspaceContext context, @PathVariable Long accountId) {
        policies.adopt(context.id(), accountId);
        return views.view(context.id()).orElseThrow();
    }

    public record DirectorySourceDTO(
            @NonNull String registrationId,
            @NonNull String displayName,
            @NonNull String issuer,
            @NonNull Set<String> groupIds) {}

    public record DirectoryCredentialsDTO(
            @NotBlank @Size(max = 512) String clientId,
            @NotBlank @Size(max = 4096) String clientSecret) {
        @Override
        public String toString() {
            return "DirectoryCredentialsDTO[clientId=***, clientSecret=***]";
        }
    }

    public record DirectoryConfigurationDTO(
            @NotBlank @Size(max = 64) String registrationId,
            @NotNull @Size(min = 1, max = 100) Set<@NotBlank @Size(max = 255) String> groupIds,
            @Nullable @Valid DirectoryCredentialsDTO credentials) {}

    public record DirectoryApprovalDTO(@NotNull @Positive Long configurationVersion) {}

    public record DirectoryStatusDTO(@NotNull DirectoryPolicy.Status status) {}
}
