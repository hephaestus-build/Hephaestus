package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record ScmCapturedWork(
        long sourceId,
        long repositoryId,
        int number,
        @Nullable String url,
        String repositoryPath,
        String title,
        @Nullable String body,
        @Nullable String head,
        @Nullable String base,
        @Nullable String sourceBranch,
        @Nullable String targetBranch,
        @Nullable Long reviewSourceId,
        @Nullable Long reviewerUserId) {
    /** The limitation of a discussion without a recorded whole provider read. */
    public static final String DISCUSSION_NOT_READ_WHOLE = "DISCUSSION_NOT_READ_WHOLE";

    public static final String COMMENT_COUNT_UNKNOWN = "COMMENT_COUNT_UNKNOWN";
    public static final String COMMENTS_NOT_READ_WHOLE = "COMMENTS_NOT_READ_WHOLE";

    public ScmCapturedWork {
        if (sourceId <= 0 || repositoryId <= 0 || number <= 0 || repositoryPath.isBlank())
            throw new IllegalArgumentException("Invalid pinned SCM work");
    }

    /** The work, and the submitted review when the item is one, as the mirror stores them now. */
    public static ScmCapturedWork of(Issue work, @Nullable PullRequestReview review) {
        Repository repository = Objects.requireNonNull(work.getRepository());
        PullRequest pull = work instanceof PullRequest p ? p : null;
        return new ScmCapturedWork(
                work.getId(),
                repository.getId(),
                work.getNumber(),
                work.getHtmlUrl(),
                repository.getNameWithOwner(),
                work.getTitle(),
                work.getBody(),
                pull == null ? null : pull.getHeadRefOid(),
                pull == null ? null : pull.getBaseRefOid(),
                pull == null ? null : pull.getHeadRefName(),
                pull == null ? null : pull.getBaseRefName(),
                review == null ? null : review.getId(),
                review == null || review.getAuthor() == null
                        ? null
                        : review.getAuthor().getId());
    }
}
