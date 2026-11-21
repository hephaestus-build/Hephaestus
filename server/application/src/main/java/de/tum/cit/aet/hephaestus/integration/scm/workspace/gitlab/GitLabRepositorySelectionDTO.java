package de.tum.cit.aet.hephaestus.integration.scm.workspace.gitlab;

import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

@Schema(description = "Whether a GitLab workspace follows every project of its group or only the projects it monitors")
public record GitLabRepositorySelectionDTO(
        @NonNull
        @NotNull(message = "repositorySelection is required")
        @Schema(
                description = "ALL admits projects that discovery later finds in the group, except stopped ones."
                        + " SELECTED admits only the projects the workspace monitors.")
        RepositorySelection repositorySelection) {}
