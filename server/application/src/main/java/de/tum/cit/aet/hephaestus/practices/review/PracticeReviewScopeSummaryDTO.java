package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.PeopleCount;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

public record PracticeReviewScopeSummaryDTO(
        @Schema(description = "Monitored repositories admitted by the repository coverage axis") @NonNull
        Integer selectedRepositories,

        @Schema(description = "Repositories currently monitored by the workspace") @NonNull
        Integer monitoredRepositories,

        @PeopleCount(exactBecause = NAMED_IN_MEMBERS)
        @Schema(description = "Eligible linked members admitted by the people coverage axis")
        @NonNull
        Integer selectedPeople,

        @PeopleCount(exactBecause = NAMED_IN_MEMBERS)
        @Schema(description = "Eligible linked human members in the workspace")
        @NonNull
        Integer eligiblePeople,

        @Schema(description = "Workspace-wide practice-review jobs created during the estimate window") @NonNull
        Integer recentReviewVolume,

        @Schema(description = "Number of days included in recentReviewVolume") @NonNull
        Integer estimateWindowDays) {

    /** Why the two people counts are exact. */
    static final String NAMED_IN_MEMBERS = "The member list names each person and whether they are eligible or"
            + " selected, and an admin selects people by account";
}
