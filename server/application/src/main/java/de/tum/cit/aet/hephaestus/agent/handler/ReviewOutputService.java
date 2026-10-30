package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.candidate.Candidate;
import de.tum.cit.aet.hephaestus.agent.handler.candidate.CandidateParser;
import de.tum.cit.aet.hephaestus.agent.handler.candidate.CandidateRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedResults;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ResultsRefusedException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAuthority;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.InapplicabilityWarrant;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ReadReceiptStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidence;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@ConditionalOnCaptureRole
public class ReviewOutputService {
    private final ReviewJobAssignments assignments;
    private final CandidateEvidenceVerifier verifier;
    private final ResultStore results;
    private final ReviewAssignmentStore assignmentStore;
    private final JobEvidenceFiles files;
    private final ClasspathSourceCatalogRegistry catalogs;
    private final ObjectMapper mapper;

    public ReviewOutputService(
            ReviewJobAssignments assignments,
            CandidateEvidenceVerifier verifier,
            ResultStore results,
            ObjectMapper mapper,
            JobEvidenceFiles files,
            ClasspathSourceCatalogRegistry catalogs,
            ReviewAssignmentStore assignmentStore) {
        this.assignments = assignments;
        this.verifier = verifier;
        this.results = results;
        this.mapper = mapper;
        this.files = files;
        this.catalogs = catalogs;
        this.assignmentStore = assignmentStore;
    }

    public PreparedResults prepareStage(
            ReviewJob job, AdmissionRequestDTO submission, BiConsumer<ReviewJob, List<ResultCandidate>> record) {
        if (!job.getReviewId().equals(submission.reviewId())
                || !job.getId().equals(submission.jobId())
                || job.getAttempt() != submission.attempt()) throw new ResultAdmissionService.StaleAttemptException();
        Map<String, ReviewAssignmentSnapshot> pinned = assignments.admissible(job).stream()
                .collect(Collectors.toUnmodifiableMap(ReviewAssignmentSnapshot::practiceSlug, Function.identity()));
        var seen = new HashSet<String>();
        for (var entry : submission.results()) {
            if (!seen.add(entry.practiceSlug()))
                throw new ResultsRefusedException("ambiguous_practice_results", "A practice repeats in the submission");
            var assignment = pinned.get(entry.practiceSlug());
            if (assignment == null)
                throw new ResultsRefusedException("practice_not_admitted", "The practice is not assigned to this job");
            if (!Long.toString(assignment.item().itemId()).equals(entry.itemId())
                    || !assignment.version().value().equals(entry.itemVersion())) {
                throw new ResultsRefusedException(
                        "wrong_attribution", "The submitted item or version differs from the frozen assignment");
            }
        }
        for (var nonResult : submission.nonResults()) {
            if (!seen.add(nonResult.practiceSlug()))
                throw new ResultsRefusedException("ambiguous_practice_results", "A practice repeats in the submission");
            if (!pinned.containsKey(nonResult.practiceSlug()))
                throw new ResultsRefusedException("practice_not_admitted", "The practice is not assigned to this job");
        }

        var review = assignments.review(job);
        JsonNode originalCapture = Objects.requireNonNull(job.getCapture());
        var manifest = mapper.treeToValue(originalCapture.path("manifest"), JobFolderIndex.class);
        var receipts = submission.results().stream()
                .flatMap(entry -> entry.readReceipts().stream())
                .toList();
        var resolvedManifest = manifest;
        var admitted = new ArrayList<ResultCandidate>();
        var withheld = new LinkedHashMap<String, AdmissionResponseDTO.Withheld>();
        var refusals = new ArrayList<WithheldAssignment>();
        for (var entry : submission.results()) {
            var assignment = Objects.requireNonNull(pinned.get(entry.practiceSlug()));
            Candidate candidate;
            try {
                candidate = CandidateParser.parse(entry.candidate());
            } catch (CandidateRefusedException refused) {
                refusals.add(new WithheldAssignment(
                        assignment, refused.refusal(), Objects.requireNonNull(refused.getMessage())));
                continue;
            }
            if (candidate.coverage() instanceof HostCoverage host
                    && !host.receipts().equals(entry.readReceipts())) {
                refusals.add(new WithheldAssignment(
                        assignment,
                        CandidateRefusal.COVERAGE_INCONSISTENT,
                        "Host coverage differs from the submitted read receipts"));
                continue;
            }
            try {
                resolvedManifest = files.registerReadReceipts(job, resolvedManifest, entry.readReceipts(), catalogs);
            } catch (IllegalArgumentException | IllegalStateException refusal) {
                refusals.add(new WithheldAssignment(
                        assignment,
                        CandidateRefusal.COVERAGE_INCONSISTENT,
                        Objects.requireNonNull(refusal.getMessage())));
                continue;
            }
            var captured = CapturedEvidence.of(resolvedManifest);
            if (review.kind() == ReviewKind.SCAN
                    && candidate.outcome().isMet()
                    && !hasCompleteAssignedRead(assignment, candidate, resolvedManifest, captured)) {
                refusals.add(new WithheldAssignment(
                        assignment,
                        CandidateRefusal.COVERAGE_INCONSISTENT,
                        "A scan MET needs complete host coverage and a complete read of its assigned pinned file"));
                continue;
            }
            try {
                var verified = verifier.verifyCandidate(job, assignment, review.kind(), candidate, captured);
                // After the verifier, so every cited source is one the catalog knows.
                requireDeclaredSources(assignment, review.kind(), verified);
                // A scan's search covers its assigned file, whose complete read the scan check above requires.
                if (review.kind() == ReviewKind.ITEM) requireSearchedAbsence(candidate, captured);
                admitted.add(result(assignment, verified));
            } catch (CandidateEvidenceVerifier.Unverified refusal) {
                refusals.add(new WithheldAssignment(
                        assignment, refusal.refusal(), Objects.requireNonNull(refusal.getMessage())));
            }
        }
        int attempt = job.getAttempt();
        UUID lease = job.getLeaseId();
        JsonNode capture = Objects.requireNonNull(job.getCapture()).deepCopy();
        var retainedManifest = resolvedManifest;
        return owned -> {
            if (!job.getId().equals(owned.getId())
                    || attempt != owned.getAttempt()
                    || !Objects.equals(lease, owned.getLeaseId())
                    || !capture.equals(owned.getCapture())) {
                throw new ResultAdmissionService.StaleAttemptException();
            }
            for (var refusal : refusals) {
                assignmentStore.withhold(
                        owned.getWorkspaceId(), refusal.assignment().id(), refusal.reason(), refusal.message());
                var entry = new AdmissionResponseDTO.Withheld(
                        refusal.assignment().practiceSlug(),
                        Long.toString(refusal.assignment().item().itemId()),
                        refusal.reason().wireValue());
                withheld.put(entry.practiceSlug(), entry);
            }
            for (var nonResult : submission.nonResults()) {
                var assignment = Objects.requireNonNull(pinned.get(nonResult.practiceSlug()));
                assignmentStore.fail(
                        owned.getWorkspaceId(),
                        assignment.id(),
                        nonResult.reason(),
                        "The runtime ended this practice without a result.");
            }
            ObjectNode retained = (ObjectNode) capture.deepCopy();
            retained.set("manifest", mapper.valueToTree(retainedManifest));
            retained.set("readReceipts", mapper.valueToTree(receipts));
            retained.set("withheld", mapper.valueToTree(withheld.values()));
            retained.set("nonResults", mapper.valueToTree(submission.nonResults()));
            owned.setCapture(retained);
            record.accept(owned, List.copyOf(admitted));
        };
    }

    public AdmissionReceipt seal(ReviewJob job, List<ResultCandidate> staged) {
        var admitted = new ArrayList<>(staged);
        var assigned = assignments.load(job);
        var applicability =
                Objects.requireNonNull(job.getCapture()).path("readiness").path("applicability");
        for (var assignment : assigned) {
            var recorded = applicability.path(assignment.practiceSlug());
            if (!recorded.path("absent").asBoolean(false)) continue;
            var check = mapper.treeToValue(recorded, AppliesWhenCheck.class);
            admitted.add(new ResultCandidate(
                    assignment.practice().practiceId(),
                    assignment.practice().practiceVersionId(),
                    assignment.item().itemId(),
                    assignment.version().value(),
                    assignment.developerId(),
                    ResultOutcome.DOES_NOT_APPLY,
                    "This practice does not apply",
                    check.otherwise(),
                    new ResultEvidence(
                            List.of(),
                            null,
                            new InapplicabilityWarrant(
                                    check.clauses().stream()
                                            .map(AppliesWhenCheck.Clause::readFrom)
                                            .distinct()
                                            .toList(),
                                    "At least one Applies when condition must match",
                                    check.otherwise()),
                            List.of()),
                    null,
                    new HostCoverage(true, List.of(), 0, 0, 0, List.of()),
                    List.of()));
        }
        return results.admit(
                job.getWorkspaceId(), job.getReviewId(), job.getId(), job.getAttempt(), List.copyOf(admitted));
    }

    /**
     * Refuses a result that says a checkout lacks something: it names the checkout as searched, or its words say the
     * repository beyond its citations lacks something ({@link AbsenceClaims}), and its receipts hold no search of the
     * whole checkout that ran to the end, captured complete at the searched commit. A complete read of one file is a
     * complete source of that file, not of the checkout (ADR 0064 § Absence). Every other source is staged whole in
     * the job folder. A checkout that the capture did not hold whole is a capture limit, so no search could support
     * the claim. A could-not-determine result says nothing about the work.
     */
    private void requireSearchedAbsence(Candidate candidate, CapturedEvidence captured) {
        if (candidate.outcome() == ResultOutcome.COULD_NOT_DETERMINE
                || !(candidate.coverage() instanceof HostCoverage host)) return;
        var consulted = new ArrayList<SourceKind>();
        var search = candidate.search();
        if (search != null) consulted.addAll(search.consulted());
        var inapplicability = candidate.inapplicability();
        if (inapplicability != null) consulted.addAll(inapplicability.consulted());
        boolean claimed = AbsenceClaims.claimed(candidate);
        if (consulted.isEmpty() && !claimed) return;
        var checkouts = captured.listedSources().stream()
                .filter(kind -> catalogs.requireSource(kind).authority() == SourceAuthority.UPSTREAM_SNAPSHOT)
                .toList();
        var named = consulted.stream().filter(checkouts::contains).findFirst().orElse(null);
        if (named == null && (checkouts.isEmpty() || !claimed)) return;
        var searchable = named == null ? checkouts : List.of(named);
        boolean searched = host.receipts().stream()
                .anyMatch(receipt -> receipt.status() == ReadReceiptStatus.SEARCHED
                        && ".".equals(receipt.path())
                        && searchable.contains(receipt.sourceKind())
                        && captured.completeAt(receipt.sourceKind(), receipt.version()));
        if (searched) return;
        String claim = named != null
                ? "The result names " + named.value() + " as searched"
                : "The result says the repository lacks something beyond its citations";
        if (searchable.stream().noneMatch(captured::capturedWhole))
            throw new CandidateEvidenceVerifier.Unverified(
                    CandidateRefusal.SOURCE_UNAVAILABLE,
                    claim + ", but the capture does not hold the whole checkout, so no search can show that",
                    null);
        throw new CandidateEvidenceVerifier.Unverified(
                CandidateRefusal.CONSULTED_SOURCE_UNREAD,
                claim + ", but its receipts hold no search of the whole checkout to the end",
                null);
    }

    /**
     * Refuses a result that cites, or says it consulted, a source its practice does not declare (ADR 0069). The
     * session's opening and its read tools hold only declared sources, so such a result rests on evidence the practice
     * never asked for, and its freshness would not follow that source. Every session reads the sources of
     * {@link SandboxLayout#READ_THROUGH_GIT} in its checkout. Capture-only Applies when sources do not grant runtime
     * reads. The server creates a proven Does not apply result separately, without a runtime candidate.
     */
    private void requireDeclaredSources(ReviewAssignmentSnapshot assignment, ReviewKind kind, Candidate candidate) {
        var definition = assignment.practice().definition();
        var reads = kind == ReviewKind.SCAN
                ? Objects.requireNonNull(definition.scan()).reads()
                : definition.reads();
        var declared = new HashSet<SourceKind>(SandboxLayout.READ_THROUGH_GIT);
        reads.sources().forEach(read -> declared.add(read.source()));
        var cited = new ArrayList<SourceKind>();
        candidate.allCitations().forEach(citation -> cited.add(citation.sourceKind()));
        var search = candidate.search();
        if (search != null) cited.addAll(search.consulted());
        var inapplicability = candidate.inapplicability();
        if (inapplicability != null) cited.addAll(inapplicability.consulted());
        var undeclared = cited.stream()
                .filter(source -> !declared.contains(source))
                .map(SourceKind::value)
                .distinct()
                .sorted()
                .toList();
        if (undeclared.isEmpty()) return;
        throw new CandidateEvidenceVerifier.Unverified(
                CandidateRefusal.UNDECLARED_SOURCE_CITED,
                "The result cites " + String.join(", ", undeclared) + ", which the practice does not declare",
                null);
    }

    private static boolean hasCompleteAssignedRead(
            ReviewAssignmentSnapshot assignment,
            Candidate candidate,
            JobFolderIndex manifest,
            CapturedEvidence captured) {
        if (!(candidate.coverage() instanceof HostCoverage host) || !host.complete()) return false;
        String path = assignment.item().path();
        String commit = assignment.version().baseVersion();
        if (path == null || commit == null) return false;
        return host.receipts().stream()
                .anyMatch(receipt -> receipt.status() == ReadReceiptStatus.COMPLETE
                        && path.equals(receipt.path())
                        && commit.equals(receipt.version())
                        && receipt.contentDigest() != null
                        && receipt.contentDigest().matches("[0-9a-f]{64}")
                        && captured.availableSources().contains(receipt.sourceKind())
                        && manifest.citableTexts().stream()
                                .anyMatch(text -> text.source()
                                                .equals(receipt.sourceKind().value())
                                        && text.path().equals(path)
                                        && text.side() == CitationSide.NEW
                                        && commit.equals(text.commit())
                                        && text.sha256().equals(receipt.contentDigest())));
    }

    private record WithheldAssignment(ReviewAssignmentSnapshot assignment, CandidateRefusal reason, String message) {}

    private ResultCandidate result(ReviewAssignmentSnapshot assignment, Candidate candidate) {
        var problems = new ArrayList<ProblemCandidate>();
        for (int i = 0; i < candidate.problems().size(); i++) {
            var problem = candidate.problems().get(i);
            problems.add(new ProblemCandidate(
                    i,
                    problem.key(),
                    problem.severity(),
                    problem.summary(),
                    problem.explanation(),
                    problem.citations(),
                    problem.basis(),
                    problem.nextStep()));
        }
        var evidence = new ResultEvidence(
                candidate.citations(), candidate.search(), candidate.inapplicability(), candidate.openQuestions());
        return new ResultCandidate(
                assignment.practice().practiceId(),
                assignment.practice().practiceVersionId(),
                assignment.item().itemId(),
                assignment.version().value(),
                assignment.developerId(),
                candidate.outcome(),
                candidate.summary(),
                candidate.explanation(),
                evidence,
                candidate.example(),
                candidate.coverage(),
                problems);
    }
}
