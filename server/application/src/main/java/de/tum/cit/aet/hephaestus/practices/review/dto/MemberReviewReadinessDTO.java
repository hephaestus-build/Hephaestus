package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Whether and how the workspace reviews one member's work, from the rules that start and admit a review. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MemberReviewReadinessDTO(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The first reason, in the enum's order, why no review of the member's work runs now;"
                        + " null when work activity can start one. Repository coverage and a practice's state"
                        + " filters depend on the work and are not judged here.")
        @Nullable
        ReviewReadinessBlocker blocker,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The sorted, distinct item events (such as scm.pull_request.opened) that can start a"
                        + " review of the member's work, from the practices that Hephaestus reviews about the person"
                        + " who did the work. A review also needs the work's repository and the practice's own"
                        + " conditions to match, and a null blocker.")
        List<String> events,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether a workspace admin approves feedback from at least one of those practices"
                        + " before it is posted on the work")
        boolean approvalBeforePosting) {
    public MemberReviewReadinessDTO {
        events = List.copyOf(events);
    }
}
