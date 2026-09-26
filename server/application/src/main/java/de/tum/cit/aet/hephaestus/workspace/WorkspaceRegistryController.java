package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.feature.FeatureFlag;
import de.tum.cit.aet.hephaestus.feature.FeatureFlagService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceProvidersDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Workspace registry endpoints under /workspaces that operate outside of a specific workspace slug;
 * slugged routes live in {@link WorkspaceController} via {@code @WorkspaceScopedController}.
 */
@RestController
@RequestMapping("/workspaces")
@RequiredArgsConstructor
@Validated
@PreAuthorize("isAuthenticated()")
public class WorkspaceRegistryController {

    private final WorkspaceService workspaceService;
    private final WorkspaceQueryService workspaceQueryService;
    private final WorkspaceProvisioningService workspaceProvisioningService;
    private final FeatureFlagService featureFlagService;
    private final WorkspaceProperties workspaceProperties;

    @GetMapping("/providers")
    @Operation(
            summary = "List available workspace creation providers",
            description =
                    "Returns available workspace providers with their configuration. Public endpoint — no authentication required.")
    @SecurityRequirements
    @PreAuthorize("permitAll()")
    public ResponseEntity<WorkspaceProvidersDTO> getProviders() {
        return ResponseEntity.ok(workspaceQueryService.getAvailableProviders());
    }

    @PostMapping
    @Operation(summary = "Create a new workspace")
    @ApiResponse(
            responseCode = "201",
            description = "Workspace created",
            content = @Content(schema = @Schema(implementation = WorkspaceDTO.class)))
    public ResponseEntity<WorkspaceDTO> createWorkspace(
            @Valid @RequestBody CreateWorkspaceRequestDTO createWorkspaceRequest) {
        // Actor gate; orthogonal to per-provider availability (the GitLab feature flag below).
        if (workspaceProperties.creationPolicy() == WorkspaceProperties.CreationPolicy.ADMIN_ONLY
                && !SecurityUtils.isSuperAdmin()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Workspace creation is restricted to instance admins on this deployment");
        }

        if (createWorkspaceRequest.kind() == IntegrationKind.GITLAB
                && !featureFlagService.isEnabled(FeatureFlag.GITLAB_WORKSPACE_CREATION)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitLab workspace creation is not enabled");
        }

        // The token may only reach, and the workspace only bind to, a configured GitLab instance.
        CreateWorkspaceRequestDTO request = createWorkspaceRequest.kind() == IntegrationKind.GITLAB
                ? createWorkspaceRequest.withServerUrl(
                        workspaceProvisioningService.requireGitLabInstance(createWorkspaceRequest.serverUrl()))
                : createWorkspaceRequest;

        Workspace workspace = workspaceService.createWorkspaceWithInitialization(request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{workspaceSlug}")
                .buildAndExpand(workspace.getWorkspaceSlug())
                .toUri();

        return ResponseEntity.created(location).body(workspaceQueryService.toWorkspaceDTO(workspace));
    }

    @GetMapping
    @Operation(summary = "List all workspaces")
    @ApiResponse(
            responseCode = "200",
            description = "Workspace list",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = WorkspaceListItemDTO.class))))
    public ResponseEntity<List<WorkspaceListItemDTO>> listWorkspaces() {
        return ResponseEntity.ok(workspaceQueryService.findAccessibleWorkspaceListItems());
    }
}
