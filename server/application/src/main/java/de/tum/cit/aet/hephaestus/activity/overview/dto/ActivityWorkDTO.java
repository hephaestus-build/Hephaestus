package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "The activity on one pull request or issue in a time range")
public record ActivityWorkDTO(
        @NonNull
        @Schema(
                description = "Identifier of the group: work:<id of the pull request or issue>, or event:<id of the"
                        + " activity> for activity whose pull request or issue is not known")
        String id,

        @Nullable
        @Schema(
                description = "The pull request or issue; null when it, or the review or comment the activity was,"
                        + " is no longer known or was deleted upstream")
        WorkItemDTO work,

        @NonNull @Schema(description = "Each kind of activity that happened on it, in a fixed order of kinds")
        List<ActivityActionDTO> actions,

        @NonNull @Schema(description = "When the latest of this activity happened")
        Instant lastOccurredAt,

        @NonNull @Schema(description = "Everyone this activity counts for, by name")
        List<UserInfoDTO> people) {}
