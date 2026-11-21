package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * What a composition reads for one attempt: its plan, which is the request it composes for and the admitted results,
 * and the retained basis that a session renders from. The sessions and the closing of a composition read both.
 */
@Service
@ConditionalOnAgentRuntime
public class CompositionPlans {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ResultAdmissionService admission;
    private final ReviewCompositionContinuation continuation;
    private final ReviewJobAssignments assignments;
    private final ItemDescriptorRegistry items;
    private final ObjectMapper mapper;
    private final CompositionToolTrace toolTrace;

    public CompositionPlans(
            ResultAdmissionService admission,
            ReviewCompositionContinuation continuation,
            ReviewJobAssignments assignments,
            ItemDescriptorRegistry items,
            ObjectMapper mapper,
            CompositionToolTrace toolTrace) {
        this.admission = admission;
        this.continuation = continuation;
        this.assignments = assignments;
        this.items = items;
        this.mapper = mapper;
        this.toolTrace = toolTrace;
    }

    /** What one attempt composes: its identity, the request it composes for and the admitted results. */
    @SuppressWarnings("ArrayRecordComponent") // routing narrows a parsed request, so each reader parses its own
    record Plan(
            ResultAdmissionService.AdmissionIdentity identity,
            byte[] requestBytes,
            PrivateFeedback.Request request,
            Admission state) {}

    /** The attempt's plan, or null when the review has no destination to compose or no admitted result. */
    @Nullable
    Plan plan(ReviewJob job, ResultAdmissionService.AdmissionIdentity identity) {
        return plan(job, identity, Map.of());
    }

    /**
     * The plan as this attempt found it: its sessions render and check what earlier attempts decided, so what this
     * attempt published is its own work, not a decision it found. Closing counts both.
     */
    @Nullable
    Plan found(ReviewJob job, ResultAdmissionService.AdmissionIdentity identity) {
        var own = new LinkedHashMap<String, Set<String>>();
        var decisions = toolTrace.decisions(identity);
        for (var published : decisions) {
            if (!published.published() || published.destination() == FeedbackDestination.ON_WORK) continue;
            decisions.stream()
                    .filter(check -> check.destination() == published.destination()
                            && Objects.equals(check.draftId(), published.draftId())
                            && check.proposal() != null)
                    .findFirst()
                    .ifPresent(check -> mapper.treeToValue(check.proposal(), FeedbackPackage.class)
                            .composition()
                            .forEach(piece -> piece.resultIds()
                                    .forEach(id -> own.computeIfAbsent(
                                                    published.destination().name(), ignored -> new LinkedHashSet<>())
                                            .add(id.toString()))));
        }
        return plan(job, identity, own);
    }

    private @Nullable Plan plan(
            ReviewJob job, ResultAdmissionService.AdmissionIdentity identity, Map<String, Set<String>> own) {
        var review = assignments.review(job);
        var capabilities = items.descriptorFor(review.item().kind())
                .map(ItemDescriptor::copyCapabilities)
                .orElse(Set.of());
        byte[] requestBytes = FeedbackCompositionInputs.request(
                        review.kind(), review.origin(), Set.of(FeedbackDestination.values()), capabilities, mapper)
                .orElse(null);
        if (requestBytes == null) return null;
        var request = PrivateFeedback.Request.of(mapper.readTree(requestBytes)).orElse(null);
        if (request == null) return null;
        var state = admitted(job, own);
        if (state.results().isEmpty()) return null;
        return new Plan(identity, requestBytes, request, state);
    }

    /** The retained inputs a session renders from, with what the turns read of them. */
    record Basis(
            ComposerFiles files,
            CapturedWork captured,
            JsonNode folderIndex,
            JsonNode practiceIndex,
            List<String> practiceSlugs,
            List<String> notReached) {}

    Basis basis(ReviewJob job, Plan plan) {
        var files = ComposerTurns.workspace(continuation.retainedInputs(job), plan.requestBytes(), plan.state());
        JsonNode folderIndex = files.json(SandboxLayout.MANIFEST_PATH, mapper).orElse(JSON.missingNode());
        JsonNode practiceIndex =
                files.json(ComposerFiles.PRACTICE_INDEX, mapper).orElse(JSON.arrayNode());
        return new Basis(
                files,
                new CapturedWork(files, mapper),
                folderIndex,
                practiceIndex,
                ComposerTurns.practiceSlugs(practiceIndex),
                ComposerTurns.notReached(practiceIndex, plan.state()));
    }

    /** The admitted results with the private feedback each destination's prepared pieces already decide. */
    Admission admitted(ReviewJob job) {
        return admitted(job, Map.of());
    }

    /** The admitted results with what each destination's prepared pieces decide, without the results in {@code own}. */
    private Admission admitted(ReviewJob job, Map<String, Set<String>> own) {
        var decided = new LinkedHashMap<String, Set<String>>();
        admission.privateProgress(job).decidedByDestination().forEach((destination, results) -> {
            var ids = new LinkedHashSet<String>();
            results.forEach(id -> ids.add(id.toString()));
            ids.removeAll(own.getOrDefault(destination.name(), Set.of()));
            decided.put(destination.name(), ids);
        });
        return Admission.of(mapper.valueToTree(admission.admittedResponse(job)), decided);
    }
}
