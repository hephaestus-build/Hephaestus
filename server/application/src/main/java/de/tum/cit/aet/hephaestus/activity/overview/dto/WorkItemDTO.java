package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue.State;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryInfoDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.Hibernate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A pull request or issue, with what a developer needs to decide whether to open it. */
@Schema(description = "A pull request or issue")
public record WorkItemDTO(
        @NonNull @Schema(description = "Identifier of the pull request or issue")
        Long id,

        @NonNull @Schema(description = "Whether this is a pull request or an issue")
        WorkItemType type,

        @NonNull @Schema(description = "Number within the repository", example = "42")
        Integer number,

        @NonNull @Schema(description = "Title") String title,
        @NonNull @Schema(description = "Current state") State state,

        @NonNull @Schema(description = "Whether the pull request is a draft; false for issues")
        Boolean isDraft,

        @Nullable @Schema(description = "The pull request's review decision, when the provider reported one")
        ReviewDecision reviewDecision,

        @Nullable @Schema(description = "What the checks said about the pull request's current head, when known")
        CheckState checks,

        @Nullable @Schema(description = "Link to the pull request or issue on the provider")
        String htmlUrl,

        @Nullable @Schema(description = "The repository") RepositoryInfoDTO repository,
        @Nullable @Schema(description = "The author") UserInfoDTO author,

        @Nullable @Schema(description = "When it was created")
        Instant createdAt,

        @Nullable @Schema(description = "When it was last updated")
        Instant updatedAt) {

    public enum WorkItemType {
        PULL_REQUEST,
        ISSUE
    }

    /** The work, or null when it is gone or was deleted upstream. */
    public static @Nullable WorkItemDTO fromAvailable(@Nullable Issue work) {
        return work == null || work.getDeletedAt() != null ? null : from(work);
    }

    public static WorkItemDTO from(Issue work) {
        // SINGLE_TABLE: a proxy of Issue is never instanceof PullRequest, even when the row is one.
        if (Hibernate.unproxy(work) instanceof PullRequest pullRequest) {
            // A check state observed for an earlier head says nothing about the current one.
            boolean checksCurrent = pullRequest.getHeadCheckSha() != null
                    && Objects.equals(pullRequest.getHeadCheckSha(), pullRequest.getHeadRefOid());
            return new WorkItemDTO(
                    pullRequest.getId(),
                    WorkItemType.PULL_REQUEST,
                    pullRequest.getNumber(),
                    pullRequest.getTitle(),
                    pullRequest.getState(),
                    pullRequest.isDraft(),
                    pullRequest.getReviewDecision(),
                    checksCurrent ? pullRequest.getHeadCheckState() : null,
                    pullRequest.getHtmlUrl(),
                    RepositoryInfoDTO.fromRepositoryWithoutLabels(pullRequest.getRepository()),
                    UserInfoDTO.fromUser(pullRequest.getAuthor()),
                    pullRequest.getCreatedAt(),
                    pullRequest.getUpdatedAt());
        }
        return new WorkItemDTO(
                work.getId(),
                WorkItemType.ISSUE,
                work.getNumber(),
                work.getTitle(),
                work.getState(),
                false,
                null,
                null,
                work.getHtmlUrl(),
                RepositoryInfoDTO.fromRepositoryWithoutLabels(work.getRepository()),
                UserInfoDTO.fromUser(work.getAuthor()),
                work.getCreatedAt(),
                work.getUpdatedAt());
    }
}
