package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.BaseGitServiceEntity;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "pull_request_review_comment",
        indexes = {
            @Index(name = "idx_review_comment_review_reply", columnList = "review_id,in_reply_to_id"),
            @Index(name = "idx_review_comment_pull_request", columnList = "pull_request_id,id")
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_pr_review_comment_provider_native_id",
                    columnNames = {"provider_id", "native_id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class PullRequestReviewComment extends BaseGitServiceEntity {

    // The diff of the line that the comment refers to.
    // Nullable: GitLab does not expose diff hunks for inline comments.
    @Column(columnDefinition = "TEXT")
    private @Nullable String diffHunk;

    // The relative path of the file to which the comment applies.
    @NonNull
    private String path;

    // The SHA of the commit to which the comment applies.
    private @Nullable String commitId;

    // The SHA of the original commit to which the comment applies.
    private @Nullable String originalCommitId;

    @Column(columnDefinition = "TEXT")
    @NonNull
    private String body;

    @NonNull
    private String htmlUrl;

    // Nullable: GitLab does not have the concept of author association.
    @Enumerated(EnumType.STRING)
    private @Nullable AuthorAssociation authorAssociation;

    // The first line of the range for a multi-line comment.
    private Integer startLine;

    // The first line of the range for a multi-line comment.
    private Integer originalStartLine;

    // The line of the blob to which the comment applies. The last line of the range for a multi-line comment
    private int line;

    // The line of the blob to which the comment applies. The last line of the range for a multi-line comment
    private int originalLine;

    // Which side of the diff the comment refers to (RIGHT = new/head, LEFT = old/base).
    // Derived from GitLab position (RIGHT if new_line set, LEFT if only old_line).
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private @Nullable Side side;

    // Which side of the diff the start of a multi-line comment range refers to.
    @Enumerated(EnumType.STRING)
    @Column(name = "start_side", length = 16)
    private @Nullable Side startSide;

    // Whether the comment body content is outdated (i.e., code it refers to has changed)
    private @Nullable Boolean outdated;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    @ToString.Exclude
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id")
    @ToString.Exclude
    @Nullable
    private PullRequestReview review;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pull_request_id")
    @ToString.Exclude
    @Nullable
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id", nullable = false)
    @ToString.Exclude
    @Nullable
    private PullRequestReviewThread thread;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "in_reply_to_id")
    @ToString.Exclude
    private @Nullable PullRequestReviewComment inReplyTo;

    @OneToMany(mappedBy = "inReplyTo")
    @ToString.Exclude
    private Set<PullRequestReviewComment> replies = new HashSet<>();

    /**
     * The revision the comment was written against, or null when none is recorded. GitHub's original commit is the
     * revision originally reviewed; GitLab's is the comparison base, so its current commit is the reviewed head.
     */
    public @Nullable String reviewedRevision() {
        if (originalCommitId != null && !originalCommitId.isBlank() && provider.kind() == IntegrationKind.GITHUB) {
            return originalCommitId;
        }
        return commitId == null || commitId.isBlank() ? null : commitId;
    }

    public enum Side {
        LEFT,
        RIGHT,
        UNKNOWN,
    }
}
