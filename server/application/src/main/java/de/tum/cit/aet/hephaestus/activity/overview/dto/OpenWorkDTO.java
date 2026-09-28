package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "What is open for one member right now")
public record OpenWorkDTO(
        @NonNull
        @Schema(
                description =
                        "Open pull requests by other people that ask this member for a review; drafts are excluded")
        WorkItemListDTO reviewRequests,

        @NonNull
        @Schema(
                description = "Open pull requests by other people that ask one of this member's teams for a review,"
                        + " and not this member: not asking them directly, and not yet approved or sent back by them."
                        + " Drafts are excluded. GitHub only; GitLab has no team reviewers, so a GitLab workspace"
                        + " lists none.")
        WorkItemListDTO teamReviewRequests,

        @NonNull @Schema(description = "Open pull requests this member authored, drafts included")
        WorkItemListDTO pullRequests,

        @NonNull @Schema(description = "Open issues assigned to this member")
        WorkItemListDTO issues) {}
