package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.GeneralReviewCommentContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.IssueContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturePreparation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturedWork;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Prepares the evidence of SCM work: the record an occasion retains, and the capture of the current work or of what an
 * occasion retained. The caller has already pinned the item and checked current access to it.
 */
@Component
@Slf4j
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmWorkCapture {
    static final String NOT_OBSERVED = "NOT_OBSERVED";
    static final String SUPPRESSED = "SUPPRESSED";
    static final String SIZE_LIMIT = "SIZE_LIMIT";

    private final PullRequestContentSource pullContent;
    private final IssueContentSource issueContent;
    private final GeneralReviewCommentContentSource generalComments;
    private final ReviewThreadContentSource threads;
    private final LinkedWorkItemContentSource linked;
    private final RepositoryTreeContentSource repositoryTree;
    private final ScmCaptureContributors contributors;
    private final ItemEventService events;
    private final PersonDataWriteFence fence;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** The item kinds whose event reviews read only what their occasion retained. */
    static boolean retains(ItemKind kind) {
        return kind.equals(ScmItemEvents.PULL_REQUEST) || kind.equals(ScmItemEvents.PULL_REQUEST_REVIEW);
    }

    /**
     * The serialized record of an occasion, or an {@link ItemEventInput#unretained} statement of why it is not kept: a
     * copied person's content may not be written ({@code SUPPRESSED}), or the record exceeds what an occasion may
     * retain ({@code SIZE_LIMIT}).
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ItemCaptureException.class)
    public String retain(ScmOccasionEvidence evidence, long itemId) {
        var participants = evidence.participants();
        // The erasure of any of these people finishes before, or waits until this write commits and then finds it by
        // their native identity, which the record keeps.
        if (!participants.isEmpty()
                && !fence.holdForWrite(participants.stream()
                        .map(ScmOccasionEvidence.Participant::identity)
                        .toList())) return ItemEventInput.unretained(SUPPRESSED);
        String json = mapper.writeValueAsString(evidence);
        if (!ItemEventInput.fits(json)) {
            log.info("Occasion evidence not retained: reason=sizeLimit, itemId={}", itemId);
            return ItemEventInput.unretained(SIZE_LIMIT);
        }
        return json;
    }

    /**
     * The evidence of a pending occasion completed with the diff base its version adds. The caller pins the version
     * only while the occasion's fence holds; the record's state, roles, people and words stay as first retained. A
     * statement that nothing was kept stays as it is, and null stays null.
     */
    @Transactional(readOnly = true, noRollbackFor = ItemCaptureException.class)
    public @Nullable String complete(@Nullable String retained, ItemVersion version) {
        if (retained == null) return null;
        ScmOccasionEvidence evidence;
        try {
            if (mapper.readTree(retained).has(ItemEventInput.UNRETAINED)) return retained;
            evidence = mapper.readValue(retained, ScmOccasionEvidence.class);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Retained occasion evidence is unreadable", unreadable);
        }
        try {
            return mapper.writeValueAsString(evidence.pinned(version));
        } catch (IllegalArgumentException notThisOccasion) {
            // The unchanged pending fence already proved the occasion's material. A different retained record is
            // inconsistent, not an original that was never kept; keep the fault visible for repair and retry.
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED,
                    "Retained occasion evidence does not match its version",
                    notThisOccasion);
        }
    }

    /**
     * The record exactly this occasion of {@code item} retained. Without one it refuses with
     * {@link ItemCaptureCode#OCCASION_EVIDENCE_UNAVAILABLE}; a record that cannot be read is a fault.
     */
    ScmOccasionEvidence occasion(ItemRef item, UUID eventId) {
        var retained = events.evidence(item.workspaceId(), item.itemId(), eventId);
        var json = retained.record();
        if (json == null)
            throw new ItemCaptureException(
                    ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE,
                    "The occasion kept no record of the work: " + retained.unretained());
        try {
            return mapper.readValue(json, ScmOccasionEvidence.class);
        } catch (JacksonException | IllegalArgumentException unreadable) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Retained occasion evidence is unreadable", unreadable);
        }
    }

    /**
     * Captures {@code work} as pinned, or what {@code occasion} retained of it. Every contributor must consent,
     * including the people only the occasion still names.
     */
    CaptureContribution capture(
            ItemSnapshot pinned,
            CaptureRequest request,
            Issue work,
            @Nullable PullRequestReview review,
            @Nullable ScmOccasionEvidence occasion) {
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
                new ScmCapturePreparation(),
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
