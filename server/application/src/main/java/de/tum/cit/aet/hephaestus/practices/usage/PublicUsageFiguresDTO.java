package de.tum.cit.aet.hephaestus.practices.usage;

import de.tum.cit.aet.hephaestus.core.ShownCount;
import org.jspecify.annotations.Nullable;

/**
 * The figures of one released half-year. Each is rounded down, and null when it is not published. No figure counts
 * one workspace or one person.
 *
 * @param workspaces workspaces with at least one practice review in the period
 * @param reviewedPeople distinct people with an observation recorded in the period
 * @param reviewedWork distinct pull requests and merge requests with a judged or reused observation
 * @param practiceReviews practices checked in the reviews of the period, one per practice and review
 * @param feedbackDelivered practice feedback delivered in the period
 */
public record PublicUsageFiguresDTO(
        @Nullable Long workspaces,
        @Nullable ShownCount reviewedPeople,
        @Nullable Long reviewedWork,
        @Nullable Long practiceReviews,
        @Nullable Long feedbackDelivered) {}
