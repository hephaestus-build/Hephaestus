package de.tum.cit.aet.hephaestus.agent.handler;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.composition.PinnedInlineAnchorQuery;
import de.tum.cit.aet.hephaestus.agent.handler.composition.PinnedInlineAnchors;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedProblemDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.ProjectedCitationDTO;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceCitation;
import de.tum.cit.aet.hephaestus.practices.spi.InapplicabilityWarrant;
import de.tum.cit.aet.hephaestus.practices.spi.SearchWarrant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Projects admitted evidence and its independently checked feedback placement. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class AdmittedResultProjectionService {
    private final ReviewJobAssignments assignments;
    private final PublicReviewEligibility publicEligibility;
    private final PinnedInlineAnchorQuery inlineAnchors;
    private final ObjectMapper mapper;

    public List<AdmittedResultDTO> results(ReviewJob job, List<AdmittedResultSnapshot> admitted) {
        var pinned = assignments.load(job).stream()
                .collect(Collectors.toMap(a -> a.practice().practiceId(), Function.identity()));
        Set<UUID> eligible = publicEligibility.publicResultIds(job, admitted);
        var placement = inlineAnchors.placement(job);
        return admitted.stream()
                .map(row -> {
                    var candidate = row.candidate();
                    var assignment = Objects.requireNonNull(pinned.get(candidate.practiceId()));
                    boolean summaryOnly = !assignment.practice().definition().lineNotes();
                    boolean publicEligible = eligible.contains(row.id());
                    var problems = row.problems().stream()
                            .map(problem -> {
                                var child = problem.candidate();
                                return new AdmittedProblemDTO(
                                        problem.id(),
                                        child.severity(),
                                        child.summary(),
                                        row.id(),
                                        child.position(),
                                        child.key(),
                                        child.explanation(),
                                        projected(child.citations(), !summaryOnly ? placement : null),
                                        child.basis(),
                                        child.nextStep(),
                                        publicEligible);
                            })
                            .toList();
                    JsonNode evidence = mapper.valueToTree(new ProjectedEvidence(
                            projected(candidate.evidence().citations(), null),
                                    candidate.evidence().search(),
                            candidate.evidence().inapplicability(),
                                    candidate.evidence().openQuestions().stream()
                                            .map(question -> new ProjectedQuestion(
                                                    question.openQuestion(),
                                                    question.wouldSettleIt(),
                                                    question.citation() == null
                                                            ? null
                                                            : projected(List.of(question.citation()), null)
                                                                    .getFirst()))
                                            .toList()));
                    var sourceExample = candidate.example();
                    JsonNode example = sourceExample == null
                            ? null
                            : mapper.valueToTree(new ProjectedExample(
                                    projected(List.of(sourceExample.citation()), null)
                                            .getFirst(),
                                    sourceExample.note()));
                    return new AdmittedResultDTO(
                            assignment.practiceSlug(),
                            Long.toString(candidate.itemId()),
                            row.id(),
                            candidate.outcome(),
                            candidate.summary(),
                            candidate.explanation(),
                            evidence,
                            example,
                            candidate.coverage(),
                            problems,
                            summaryOnly,
                            publicEligible);
                })
                .toList();
    }

    public Map<ComposedFeedback.Anchor, FeedbackAnchor> anchors(ReviewJob job, List<AdmittedResultSnapshot> rows) {
        var anchors = new LinkedHashMap<ComposedFeedback.Anchor, FeedbackAnchor>();
        var placement = inlineAnchors.placement(job);
        for (var row : rows)
            for (var problem : row.problems()) {
                var citations = projected(problem.candidate().citations(), placement);
                citations.stream()
                        .filter(ProjectedCitationDTO::anchorable)
                        .forEach(citation -> anchors.put(
                                new ComposedFeedback.Anchor(problem.id(), citation.index()),
                                new FeedbackAnchor(
                                        Objects.requireNonNull(citation.path()),
                                        citation.startLine(),
                                        citation.endLine(),
                                        Objects.requireNonNull(placement)
                                                .placementSide(problem.candidate()
                                                        .citations()
                                                        .get(citation.index())))));
            }
        return Map.copyOf(anchors);
    }

    private List<ProjectedCitationDTO> projected(
            List<EvidenceCitation> citations, @Nullable PinnedInlineAnchors placement) {
        var projected = new ArrayList<ProjectedCitationDTO>();
        for (int index = 0; index < citations.size(); index++) {
            var citation = citations.get(index);
            boolean anchorable = placement != null && placement.permits(citation);
            projected.add(new ProjectedCitationDTO(
                    citation.sourceKind().value(),
                    citation.sourcePath(),
                    citation.path(),
                    citation.side(),
                    citation.commit(),
                    citation.startLine(),
                    citation.endLine(),
                    citation.quote(),
                    citation.verification(),
                    index,
                    anchorable));
        }
        return List.copyOf(projected);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record ProjectedEvidence(
            List<ProjectedCitationDTO> citations,
            @Nullable SearchWarrant search,
            @Nullable InapplicabilityWarrant inapplicability,
            List<ProjectedQuestion> openQuestions) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record ProjectedQuestion(
            String openQuestion,
            String wouldSettleIt,
            @Nullable ProjectedCitationDTO citation) {}

    private record ProjectedExample(ProjectedCitationDTO citation, String note) {}
}
