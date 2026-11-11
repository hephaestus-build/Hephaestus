package de.tum.cit.aet.hephaestus.workspace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.workspace.Membership.RoleSource;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkspaceMembershipDTO(
        @NonNull AccountRefDTO account,
        @NonNull WorkspaceRole role,

        @Schema(
                description = "Who decided the role: PROVIDER while the organization or group roster sets it at each"
                        + " sync, PERSON after a person set it. An owner role is always PERSON.")
        @NonNull
        RoleSource roleSource,

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
