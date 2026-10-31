package de.tum.cit.aet.hephaestus.agent.handler;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.context.AppliesWhenEvaluator;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScanItemVersions;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.UnofferedItemCapabilities;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class DefaultReviewExecutionHandler {
    /** A pinned practice whose reads the current source catalog cannot capture for the reviewed item. */
    static final String SOURCE_CONTRACT_UNSUPPORTED = "source_contract_unsupported";
    /** A pinned item practice that declares a review event or About role that its item kind does not offer. */
    static final String ITEM_CAPABILITY_UNSUPPORTED = "item_capability_unsupported";

    private final ReviewJobAssignments assignments;
    private final List<ItemSource> sources;
    private final WorkspaceContextBuilder context;
    private final JobEvidenceFiles files;
    private final PracticeCatalogInjector catalog;
    private final PracticeScriptInputs inputs;
    private final ReviewHistoryContentSource history;
    private final ReviewAssignmentStore capture;
    private final ItemDescriptorRegistry items;
    private final List<ScanItemVersions> scanVersions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AppliesWhenEvaluator applicability;
    private final ResultAdmissionService admission;
    private final ClasspathSourceCatalogRegistry sourceCatalog;

    public PreparedJobInputs prepareInputs(ReviewJob job) {
        var review = assignments.review(job);
        var loaded = assignments.load(job);
        var item = loaded.getFirst().item();
        // A retry runs only what is still open. A practice that an earlier attempt of the job withheld keeps that
        // withholding and its reason, and is neither captured nor run again.
        var ended = capture.endedPractices(job.getWorkspaceId(), review.id(), job.getId());
        var assigned = loaded.stream()
                .filter(assignment -> !ended.contains(assignment.practice().practiceId()))
                .toList();
        var matching = sources.stream().filter(source -> source.supports(item)).toList();
        if (matching.size() != 1) throw new IllegalStateException("The item needs exactly one capture source");
        var source = matching.getFirst();
        // The capture boundary rejects the whole batch for one read it cannot capture, so such a practice is
        // withheld here and the others are captured. A practice that its item kind cannot support is withheld
        // the same way.
        var refused = new LinkedHashMap<ReviewAssignmentSnapshot, ReviewCaptureDecision>();
        for (var assignment : assigned) {
            var refusal = refusal(assignment, item, review.kind());
            if (refusal != null) refused.put(assignment, refusal);
        }
        var pinned = assigned.stream().filter(a -> !refused.containsKey(a)).toList();
        if (pinned.isEmpty()) {
            capture.capture(
                    job.getWorkspaceId(), review.id(), job.getId(), job.getAttempt(), List.copyOf(refused.values()));
            throw new InsufficientEvidenceException(
                    "No assigned practice has a pinned definition that this item supports",
                    new PreparedJobInputs(Map.of(), List.of(), List.of(), null, null));
        }
        var required = pinned.stream()
                .flatMap(a -> (review.kind() == ReviewKind.SCAN
                                ? Objects.requireNonNull(
                                                a.practice().definition().scan())
                                        .reads()
                                : a.practice().definition().reads())
                        .sources().stream())
                .map(read -> read.source())
                .collect(Collectors.toSet());
        for (var assignment : pinned) {
            var conditions = assignment.practice().definition().appliesWhen();
            if (review.kind() == ReviewKind.ITEM && conditions != null)
                required.addAll(applicability.sources(item.kind(), conditions));
        }
        var grantedContext = new LinkedHashMap<ReviewAssignmentId, List<String>>();
        if (review.kind() == ReviewKind.SCAN) {
            var composers = scanVersions.stream()
                    .filter(v -> v.kinds().contains(item.kind()))
                    .toList();
            if (composers.size() != 1)
                throw new IllegalStateException("The scan item needs exactly one version composer");
            var composer = composers.getFirst();
            for (var assignment : pinned)
                grantedContext.put(
                        assignment.id(),
                        composer.contextPaths(
                                assignment.item(),
                                assignment.version(),
                                Objects.requireNonNull(assignment
                                                .practice()
                                                .definition()
                                                .scan())
                                        .context()));
        }
        // An event review captures what its occasion retained, never the work as it changed since.
        var occasion = review.kind() == ReviewKind.ITEM ? review.eventId() : null;
        var snapshot = occasion == null
                ? source.pin(item, pinned.getFirst().version())
                : source.pinOccasion(item, pinned.getFirst().version(), occasion);
        Set<String> scanPaths = null;
        if (review.kind() == ReviewKind.SCAN) {
            scanPaths = new HashSet<>();
            for (var assignment : pinned) {
                var path = assignment.item().path();
                if (path != null) scanPaths.add(path);
                scanPaths.addAll(Objects.requireNonNull(grantedContext.get(assignment.id())));
            }
        }
        files.beginPersonCapture(job);
        try {
            var evidence = context.prepare(
                    snapshot,
                    new CaptureRequest(
                            review.id(),
                            job.getId(),
                            job.getAttempt(),
                            required,
                            SandboxLayout.SOURCE_CONTRACT_VERSION,
                            SandboxLayout.PRACTICE_CONTRACT_VERSION,
                            scanPaths,
                            required,
                            occasion));
            try {
                var manifest = Objects.requireNonNull(evidence.manifest());
                var now = clock.instant();
                var decisions = pinned.stream()
                        .map(a -> readiness(a, manifest, now, review.kind()))
                        .toList();
                var checks = new LinkedHashMap<String, AppliesWhenCheck>();
                for (var assignment : pinned) {
                    if (decisions.stream()
                            .noneMatch(d -> d.practiceSlug().equals(assignment.practiceSlug()) && d.ready())) continue;
                    var check = applicability.evaluate(
                            assignment.practice().definition().appliesWhen(), manifest, evidence.files());
                    if (check != null) checks.put(assignment.practiceSlug(), check);
                }
                var report = new AutomatedReviewReadinessReport(
                        manifest.contractVersion(),
                        manifest.catalogDigest(),
                        item.kind().value(),
                        manifest.capturedAt(),
                        now,
                        decisions,
                        checks);
                var captureDecisions = new ArrayList<>(refused.values());
                for (var decision : decisions) {
                    var assignment = pinned.stream()
                            .filter(a -> a.practiceSlug().equals(decision.practiceSlug()))
                            .findFirst()
                            .orElseThrow();
                    captureDecisions.add(new ReviewCaptureDecision(
                            assignment.practice().practiceId(),
                            decision.ready() ? ReviewPracticeStatus.READY : ReviewPracticeStatus.WITHHELD,
                            decision.ready() ? null : "readiness_refused",
                            captureFacts(decision, checks.get(decision.practiceSlug()))));
                }
                capture.capture(job.getWorkspaceId(), review.id(), job.getId(), job.getAttempt(), captureDecisions);
                var staged = new LinkedHashMap<>(evidence.files());
                var ready = pinned.stream()
                        .filter(a -> decisions.stream()
                                .anyMatch(d -> d.practiceSlug().equals(a.practiceSlug()) && report.runs(d)))
                        .toList();
                catalog.inject(staged, ready, review.kind(), grantedContext);
                if (!ready.isEmpty()) {
                    var record = source.record(snapshot, evidence.files());
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
                        staged.putAll(inputs.stage(
                                review,
                                assignment,
                                record,
                                Objects.requireNonNullElse(job.getStartedAt(), now),
                                previous));
                    }
                }
                staged.put(
                        SandboxLayout.TASK_ENVELOPE_FILENAME,
                        mapper.writeValueAsBytes(TaskEnvelope.practiceReview(
                                review.id(), job.getId(), job.getAttempt(), job.getWorkspaceId())));
                // The attempt's copies of the occasion's record are kept no longer than the record itself.
                var prepared = files.prepare(job, evidence.withFiles(staged), report)
                        .withCopiesExpireAt(review.evidenceExpiresAt());
                if (decisions.stream().noneMatch(AutomatedReviewReadinessDecision::ready))
                    throw new InsufficientEvidenceException(
                            "No assigned practice has its required captured evidence", prepared);
                return prepared;
            } catch (RuntimeException failure) {
                evidence.close();
                throw failure;
            }
        } catch (RuntimeException failure) {
            files.abortPersonCapture(job);
            throw failure;
        }
    }

    /**
     * Returns the withheld decision for a practice that cannot be captured for the item, or null when it can.
     *
     * <p>An item practice must declare only review events and an About role that the item's descriptor offers.
     * Authoring checks the same descriptor facts, but a stored version is not revalidated, and a requested review
     * bypasses event selection. The check does not require any declared event to have occurred. A scan reviews
     * files or repositories rather than the practice's own item kind, so neither field applies to it.
     */
    private @Nullable ReviewCaptureDecision refusal(
            ReviewAssignmentSnapshot assignment, ItemRef item, ReviewKind scope) {
        if (scope == ReviewKind.ITEM) {
            var descriptor = items.descriptorFor(item.kind())
                    .orElseThrow(() -> new IllegalStateException("The captured item kind has no descriptor"));
            var unoffered = UnofferedItemCapabilities.of(
                    descriptor, assignment.practice().definition());
            if (unoffered != null)
                return withheld(
                        assignment,
                        ITEM_CAPABILITY_UNSUPPORTED,
                        new UnofferedCapabilities(
                                assignment.practiceSlug(),
                                clock.instant(),
                                item.kind().value(),
                                unoffered.events(),
                                unoffered.about()));
        }
        var reads = unsupportedReads(assignment, item.kind().value(), scope);
        return reads == null ? null : withheld(assignment, SOURCE_CONTRACT_UNSUPPORTED, reads);
    }

    private ReviewCaptureDecision withheld(ReviewAssignmentSnapshot assignment, String reason, Record facts) {
        return new ReviewCaptureDecision(
                assignment.practice().practiceId(), ReviewPracticeStatus.WITHHELD, reason, mapper.valueToTree(facts));
    }

    /**
     * Returns why the current catalog cannot capture this practice's pinned reads for the item, or null when it can.
     * An optional read counts: dropping it would review the practice with less than its pinned definition.
     */
    private @Nullable UnsupportedReads unsupportedReads(
            ReviewAssignmentSnapshot assignment, String itemKind, ReviewKind scope) {
        var definition = assignment.practice().definition();
        PracticeReads reads = scope == ReviewKind.SCAN
                ? Objects.requireNonNull(definition.scan()).reads()
                : definition.reads();
        var current = sourceCatalog.current();
        var sources = reads.sources().stream()
                .filter(read -> current.source(read.source())
                        .filter(contract -> contract.appliesTo(itemKind))
                        .isEmpty())
                .toList();
        if (reads.contract().equals(current.version().value()) && sources.isEmpty()) return null;
        return new UnsupportedReads(
                assignment.practiceSlug(),
                clock.instant(),
                reads.contract(),
                current.version().value(),
                itemKind,
                sources);
    }

    /** The recorded capture facts of a practice withheld before capture. */
    private record UnsupportedReads(
            String practiceSlug,
            Instant decidedAt,
            String sourceContract,
            String currentSourceContract,
            String itemKind,
            List<PracticeReadSource> unsupportedSources) {}

    /** The recorded facts of an item practice withheld before capture for what its item kind does not offer. */
    private record UnofferedCapabilities(
            String practiceSlug,
            Instant decidedAt,
            String itemKind,
            List<ItemEventName> unofferedEvents,
            @Nullable AboutRole unofferedAbout) {}

    private JsonNode captureFacts(AutomatedReviewReadinessDecision decision, @Nullable AppliesWhenCheck check) {
        ObjectNode facts = mapper.valueToTree(decision);
        if (check != null) facts.set("applicability", mapper.valueToTree(check));
        return facts;
    }

    public void completeWithoutExecution(ReviewJob job) {
        admission.admit(
                new ResultAdmissionService.AdmissionIdentity(
                        job.getId(), job.getWorkspaceId(), job.getAttempt(), Objects.requireNonNull(job.getLeaseId())),
                new AdmissionRequestDTO(3, job.getReviewId(), job.getId(), job.getAttempt(), List.of(), List.of()));
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

    private AutomatedReviewReadinessDecision readiness(
            ReviewAssignmentSnapshot assignment, JobFolderIndex manifest, Instant now, ReviewKind scope) {
        var definition = assignment.practice().definition();
        var reasons = new ArrayList<AutomatedReviewReadinessReason>();
        if (definition.reviewedBy().by() != ReviewedByKind.HEPHAESTUS)
            reasons.add(AutomatedReviewReadinessReason.NO_AUTOMATED_REVIEW);
        var reads = scope == ReviewKind.SCAN
                ? Objects.requireNonNull(definition.scan()).reads()
                : definition.reads();
        var checks = reads.sources().stream()
                .map(read -> {
                    var state = manifest.sources().stream()
                            .filter(s -> s.kind().equals(read.source()))
                            .map(s -> s.state())
                            .findFirst()
                            .orElse(null);
                    var missing = new ArrayList<SourceReadinessReason>();
                    if (read.need() != ReadNeed.OPTIONAL) {
                        if (!(state instanceof SourceCaptureState.Available available)) {
                            missing.add(SourceReadinessReason.SOURCE_NOT_AVAILABLE);
                            if (state instanceof SourceCaptureState.Unavailable unavailable) {
                                switch (unavailable.reasonCode()) {
                                    case REPOSITORY_FETCH_FAILED ->
                                        missing.add(SourceReadinessReason.REPOSITORY_FETCH_FAILED);
                                    case REVIEW_VERSION_UNAVAILABLE ->
                                        missing.add(SourceReadinessReason.REVIEW_VERSION_UNAVAILABLE);
                                    default -> {}
                                }
                            }
                        } else if (read.need() == ReadNeed.COMPLETE
                                && available.completeness() != SourceCompleteness.COMPLETE)
                            missing.add(SourceReadinessReason.SOURCE_INCOMPLETE);
                    }
                    return new SourceReadinessCheck(
                            read.source(),
                            manifest.contractVersion(),
                            now,
                            manifest.capturedAt(),
                            missing.isEmpty(),
                            missing);
                })
                .toList();
        if (checks.stream().anyMatch(check -> !check.meetsRequirements()))
            reasons.add(AutomatedReviewReadinessReason.DECLARED_EVIDENCE_INSUFFICIENT);
        return new AutomatedReviewReadinessDecision(assignment.practiceSlug(), now, reasons.isEmpty(), reasons, checks);
    }
}
