package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.GeneralReviewCommentContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.IssueContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturePreparation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturedWork;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Prepares the evidence of SCM work: the capture of the current work or of what an occasion retained. The caller has
 * already pinned the item and checked current access to it.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmWorkCapture {
    private final PullRequestContentSource pullContent;
    private final IssueContentSource issueContent;
    private final GeneralReviewCommentContentSource generalComments;
    private final ReviewThreadContentSource threads;
    private final LinkedWorkItemContentSource linked;
    private final RepositoryTreeContentSource repositoryTree;
    private final ScmCaptureContributors contributors;
    private final GitRepositoryManager git;
    private final ReviewRepositoryPreparer preparer;
    private final ScmWorkLookup workLookup;
    private final ScmRetainedOccasionEvidence occasionEvidence;
    private final Clock clock;

    /** The item kinds whose event reviews read only what their occasion retained. */
    static boolean retains(ItemKind kind) {
        return kind.equals(ScmItemEvents.PULL_REQUEST) || kind.equals(ScmItemEvents.PULL_REQUEST_REVIEW);
    }

    /**
     * Whether a capture of pull request work under {@code request} reads the clone of its reviewed repository, which it
     * fetches before its transaction. {@link ScmCapturePreparation#prepare} refuses a read that no fetch preceded.
     */
    boolean readsClone(ItemKind kind, CaptureRequest request) {
        var sources = request.sources();
        return retains(kind)
                && git.isEnabled()
                && (PullRequestContentSource.readsClone(sources, request.permittedSources())
                        || LinkedWorkItemContentSource.readsClone(sources)
                        || RepositoryTreeContentSource.readsClone(sources));
    }

    /** The reviewed repository of {@code pinned}: the occasion's work for an event review, else the current work. */
    ReviewRepositoryPreparer.Remote reviewedRepository(ItemSnapshot pinned, @Nullable UUID eventId) {
        var item = pinned.item();
        if (eventId != null)
            return preparer.remote(
                    pinned, occasionEvidence.occasion(item, eventId).work());
        var current = workLookup.current(item);
        return preparer.remote(pinned, ScmCapturedWork.of(current.work(), current.review()));
    }

    /** Fetches the reviewed repository; the caller runs it with no transaction. */
    void fetch(ReviewRepositoryPreparer.Remote reviewed) {
        preparer.fetch(reviewed);
    }

    /**
     * Captures {@code work} as pinned, or what {@code occasion} retained of it. Every contributor must consent,
     * including the people only the occasion still names. {@code preparation} holds the fetch that ran before the
     * transaction.
     */
    CaptureContribution capture(
            ItemSnapshot pinned,
            CaptureRequest request,
            Issue work,
            @Nullable PullRequestReview review,
            @Nullable ScmOccasionEvidence occasion,
            ScmCapturePreparation preparation) {
        List<ScmCaptureConsent.Contributor> retained = occasion == null
                ? List.of()
                : occasion.participants().stream()
                        .map(ScmOccasionEvidence.Participant::contributor)
                        .toList();
        contributors.require(work, review, request.sources(), retained);
        var permittedRequest = request;
        if (request.sources().contains(PullRequestContentSource.CORE)
                && !request.sources().contains(PullRequestContentSource.DIFF)
                && request.permittedSources().contains(PullRequestContentSource.DIFF)
                && !contributors.permitsCommitRecords(work, review, retained)) {
            // Withheld commit words do not withdraw the separately permitted pull request metadata.
            var permitted = new HashSet<>(request.permittedSources());
            permitted.remove(PullRequestContentSource.DIFF);
            permittedRequest = request.withPermittedSources(permitted);
        }
        ScmCaptureContext context = new ScmCaptureContext(
                pinned,
                permittedRequest,
                occasion != null ? occasion.work() : ScmCapturedWork.of(work, review),
                preparation,
                clock.instant(),
                occasion != null ? occasion.captured() : null);
        return compose(context);
    }

    @SuppressWarnings("ReferenceEquality") // Throwable.addSuppressed rejects the same exception instance.
    private CaptureContribution compose(ScmCaptureContext context) {
        List<CaptureContribution> contributions = new ArrayList<>();
        try {
            if (context.pinned().item().kind().equals(ScmItemEvents.ISSUE))
                contributions.add(
                        issueContent.capture(context, context.request().sources()));
            else {
                contributions.add(pullContent.capture(context, context.request().sources()));
                contributions.add(
                        generalComments.capture(context, context.request().sources()));
                contributions.add(threads.capture(context, context.request().sources()));
                contributions.add(linked.capture(context, context.request().sources()));
                contributions.add(
                        repositoryTree.capture(context, context.request().sources()));
            }
            return ScmCaptureContributions.combine(contributions);
        } catch (RuntimeException failure) {
            try {
                CaptureContribution.closeAll(contributions);
            } catch (Exception cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
