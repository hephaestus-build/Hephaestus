package de.tum.cit.aet.hephaestus.workspace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkspaceMembershipDTO(
        @NonNull AccountRefDTO account,
        @NonNull WorkspaceRole role,
        @NonNull Instant joinedAt,
        @Nullable Instant leftAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hidden,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reviewSelected,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean eligibleForPracticeReview,

        @Schema(
                description = "Whether the member answered the public activity question in this workspace, without"
                        + " the answer. Null unless the reader is a workspace admin, the workspace publishes"
                        + " public activity, and the member has not left.")
        @Nullable
        Boolean publicActivityAnswered) {}
