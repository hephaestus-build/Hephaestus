package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.GeneralReviewCommentContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.IssueContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Enforces consent for every known human whose selected SCM content can be exported. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmCaptureContributors {
    private final UserRepository users;
    private final ScmCaptureConsent consent;
    private final ScmContributorQueryRepository queries;

    boolean permitsCommitRecords(
            Issue work, @Nullable PullRequestReview review, Collection<ScmCaptureConsent.Contributor> retained) {
        try {
            require(work, review, Set.of(PullRequestContentSource.DIFF), retained);
            return true;
        } catch (ItemCaptureException failure) {
            if (failure.code() != ItemCaptureCode.CONSENT_WITHHELD) throw failure;
            return false;
        }
    }

    /**
     * @param retained the people whose identities and words an event review's occasion retained, also when they no
     *     longer appear on the work or in the mirror
     */
    void require(
            Issue work,
            @Nullable PullRequestReview review,
            Set<SourceKind> selected,
            Collection<ScmCaptureConsent.Contributor> retained) {
        List<User> contributors = new ArrayList<>(work.getAssignees());
        if (work.getAuthor() != null) contributors.add(work.getAuthor());
        if (review != null && review.getAuthor() != null) contributors.add(review.getAuthor());
        if (work instanceof PullRequest pull && pull.getMergedBy() != null) contributors.add(pull.getMergedBy());
        List<Long> ids = new ArrayList<>();
        if (selected.contains(IssueContentSource.COMMENTS) || selected.contains(GeneralReviewCommentContentSource.KIND))
            ids.addAll(queries.issueCommentAuthors(work.getId()));
        if (selected.contains(PullRequestContentSource.COMMENTS) || selected.contains(ReviewThreadContentSource.KIND))
            ids.addAll(queries.reviewCommentAuthors(work.getId()));
        if (selected.contains(GeneralReviewCommentContentSource.KIND)) ids.addAll(queries.reviewAuthors(work.getId()));
        if (selected.contains(ReviewThreadContentSource.KIND)) ids.addAll(queries.threadResolvers(work.getId()));
        if (work instanceof PullRequest && selected.contains(PullRequestContentSource.DIFF))
            ids.addAll(queries.diffContributors(work.getId()));
        if (work instanceof PullRequest && selected.contains(RepositoryTreeContentSource.KIND))
            ids.addAll(queries.treeContributors(
                    Objects.requireNonNull(work.getRepository()).getId()));
        users.findAllById(ids.stream().distinct().toList()).forEach(contributors::add);
        var everyone = new ArrayList<ScmCaptureConsent.Contributor>();
        contributors.forEach(user -> everyone.add(ScmCaptureConsent.Contributor.of(user)));
        everyone.addAll(retained);
        consent.requireContributors(everyone);
    }
}
