package de.tum.cit.aet.hephaestus.activity.overview.dto;

import org.jspecify.annotations.NonNull;

public record ActivityBreakdownDTO(
        @NonNull Integer pullRequestsClosed,
        @NonNull Integer approvals,
        @NonNull Integer changeRequests,
        @NonNull Integer commentReviews,
        @NonNull Integer discussionComments,
        @NonNull Integer codeComments,
        @NonNull Integer issuesClosed) {}
