package de.tum.cit.aet.hephaestus.practices.review.report;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultMark;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScanEvidencePolicy {
    private final ResultMarkRepository marks;
    private final EvidenceAuthorization authorization;

    public Set<UUID> permitted(Review review, Collection<Result> rows) {
        return permitted(review.getWorkspaceId(), rows);
    }

    public Set<UUID> unmarked(Review review, Collection<Result> rows) {
        if (rows.isEmpty()) return Set.of();
        var marked = marks
                .findByWorkspaceIdAndResultIdInAndRemovedAtIsNull(
                        review.getWorkspaceId(),
                        rows.stream().map(Result::getId).toList())
                .stream()
                .map(ResultMark::getResultId)
                .collect(Collectors.toSet());
        return rows.stream()
                .filter(r -> !marked.contains(r.getId()))
                .map(Result::getId)
                .collect(Collectors.toSet());
    }

    public Set<UUID> permitted(long workspaceId, Collection<Result> rows) {
        return authorization.permitsAll(
                workspaceId, rows.stream().map(Result::facts).toList(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW);
    }
}
