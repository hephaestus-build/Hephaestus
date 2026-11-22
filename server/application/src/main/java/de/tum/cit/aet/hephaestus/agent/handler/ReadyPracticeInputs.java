package de.tum.cit.aet.hephaestus.agent.handler;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stages the inputs of the practices that an attempt runs: the practice catalog, each practice's inputs, and, for an
 * item review, the coordinates and citations of the practice's previous result.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReadyPracticeInputs {
    private final PracticeCatalogInjector catalog;
    private final PracticeScriptInputs inputs;
    private final ReviewHistoryContentSource history;
    private final ObjectMapper mapper;

    /**
     * Adds the catalog and the inputs of the {@code ready} practices to {@code staged}.
     *
     * @param record the item's record, read only when a practice runs
     */
    void stage(
            Map<String, byte[]> staged,
            ReviewSnapshot review,
            List<ReviewAssignmentSnapshot> ready,
            Map<ReviewAssignmentId, List<String>> grantedContext,
            Supplier<JsonNode> record,
            Instant startedAt) {
        catalog.inject(staged, ready, review.kind(), grantedContext);
        if (!ready.isEmpty()) {
            var item = record.get();
            for (var assignment : ready) {
                JsonNode previous = mapper.nullNode();
                if (review.kind() == ReviewKind.ITEM) {
                    var retained = history.previousResults(assignment);
                    if (!retained.isEmpty()) {
                        var prior = retained.getFirst();
                        var coordinates = mapper.createObjectNode()
                                .put("resultId", prior.resultId().toString())
                                .put("reviewedAt", prior.recordedAt().toString())
                                .put("origin", prior.origin().name())
                                .put("itemVersion", prior.itemVersion())
                                .put("outcome", prior.outcome().name());
                        var priorEvidence = prior.evidence();
                        if (priorEvidence != null) {
                            coordinates.set("citations", previousCitations(priorEvidence));
                            previous = coordinates;
                        }
                    }
                }
                staged.putAll(inputs.stage(review, assignment, item, startedAt, previous));
            }
        }
    }

    private JsonNode previousCitations(JsonNode evidence) {
        var stored = mapper.treeToValue(evidence, ResultEvidence.class);
        return mapper.valueToTree(stored.citations().stream()
                .map(c -> new PreviousCitation(
                        c.sourceKind().value(),
                        c.sourcePath(),
                        c.path(),
                        c.startLine(),
                        c.endLine(),
                        c.side() == null ? null : c.side().name(),
                        c.commit()))
                .toList());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PreviousCitation(
            String sourceKind,
            String sourcePath,
            String path,
            int startLine,
            int endLine,
            @Nullable String side,
            @Nullable String commit) {}
}
