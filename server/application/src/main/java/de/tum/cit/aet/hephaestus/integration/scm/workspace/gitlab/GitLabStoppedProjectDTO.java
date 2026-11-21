package de.tum.cit.aet.hephaestus.integration.scm.workspace.gitlab;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

@Schema(description = "A stopped GitLab project whose synced work the workspace still keeps")
public record GitLabStoppedProjectDTO(
        @NonNull @Schema(description = "The workspace's repository item that holds the kept work")
        String repositoryItemId,

        @NonNull String nameWithOwner,

        @NonNull @Schema(description = "Names the Stop that a removal of the kept work answers")
        UUID stopReceipt) {}
