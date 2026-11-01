package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.spi.AdminPasskeyAccess;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
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

@RestController
@ConditionalOnServerRole
@RequestMapping("/workspaces")
@RequiredArgsConstructor
@Validated
@PreAuthorize("isAuthenticated()")
public class WorkspaceRegistryController {

    private final AdminPasskeyAccess passkeyAccess;
    private final WorkspaceService workspaceService;
    private final WorkspaceQueryService workspaceQueryService;
    private final WorkspaceProperties workspaceProperties;

    @GetMapping("/providers")
    @Operation(
            summary = "List available workspace creation providers",
            description = "Returns available workspace providers with their configuration. Public"
                    + " endpoint — no authentication required.")
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
        // Actor gate; per-provider availability is checked where the provider is resolved.
        if (workspaceProperties.creationPolicy() == WorkspaceProperties.CreationPolicy.ADMIN_ONLY
                && !SecurityUtils.isSuperAdmin()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "An instance admin must create workspaces on this deployment.");
        }

        passkeyAccess.requireWorkspaceAdmin(
                false, workspaceProperties.creationPolicy() == WorkspaceProperties.CreationPolicy.ADMIN_ONLY, true);

        Workspace workspace = workspaceService.createWorkspaceWithInitialization(createWorkspaceRequest);

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
