package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "Someone reviewing a pull request, and where their review stands")
public record ReviewerDTO(
        @NonNull @Schema(description = "The reviewer") UserInfoDTO user,

        @NonNull
        @Schema(
                description = "REQUESTED while a review is asked of them, otherwise the verdict of their latest review"
                        + " that was not dismissed")
        ReviewerState state) {

    /** Declared in the order reviewers are listed: who holds the pull request up comes first. */
    public enum ReviewerState {
        CHANGES_REQUESTED,
        APPROVED,
        COMMENTED,
        REQUESTED
    }
}
