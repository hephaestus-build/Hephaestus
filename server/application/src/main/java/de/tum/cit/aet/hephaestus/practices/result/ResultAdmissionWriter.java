package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.review.ReviewInvocationFence;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.CitationVerificationStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.WithheldItem;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ResultAdmissionWriter {
    private final ReviewInvocationFence fence;
    private final ReviewPracticeRepository practices;
    private final ReviewItemRepository items;
    private final ReviewAssignmentQuery assignments;
    private final ResultAdmissionPolicy policy;
    private final ResultClaimWriter claims;
    private final ResultRepository results;
    private final ResultProofMapper proof;
    private final JsonMapper json;
    private final Clock clock;

    public AdmissionReceipt admitLocked(
            long ws, UUID reviewId, UUID jobId, int attempt, List<ResultCandidate> candidates) {
        var review = fence.lock(ws, reviewId, jobId, attempt);
        practices.lockAssignments(ws, reviewId);
        var assessment = policy.assess(ws, review, jobId);
        var copyAuthority = assessment.copyAuthority();
        var admitted = new ArrayList<UUID>();
        var withheld = new ArrayList<WithheldItem>();
        var seen = new HashSet<ReviewAssignmentId>();
        for (var c : candidates.stream()
                .sorted(Comparator.comparingLong(ResultCandidate::practiceId)
                        .thenComparingLong(ResultCandidate::itemId))
                .toList()) {
            var id = new ReviewAssignmentId(reviewId, c.practiceId(), c.itemId());
            if (!seen.add(id)) throw conflict("An assignment occurs twice in this request.");
            var member = items.lockAssignment(ws, reviewId, c.practiceId(), c.itemId())
                    .orElseThrow(() -> conflict("The candidate has no frozen assignment."));
            var assignment = assignments
                    .find(ws, id)
                    .orElseThrow(() -> conflict("The assignment has no verifiable pinned facts."));
            if (!jobId.equals(member.getJobId())
                    || c.practiceVersionId() != assignment.practice().practiceVersionId()
                    || !c.itemVersion().equals(assignment.version().value())
                    || !Objects.equals(c.developerId(), assignment.developerId()))
                throw conflict("The candidate does not match its frozen assignment.");
            var currentReason = assessment.reason(c.developerId());
            if (currentReason != null) {
                if (member.getState() != ReviewItemState.LISTED && member.getState() != ReviewItemState.STAGED)
                    throw conflict("Current processing policy no longer permits this proof.");
                member.setState(ReviewItemState.WITHHELD);
                member.setReason(currentReason);
                member.setAttemptedAt(clock.instant());
                withheld.add(new WithheldItem(c.practiceId(), c.itemId(), currentReason));
                continue;
            }
            if (member.getState() == ReviewItemState.JUDGED) {
                if (member.getDetail() == null
                        || !member.getDetail().has("admissionAttempt")
                        || member.getDetail().path("admissionAttempt").asInt() != attempt)
                    throw conflict("Another attempt admitted this assignment.");
                var prior = results.findByIdAndWorkspaceId(Objects.requireNonNull(member.getResultId()), ws)
                        .orElseThrow();
                if (!json.valueToTree(proof.snapshot(prior).candidate()).equals(json.valueToTree(c)))
                    throw conflict("The admitted body cannot change.");
                admitted.add(prior.getId());
                continue;
            }
            if (member.getState() == ReviewItemState.WITHHELD) {
                withheld.add(new WithheldItem(c.practiceId(), c.itemId(), Objects.requireNonNull(member.getReason())));
                continue;
            }
            if (member.getState() != ReviewItemState.LISTED && member.getState() != ReviewItemState.STAGED)
                throw conflict("This assignment is already terminal.");
            var refusal = refusal(c);
            if (refusal != null) {
                member.setState(ReviewItemState.WITHHELD);
                member.setReason(refusal.wireValue());
                member.setAttemptedAt(clock.instant());
                withheld.add(new WithheldItem(c.practiceId(), c.itemId(), refusal.wireValue()));
                continue;
            }
            var at = clock.instant();
            if (review.getAdmittedAt() == null) review.setAdmittedAt(at);
            var result = claims.write(ws, review, c, at);
            member.setState(ReviewItemState.JUDGED);
            member.setResultId(result.getId());
            member.setAttemptedAt(at);
            ObjectNode detail = member.getDetail() == null
                    ? json.createObjectNode()
                    : (ObjectNode) member.getDetail().deepCopy();
            detail.put("admissionAttempt", attempt);
            detail.set("custody", json.valueToTree(copyAuthority));
            member.setDetail(detail);
            admitted.add(result.getId());
        }
        return new AdmissionReceipt(admitted, withheld);
    }

    static @Nullable CandidateRefusal refusal(ResultCandidate c) {
        if (c.summary().isBlank()
                || c.summary().length() > 160
                || c.explanation().isBlank()
                || c.explanation().length() > 10000) return CandidateRefusal.MALFORMED_CANDIDATE;
        if ((c.outcome() == ResultOutcome.NOT_MET) != !c.problems().isEmpty())
            return CandidateRefusal.OUTCOME_PROBLEM_MISMATCH;
        var keys = new HashSet<String>();
        var positions = new HashSet<Integer>();
        for (var p : c.problems()) {
            if (!positions.add(p.position())
                    || p.position() < 0
                    || p.summary().isBlank()
                    || p.summary().length() > 160
                    || p.explanation().isBlank()
                    || p.explanation().length() > 4000
                    || (p.nextStep() != null && p.nextStep().length() > 1000))
                return CandidateRefusal.MALFORMED_CANDIDATE;
            if (p.key() != null && !keys.add(p.key())) return CandidateRefusal.DUPLICATE_PROBLEM_KEY;
            if (p.citations().isEmpty()) return CandidateRefusal.RESULT_CITATIONS_REQUIRED;
            if (p.citations().stream()
                    .anyMatch(x -> x.verification() == null
                            || x.verification().status() != CitationVerificationStatus.VERIFIED))
                return CandidateRefusal.QUOTE_UNVERIFIED;
        }
        if (c.evidence().citations().stream()
                .anyMatch(x ->
                        x.verification() == null || x.verification().status() != CitationVerificationStatus.VERIFIED))
            return CandidateRefusal.QUOTE_UNVERIFIED;
        return null;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
