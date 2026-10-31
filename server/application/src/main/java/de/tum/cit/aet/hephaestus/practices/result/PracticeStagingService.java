package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewInvocationFence;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewStagedPayloadRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class PracticeStagingService implements PracticeStagingStore {
    private final ReviewInvocationFence fence;
    private final ReviewItemRepository items;
    private final ReviewStagedPayloadRepository payloads;
    private final ResultAdmissionPolicy policy;
    private final JsonMapper json;
    private final Clock clock;
    private final ProductItemQuery source;

    @Override
    @Transactional(readOnly = true)
    public Optional<StagedPractice> receipt(
            long workspaceId, UUID reviewId, UUID jobId, ReviewAssignmentId assignment) {
        if (!reviewId.equals(assignment.reviewId())) throw conflict();
        return items.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        workspaceId, reviewId, assignment.practiceId(), assignment.itemId())
                .filter(row -> jobId.equals(row.getJobId()) && row.getStagedAttempt() != null)
                .map(this::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StagedPractice> staged(long workspaceId, UUID reviewId, UUID jobId) {
        return items
                .findByWorkspaceIdAndReviewIdAndJobIdOrderByPracticeIdAscItemIdAsc(workspaceId, reviewId, jobId)
                .stream()
                .filter(row -> row.getStagedAttempt() != null)
                .map(this::snapshot)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean workChanged(long workspaceId, UUID reviewId, UUID jobId) {
        return items
                .findByWorkspaceIdAndReviewIdAndJobIdOrderByPracticeIdAscItemIdAsc(workspaceId, reviewId, jobId)
                .stream()
                .filter(row -> row.getState() == ReviewItemState.STAGED)
                .anyMatch(row -> switch (source.currentWorkVersion(workspaceId, row.getItemId())) {
                    case ProductItemQuery.CurrentWorkVersion.Known known ->
                        !known.value().equals(row.getItemVersion());
                    case ProductItemQuery.CurrentWorkVersion.Removed.INSTANCE -> true;
                    case ProductItemQuery.CurrentWorkVersion.Unknown.INSTANCE -> false;
                });
    }

    @Override
    @Transactional
    @WorkspaceAgnostic("Bounded retention of staging columns; each row joins its owning job and workspace")
    public int discardUnusable(Instant now, int limit) {
        return payloads.discardUnusable(now, limit);
    }

    @Override
    @Transactional
    public void stage(long workspaceId, UUID jobId, StagedPractice submission) {
        var assignment = submission.assignment();
        var reviewId = assignment.reviewId();
        int attempt = submission.attempt();
        String digest = submission.digest();
        ResultCandidate candidate = submission.candidate();
        JsonNode evidence = submission.evidence();
        var review = fence.lock(workspaceId, reviewId, jobId, attempt);
        if (!reviewId.equals(assignment.reviewId())) throw conflict();
        var row = items.lockAssignment(workspaceId, reviewId, assignment.practiceId(), assignment.itemId())
                .orElseThrow();
        if (!jobId.equals(row.getJobId())) throw conflict();
        if (row.getStagedAttempt() != null) {
            if (!digest.equals(snapshot(row).digest())) throw conflict();
            return;
        }
        if (candidate != null) {
            if (row.getState() != ReviewItemState.LISTED
                    || candidate.practiceId() != assignment.practiceId()
                    || candidate.itemId() != assignment.itemId()
                    || !candidate.itemVersion().equals(row.getItemVersion())) throw conflict();
            var reason = policy.assess(workspaceId, review, jobId).reason(candidate.developerId());
            var refusal = ResultAdmissionWriter.refusal(candidate);
            if (reason == null && refusal != null) reason = refusal.wireValue();
            if (reason != null) {
                row.setState(ReviewItemState.WITHHELD);
                row.setReason(reason);
                candidate = null;
            }
        } else if (row.getState() != ReviewItemState.WITHHELD) throw conflict();
        row.setStagedAttempt(attempt);
        row.setAttemptedAt(clock.instant());
        var payload = json.createObjectNode().put("digest", digest);
        payload.set("candidate", json.valueToTree(candidate));
        payload.set("evidence", evidence);
        payload.put("reason", row.getReason());
        if (payloads.write(
                        workspaceId,
                        reviewId,
                        assignment.practiceId(),
                        assignment.itemId(),
                        json.writeValueAsString(payload))
                != 1) throw conflict();
        if (candidate != null) row.setState(ReviewItemState.STAGED);
    }

    @Override
    @Transactional
    public void refuseCopy(long workspaceId, UUID jobId, int attempt, ReviewAssignmentId assignment) {
        fence.lock(workspaceId, assignment.reviewId(), jobId, attempt);
        var row = items.lockAssignment(workspaceId, assignment.reviewId(), assignment.practiceId(), assignment.itemId())
                .orElseThrow();
        if (!jobId.equals(row.getJobId())) throw conflict();
        if (row.getState() != ReviewItemState.STAGED) return;
        var receipt = snapshot(row);
        row.setState(ReviewItemState.WITHHELD);
        row.setReason("COPY_AUTHORITY_UNAVAILABLE");
        var payload = json.createObjectNode().put("digest", receipt.digest());
        payload.putNull("candidate");
        payload.putObject("evidence");
        payload.put("reason", row.getReason());
        if (payloads.write(
                        workspaceId,
                        assignment.reviewId(),
                        assignment.practiceId(),
                        assignment.itemId(),
                        json.writeValueAsString(payload))
                != 1) throw conflict();
    }

    private StagedPractice snapshot(ReviewItem row) {
        var payload = json.readTree(payloads.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                        row.getWorkspaceId(), row.getReviewId(), row.getPracticeId(), row.getItemId())
                .orElseThrow());
        return new StagedPractice(
                new ReviewAssignmentId(row.getReviewId(), row.getPracticeId(), row.getItemId()),
                Objects.requireNonNull(row.getStagedAttempt()),
                payload.path("digest").asString(),
                payload.path("candidate").isNull()
                        ? null
                        : json.treeToValue(payload.path("candidate"), ResultCandidate.class),
                payload.path("evidence"),
                row.getReason());
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "This practice no longer accepts a staged result");
    }
}
