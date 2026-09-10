package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for assigning or updating a role for a workspace member.
 */
@Schema(description = "Request to assign or update a user's role in a workspace")
public record AssignRoleRequestDTO(
        @NotNull @Schema(description = "Account ID of the member to update")
        Long accountId,

        @NotNull @Schema(description = "New role to assign (OWNER, ADMIN, MEMBER)")
        WorkspaceRole role) {}
