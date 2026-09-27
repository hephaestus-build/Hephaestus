package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.activity.overview.ActivityKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One thing someone did")
public record ActivityItemDTO(
        @NonNull @Schema(description = "Identifier of the activity")
        String id,

        @NonNull @Schema(description = "What was done") ActivityKind kind,
        @NonNull @Schema(description = "When it happened") Instant occurredAt,
        @NonNull @Schema(description = "Who did it") UserInfoDTO actor,

        @Nullable
        @Schema(
                description = "The pull request or issue it happened on; null when that pull request or issue, or"
                        + " the review or comment the activity was, is no longer known or was deleted upstream")
        WorkItemDTO work,

        @Nullable
        @Schema(
                description = "Link to the review or comment itself; null for pull request and issue activity, and"
                        + " whenever work is null")
        String htmlUrl) {}
