package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceProperties;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabGroupDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabPreflightRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabPreflightResponseDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabProjectPageDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** GitLab-specific REST endpoints used during the workspace creation wizard. */
@RestController
@RequestMapping("/workspaces/gitlab")
@Validated
@PreAuthorize("isAuthenticated()")
public class GitLabPreflightController {

    private final GitLabPreflightService gitLabPreflightService;
    private final WorkspaceProperties workspaceProperties;

    public GitLabPreflightController(
            GitLabPreflightService gitLabPreflightService, WorkspaceProperties workspaceProperties) {
        this.gitLabPreflightService = gitLabPreflightService;
        this.workspaceProperties = workspaceProperties;
    }

    /** The wizard's provider checks follow the same actor gate as workspace creation. */
    private void requireWorkspaceCreator() {
        if (workspaceProperties.creationPolicy() == WorkspaceProperties.CreationPolicy.ADMIN_ONLY
                && !SecurityUtils.isSuperAdmin()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "An instance admin must create workspaces on this deployment.");
        }
    }

    @PostMapping("/preflight")
    @Operation(summary = "Validate a GitLab PAT before workspace creation")
    @ApiResponse(
            responseCode = "200",
            description = "Validation result",
            content = @Content(schema = @Schema(implementation = GitLabPreflightResponseDTO.class)))
    public ResponseEntity<GitLabPreflightResponseDTO> gitLabPreflight(
            @Valid @RequestBody GitLabPreflightRequestDTO request) {
        requireWorkspaceCreator();
        GitLabPreflightResponseDTO result = gitLabPreflightService.validateToken(
                request.personalAccessToken(), request.serverUrl(), request.groupFullPath());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/groups")
    @Operation(summary = "List GitLab groups accessible to a PAT")
    @ApiResponse(
            responseCode = "200",
            description = "Accessible groups",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = GitLabGroupDTO.class))))
    public ResponseEntity<List<GitLabGroupDTO>> listGitLabGroups(
            @Valid @RequestBody GitLabPreflightRequestDTO request) {
        requireWorkspaceCreator();
        List<GitLabGroupDTO> groups =
                gitLabPreflightService.listAccessibleGroups(request.personalAccessToken(), request.serverUrl());
        return ResponseEntity.ok(groups);
    }

    @PostMapping("/projects")
    @Operation(summary = "List one page of the projects in a GitLab group and its subgroups that a PAT can read")
    @ApiResponse(
            responseCode = "200",
            description = "One page of projects; nextPage is absent on the last page",
            content = @Content(schema = @Schema(implementation = GitLabProjectPageDTO.class)))
    public ResponseEntity<GitLabProjectPageDTO> listGitLabProjects(
            @Valid @RequestBody GitLabPreflightRequestDTO request,
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(required = false) @Size(max = 255) @Nullable String search) {
        requireWorkspaceCreator();
        return ResponseEntity.ok(gitLabPreflightService.listGroupProjects(
                request.personalAccessToken(), request.serverUrl(), request.groupFullPath(), page, search));
    }
}
