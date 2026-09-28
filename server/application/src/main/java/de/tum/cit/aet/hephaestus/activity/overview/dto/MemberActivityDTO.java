package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "One member and their activity in a time range")
public record MemberActivityDTO(
        @NonNull @Schema(description = "The member") UserInfoDTO user,

        @NonNull @Schema(description = "The member's activity")
        ActivitySummaryDTO summary) {}
