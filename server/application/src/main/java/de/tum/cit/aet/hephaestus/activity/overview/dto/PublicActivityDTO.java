package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The public contract excludes account details, teams, automation and private activity. */
public record PublicActivityDTO(
        @NonNull String workspaceName,
        @NonNull Instant from,
        @NonNull Instant to,
        @NonNull List<PublicActivityPersonDTO> people,
        @NonNull ActivityCoverageDTO coverage,
        @NonNull ActivityHighlightsDTO highlights,
        @NonNull List<ActivityRepositoryDTO> repositories) {
    public static PublicActivityDTO from(String workspaceName, ActivityPeopleDTO activity) {
        return new PublicActivityDTO(
                workspaceName,
                activity.from(),
                activity.to(),
                activity.people().stream().map(PublicActivityPersonDTO::from).toList(),
                activity.coverage(),
                activity.highlights(),
                activity.repositories());
    }

    public record PublicActivityPersonDTO(
            @NonNull Long id,
            @NonNull String login,
            @NonNull String name,
            @NonNull String avatarUrl,
            @NonNull String profileUrl,
            @NonNull PublicActivityCountsDTO counts,
            @Nullable Instant firstContributionAt,
            @NonNull List<ActivitySparklineWeekDTO> weeks) {
        static PublicActivityPersonDTO from(ActivityPersonDTO row) {
            var person = row.person();
            var counts = row.counts();
            return new PublicActivityPersonDTO(
                    person.id(),
                    person.login(),
                    person.name(),
                    person.avatarUrl(),
                    person.htmlUrl(),
                    new PublicActivityCountsDTO(
                            counts.contributions(),
                            counts.pullRequestsOpened(),
                            counts.pullRequestsMerged(),
                            counts.pullRequestsReviewed(),
                            counts.peopleHelped(),
                            counts.issuesOpened(),
                            counts.activeWeeks()),
                    row.firstContributionAt(),
                    row.weeks());
        }
    }

    public record PublicActivityCountsDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) long contributions,
            @Schema(requiredMode = RequiredMode.REQUIRED) long pullRequestsOpened,
            @Schema(requiredMode = RequiredMode.REQUIRED) long pullRequestsMerged,
            @Schema(requiredMode = RequiredMode.REQUIRED) long pullRequestsReviewed,
            @Schema(requiredMode = RequiredMode.REQUIRED) long peopleHelped,
            @Schema(requiredMode = RequiredMode.REQUIRED) long issuesOpened,
            @Schema(requiredMode = RequiredMode.REQUIRED) long activeWeeks) {}
}
