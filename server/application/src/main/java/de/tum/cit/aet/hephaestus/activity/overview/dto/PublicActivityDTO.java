package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The public contract excludes account details, teams, automation and private activity. */
public record PublicActivityDTO(
        @NonNull String workspaceName,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean allowSearchEngines,
        @NonNull Instant from,
        @NonNull Instant to,
        @NonNull List<PublicActivityPersonDTO> people,
        @NonNull ActivityCoverageDTO coverage,
        @NonNull PublicActivityHighlightsDTO highlights,
        @NonNull List<PublicActivityRepositoryDTO> repositories) {
    public static PublicActivityDTO from(String workspaceName, boolean allowSearchEngines, ActivityPeopleDTO activity) {
        var loginsById = activity.people().stream()
                .collect(Collectors.toMap(
                        row -> row.person().id(), row -> row.person().login()));
        return new PublicActivityDTO(
                workspaceName,
                allowSearchEngines,
                activity.from(),
                activity.to(),
                activity.people().stream().map(PublicActivityPersonDTO::from).toList(),
                activity.coverage(),
                new PublicActivityHighlightsDTO(
                        logins(loginsById, activity.highlights().firstContributors()),
                        logins(loginsById, activity.highlights().mostPeopleHelped())),
                activity.repositories().stream()
                        .map(repository -> new PublicActivityRepositoryDTO(repository.key(), repository.name()))
                        .toList());
    }

    private static List<String> logins(Map<Long, String> loginsById, List<Long> ids) {
        return ids.stream()
                .map(id -> Objects.requireNonNull(loginsById.get(id)))
                .toList();
    }

    public record PublicActivityHighlightsDTO(
            @NonNull List<String> firstContributors,
            @NonNull List<String> mostPeopleHelped) {}

    public record PublicActivityRepositoryDTO(
            @NonNull String key, @NonNull String name) {}

    public record PublicActivityPersonDTO(
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
