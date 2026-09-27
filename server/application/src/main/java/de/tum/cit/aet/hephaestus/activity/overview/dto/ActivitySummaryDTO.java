package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "Counts of activity in a time range. Each count is the number of timeline entries of its kind"
                + " for the same scope and range.")
public record ActivitySummaryDTO(
        @NonNull @Schema(description = "Pull requests opened", example = "4")
        Integer pullRequestsOpened,

        @NonNull
        @Schema(description = "Pull requests merged; a merge counts for the pull request's author", example = "3")
        Integer pullRequestsMerged,

        @NonNull @Schema(description = "Pull requests closed without merging", example = "1")
        Integer pullRequestsClosed,

        @NonNull @Schema(description = "Reviews that approved", example = "4")
        Integer approvals,

        @NonNull @Schema(description = "Reviews that requested changes", example = "1")
        Integer changeRequests,

        @NonNull @Schema(description = "Reviews that only commented", example = "2")
        Integer commentReviews,

        @NonNull @Schema(description = "Comments in pull request and issue conversations", example = "9")
        Integer comments,

        @NonNull @Schema(description = "Comments on lines of code", example = "12")
        Integer codeComments,

        @NonNull @Schema(description = "Issues opened", example = "2")
        Integer issuesOpened,

        @NonNull @Schema(description = "Issues closed; a closed issue counts for its author", example = "1")
        Integer issuesClosed) {}
