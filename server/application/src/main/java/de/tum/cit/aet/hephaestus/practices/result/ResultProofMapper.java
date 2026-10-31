package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedProblemSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ClaimBasis;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultExample;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class ResultProofMapper {
    private final ProblemRepository problems;
    private final JsonMapper json;

    public Map<UUID, AdmittedResultSnapshot> snapshots(long workspaceId, Collection<Result> results) {
        var children = problems
                .findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(
                        workspaceId, results.stream().map(Result::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(Problem::getResultId));
        return results.stream()
                .collect(Collectors.toMap(
                        Result::getId,
                        result -> snapshot(result, children.getOrDefault(result.getId(), List.of())),
                        (left, right) -> left));
    }

    public AdmittedResultSnapshot snapshot(Result result) {
        return snapshot(
                result,
                problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(result.getWorkspaceId(), result.getId()));
    }

    private AdmittedResultSnapshot snapshot(Result result, List<Problem> storedChildren) {
        if (result.isMigratedHistory()
                || result.getPracticeVersionId() == null
                || result.getItemVersion() == null
                || result.getSummary() == null
                || result.getExplanation() == null
                || result.getEvidence() == null
                || result.getCoverage() == null)
            throw new IllegalArgumentException("Archived or redacted proof cannot be admitted.");
        var children = storedChildren.stream()
                .map(p -> {
                    if (p.getBasis() == null)
                        throw new IllegalArgumentException("The problem has no verifiable basis.");
                    List<EvidenceCitation> citations = json.convertValue(
                            p.getCitations(),
                            json.getTypeFactory().constructCollectionType(List.class, EvidenceCitation.class));
                    return new AdmittedProblemSnapshot(
                            p.getId(),
                            new ProblemCandidate(
                                    p.getPosition(),
                                    p.getKey(),
                                    p.getSeverity(),
                                    p.getSummary(),
                                    p.getExplanation(),
                                    citations,
                                    json.convertValue(p.getBasis(), ClaimBasis.class),
                                    p.getNextStep()));
                })
                .toList();
        return new AdmittedResultSnapshot(
                result.getId(),
                new ResultCandidate(
                        result.getPracticeId(),
                        result.getPracticeVersionId(),
                        result.getItemId(),
                        result.getItemVersion(),
                        result.getDeveloperId(),
                        result.getOutcome(),
                        result.getSummary(),
                        result.getExplanation(),
                        json.convertValue(result.getEvidence(), ResultEvidence.class),
                        result.getExample() == null
                                ? null
                                : json.convertValue(result.getExample(), ResultExample.class),
                        json.convertValue(result.getCoverage(), ResultCoverage.class),
                        children.stream()
                                .map(AdmittedProblemSnapshot::candidate)
                                .toList()),
                children);
    }
}
