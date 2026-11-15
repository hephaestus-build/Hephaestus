package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidenceQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class ResultEvidenceQueryService implements ResultEvidenceQuery {
    private final ProblemRepository problems;

    @Override
    public Map<UUID, List<JsonNode>> citations(long workspaceId, Collection<Result> results) {
        var identified =
                results.stream().filter(result -> result.getId() != null).toList();
        if (identified.isEmpty()) return Map.of();
        Map<UUID, List<Problem>> problemsByResult = problems
                .findByWorkspaceIdAndResultIdInOrderByResultIdAscPositionAsc(
                        workspaceId, identified.stream().map(Result::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(Problem::getResultId));
        var citations = new HashMap<UUID, List<JsonNode>>();
        for (var result : identified) {
            UUID id = Objects.requireNonNull(result.getId());
            citations.put(id, ResultCitations.all(result, problemsByResult.getOrDefault(id, List.of())));
        }
        return Map.copyOf(citations);
    }
}
