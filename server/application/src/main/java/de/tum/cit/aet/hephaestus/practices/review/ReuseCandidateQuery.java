package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultFreshness;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReuseCandidateQuery {
    private final ReviewAssignmentQuery assignments;
    private final ResultRepository results;
    private final ResultFreshness freshness;
    private final EvidenceAuthorization authorization;
    private final ResultSourceAuthorization sourceAuthorization;
    private final JsonMapper json;

    record ReusableResult(Result result, HistoryCustody custody) {}

    Optional<ReusableResult> findWithCapture(long workspaceId, ReviewAssignmentId id) {
        return find(workspaceId, id)
                .filter(candidate -> freshness.sameCapture(workspaceId, candidate.result(), id.reviewId()));
    }

    Optional<ReusableResult> find(long workspaceId, ReviewAssignmentId id) {
        var assignment = assignments.find(workspaceId, id).orElseThrow();
        var candidate = results.findReuseCandidate(
                workspaceId,
                id.reviewId(),
                id.practiceId(),
                assignment.practice().practiceVersionId(),
                id.itemId(),
                assignment.version().value(),
                assignment.developerId());
        if (candidate.isEmpty()) return Optional.empty();
        var result = candidate.orElseThrow();
        if (!(ResultCoverage.stored(json, result.getCoverage()) instanceof HostCoverage host)
                || !trusted(host)
                || !authorization.permits(workspaceId, result, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
            return Optional.empty();
        if (freshness.states(workspaceId, List.of(result)).get(result.getId()) != Currentness.CURRENT)
            return Optional.empty();
        var captured = sourceAuthorization.custody(result);
        if (!captured.complete()) return Optional.empty();
        return Optional.of(new ReusableResult(result, captured));
    }

    public static boolean trusted(HostCoverage coverage) {
        return coverage.complete()
                && coverage.failedReads() == 0
                && coverage.truncatedReads() == 0
                && !coverage.receipts().isEmpty()
                && coverage.receipts().stream()
                        .allMatch(r -> r.status() == ReadReceiptStatus.ABSENT
                                || (r.status() == ReadReceiptStatus.COMPLETE
                                        && r.contentDigest() != null
                                        && r.contentDigest().matches("[0-9a-f]{64}")));
    }
}
