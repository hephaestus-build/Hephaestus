package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * The piece of reviewed work a provider page shows, as this workspace mirrored it, and what the caller may
 * do about it.
 *
 * <p>Both capabilities describe the caller, not the work, and neither is authorization: the endpoints they
 * point to decide again on every call. They exist so a surface does not offer a button that is certain to
 * be refused.
 */
@Schema(description = "The piece of reviewed work a provider page shows, and what the caller may do about it")
public record ReviewContextDTO(
        @NonNull
        @Schema(
                description = "The work as this workspace mirrored it; its id and kind address the trace, "
                        + "observations and feedback about it")
        ReviewedWorkRefDTO work,

        @NonNull
        @Schema(
                description = "Whether the caller may ask for a review of this work: they are its author or an "
                        + "assignee, or a workspace admin, through one of their own linked accounts. It does not "
                        + "promise a review starts; the answer to the ask says whether one did, and why not")
        Boolean canRequestReview,

        @NonNull
        @Schema(
                description = "Whether the caller may read the review details behind this work — the runs, "
                        + "observations and the delivery of each piece of feedback — which is a workspace "
                        + "admin's view")
        Boolean canInspectReviewDetails) {}
