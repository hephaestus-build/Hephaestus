package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedResults;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasis;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrivateProgressDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PublicFeedbackHistoryDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import java.io.Serial;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Admission is fenced by workspace, review, job, worker and exact execution attempt. */
@Service
@ConditionalOnCaptureRole
public class ResultAdmissionService {
    public static final String INADMISSIBLE_REASON_CODE = "inadmissible_results";
    public static final String INTERRUPTED_AFTER_ADMISSION =
            "The review stopped after its observations were admitted. The recorded observations are kept.";
    private final ReviewJobRepository jobs;
    private final ReviewOutputService output;
    private final AdmittedResultQuery proof;
    private final ReviewJobAssignments assignments;
    private final AdmittedResultProjectionService projection;
    private final JobEvidenceFiles files;
    private final ReviewHistoryContentSource history;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final TransactionTemplate historySnapshot;
    private final ReviewCompositionBasisRepository bases;
    private final FeedbackCompositionService composition;
    private final ReviewWriteGuard writeGuard;

    public ResultAdmissionService(
            ReviewJobRepository jobs,
            ReviewOutputService output,
            AdmittedResultQuery proof,
            ReviewJobAssignments assignments,
            AdmittedResultProjectionService projection,
            JobEvidenceFiles files,
            ReviewHistoryContentSource history,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            ReviewCompositionBasisRepository bases,
            FeedbackCompositionService composition,
            ReviewWriteGuard writeGuard) {
        this.jobs = jobs;
        this.output = output;
        this.proof = proof;
        this.assignments = assignments;
        this.projection = projection;
        this.files = files;
        this.history = history;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        // Its own snapshot keeps the public page and its publication context on the same database state.
        this.historySnapshot = new TransactionTemplate(manager);
        this.historySnapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.historySnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.historySnapshot.setReadOnly(true);
        this.bases = bases;
        this.composition = composition;
        this.writeGuard = writeGuard;
    }

    public AdmissionResponseDTO admit(AdmissionIdentity identity, AdmissionRequestDTO request) {
        String digest = ProvenanceDigest.sha256Hex(mapper.writeValueAsBytes(request));
        ReviewJob candidate = Objects.requireNonNull(transactions.execute(status -> ownedJob(identity)));
        requireIdentity(candidate, request.reviewId(), request.jobId(), request.attempt());
        if (isContinuation(candidate)) throw new StaleAttemptException();
        PreparedResults prepared = isAdmitted(candidate) ? null : output.prepare(candidate, request);
        ReviewJob recorded = Objects.requireNonNull(transactions.execute(status -> {
            ReviewJob owned = ownedJob(identity);
            requireIdentity(owned, request.reviewId(), request.jobId(), request.attempt());
            if (isAdmitted(owned)) {
                if (!digest.equals(owned.getAdmissionDigest())) throw new AdmissionConflictException();
            } else {
                Objects.requireNonNull(prepared).record(owned);
                owned.setAdmissionDigest(digest);
                owned.setAdmissionAttempt(identity.attempt());
                jobs.saveAndFlush(owned);
                retainCompositionInputs(identity, owned, digest);
            }
            return owned;
        }));
        return admittedResponse(recorded);
    }

    private void retainCompositionInputs(AdmissionIdentity identity, ReviewJob job, String digest) {
        var review = assignments.review(job);
        if (review.kind() != ReviewKind.ITEM) return;
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ReviewCompositionBasis.RETENTION);
        // A copy of an occasion's retained record is used no longer than the record itself.
        var occasionExpiry = review.evidenceExpiresAt();
        if (occasionExpiry != null) {
            if (!occasionExpiry.isAfter(now)) return;
            if (occasionExpiry.isBefore(expiresAt)) expiresAt = occasionExpiry;
        }
        var copy = files.completeReceipt(identity).orElse(null);
        if (copy == null || !files.holdVouchingReceipt(identity, copy)) return;
        var original = files.originalCompositionInputs(identity).orElse(null);
        if (original == null) return;
        var basis = new ReviewCompositionBasis();
        basis.setJobId(job.getId());
        basis.setWorkspaceId(job.getWorkspaceId());
        basis.setAdmissionAttempt(job.getAttempt());
        basis.setAdmissionDigest(digest);
        basis.setBasisVersion(ReviewCompositionBasis.VERSION);
        basis.setBasisDigest(ReviewCompositionBasis.digest(original));
        basis.setCopyId(copy);
        basis.setExpiresAt(expiresAt);
        var payload = mapper.createObjectNode();
        var staged = payload.putObject("files");
        original.forEach((path, bytes) -> staged.put(path, Base64.getEncoder().encodeToString(bytes)));
        basis.setPayload(payload);
        bases.save(basis);
    }

    public AdmissionResponseDTO admittedResponse(ReviewJob job) {
        if (!isAdmitted(job)) throw new AdmissionConflictException();
        var admitted = proof.forAttempt(job.getWorkspaceId(), job.getReviewId(), job.getId(), citationAttempt(job));
        var results = projection.results(job, admitted);
        var withheld = new ArrayList<AdmissionResponseDTO.Withheld>();
        JsonNode capture = job.getCapture();
        if (capture != null)
            for (var entry : capture.path("withheld")) {
                withheld.add(new AdmissionResponseDTO.Withheld(
                        entry.path("practiceSlug").asString(),
                        entry.path("itemId").asString(),
                        entry.path("reason").asString()));
            }
        FeedbackCompositionService.Read privateRead = null;
        var progress = PrivateFeedbackProgress.NONE;
        if (assignments.review(job).kind() == ReviewKind.ITEM && !admitted.isEmpty()) {
            progress = composition.privateProgress(job, admitted);
            var identity = new AdmissionIdentity(
                    job.getId(), job.getWorkspaceId(), job.getAttempt(), Objects.requireNonNull(job.getLeaseId()));
            privateRead = files.indexLateRead(
                    identity,
                    () -> Objects.requireNonNull(transactions.execute(status -> {
                        ownedJob(identity);
                        return composition.read(job, admitted);
                    })),
                    read -> new JobEvidenceFiles.LateCopies(read.copies()));
        }
        return new AdmissionResponseDTO(
                AdmissionResponseDTO.CONTRACT_VERSION,
                job.getReviewId(),
                job.getId(),
                citationAttempt(job),
                Objects.requireNonNull(job.getAdmissionDigest()),
                results,
                withheld,
                privateRead == null ? null : FeedbackCompositionService.page(privateRead),
                privateRead == null ? null : FeedbackCompositionService.conversation(privateRead),
                privateRead == null ? null : FeedbackCompositionService.privateHistory(privateRead),
                assignments.publicReviewed(job),
                new PrivateProgressDTO(
                        progress.decidedResultIds().stream()
                                .sorted(Comparator.comparing(UUID::toString))
                                .toList(),
                        progress.practicePageDeliveries(),
                        progress.conversationDeliveries()));
    }

    public PrepareFeedbackResponseDTO prepareFeedback(AdmissionIdentity identity, PrepareFeedbackRequestDTO request) {
        var context = files.indexLateRead(
                identity,
                () -> Objects.requireNonNull(transactions.execute(status -> {
                    var owned = ownedJob(identity);
                    requireIdentity(owned, request.reviewId(), request.jobId(), request.attempt());
                    if (!isAdmitted(owned) || assignments.review(owned).kind() != ReviewKind.ITEM)
                        throw new AdmissionConflictException();
                    return composition.readForPreparation(
                            owned,
                            proof.forAttempt(
                                    owned.getWorkspaceId(),
                                    owned.getReviewId(),
                                    owned.getId(),
                                    citationAttempt(owned)));
                })),
                read -> new JobEvidenceFiles.LateCopies(read.copies()));
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewJob job = ownedJob(identity);
            requireIdentity(job, request.reviewId(), request.jobId(), request.attempt());
            if (!isAdmitted(job) || assignments.review(job).kind() != ReviewKind.ITEM)
                throw new AdmissionConflictException();
            var rows = proof.forAttempt(job.getWorkspaceId(), job.getReviewId(), job.getId(), citationAttempt(job));
            Set<UUID> resultIds = rows.stream().map(row -> row.id()).collect(Collectors.toSet());
            var allowed = new HashSet<>(resultIds);
            context.support().stream().map(row -> row.resultId()).forEach(allowed::add);
            if (!allowed.containsAll(request.basisResultIds())) throw new AdmissionConflictException();
            var anchors = projection.anchors(job, rows);
            var response = composition.prepare(
                    job,
                    request,
                    rows,
                    context,
                    anchors,
                    request.publicComposition() && assignments.publicReviewed(job));
            jobs.saveAndFlush(job);
            return response;
        }));
    }

    /** A review on the work that an earlier attempt prepared, with the admitted anchor of each of its notes. */
    public record PreparedReview(
            @Nullable String summary,
            List<FeedbackCompositionTrace.Support> summaryBasis,
            List<PreparedNote> notes,
            List<FeedbackCompositionTrace.QuietDecision> withheld) {}

    /** One placed note of a prepared review, at the citation it was composed on. */
    public record PreparedNote(
            String body, List<FeedbackCompositionTrace.Support> basedOn, ComposedFeedback.Anchor anchor) {}

    /**
     * The review on the work that an earlier attempt of the job prepared, for the private feedback composed after it.
     * Each placed note is mapped back to the admitted citation it rests on. Empty when the review prepared none, or
     * when its review on the work only records that it was not composed.
     */
    public Optional<PreparedReview> preparedReview(ReviewJob job) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var stored = composition.preparedReviewOnWork(job).orElse(null);
            if (stored == null) return Optional.<PreparedReview>empty();
            var trace = stored.trace();
            var rows = proof.forAttempt(job.getWorkspaceId(), job.getReviewId(), job.getId(), citationAttempt(job));
            var anchorsAt = new HashMap<FeedbackAnchor, List<ComposedFeedback.Anchor>>();
            projection
                    .anchors(job, rows)
                    .forEach((anchor, location) -> anchorsAt
                            .computeIfAbsent(location, ignored -> new ArrayList<>())
                            .add(anchor));
            var notes = new ArrayList<PreparedNote>();
            for (var note : stored.notes()) {
                var support = support(trace, FeedbackCopyKind.LINE_NOTE, note.position());
                var problems = support.stream()
                        .map(FeedbackCompositionTrace.Support::problemId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                anchorsAt.getOrDefault(note.anchor(), List.of()).stream()
                        .filter(anchor -> problems.contains(anchor.problemId()))
                        .min(Comparator.comparingInt(ComposedFeedback.Anchor::citationIndex))
                        .ifPresent(anchor -> notes.add(new PreparedNote(note.body(), support, anchor)));
            }
            var summaryBasis = support(trace, FeedbackCopyKind.SUMMARY, 0);
            String summary = summaryBasis.isEmpty() ? null : stored.summary();
            var withheld = trace.withheld().stream()
                    .filter(decision -> !decision.reason().equals(FeedbackCompositionTrace.QuietDecision.NOT_COMPOSED))
                    .toList();
            if (summary == null && notes.isEmpty() && withheld.isEmpty()) return Optional.<PreparedReview>empty();
            return Optional.of(new PreparedReview(summary, summaryBasis, notes, withheld));
        }));
    }

    private static List<FeedbackCompositionTrace.Support> support(
            FeedbackCompositionTrace trace, FeedbackCopyKind kind, int position) {
        return trace.copies().stream()
                .filter(copy -> copy.kind() == kind && copy.position() == position)
                .findFirst()
                .map(FeedbackCompositionTrace.CopySupport::basedOn)
                .orElse(List.of());
    }

    public PublicFeedbackHistoryDTO publicFeedbackHistory(AdmissionIdentity identity) {
        var retained = files.indexLateRead(
                identity,
                () -> Objects.requireNonNull(transactions.execute(status -> {
                    var job = ownedJob(identity);
                    if (!isAdmitted(job) || assignments.review(job).kind() != ReviewKind.ITEM)
                        throw new PublicHistoryRefusedException("History requires an admitted item review");
                    var reviewed = assignments.load(job);
                    // The owned attempt stays locked while a separate snapshot reads public history and its context.
                    return Objects.requireNonNull(
                            historySnapshot.execute(snapshot -> history.publicSameItemFeedback(job, reviewed)));
                })),
                read -> new JobEvidenceFiles.LateCopies(read.copies()));
        return retained.response();
    }

    public ReviewJob ownedJob(AdmissionIdentity identity) {
        var retained = jobs.findByIdAndWorkspaceId(identity.jobId(), identity.workspaceId())
                .orElseThrow(StaleAttemptException::new);
        writeGuard.lock(identity.workspaceId(), retained.getReviewId());
        var job = jobs.findByIdAndWorkspaceIdForUpdate(identity.jobId(), identity.workspaceId())
                .orElseThrow(StaleAttemptException::new);
        if (job.getStatus() != ReviewJobStatus.RUNNING
                || job.getAttempt() != identity.attempt()
                || !identity.leaseId().equals(job.getLeaseId())
                || job.getLeasedUntil() == null
                || !job.getLeasedUntil().isAfter(jobs.databaseClock())) throw new StaleAttemptException();
        return job;
    }

    private static void requireIdentity(ReviewJob job, UUID reviewId, UUID jobId, int attempt) {
        if (!job.getReviewId().equals(reviewId) || !job.getId().equals(jobId) || job.getAttempt() != attempt)
            throw new StaleAttemptException();
    }

    public static boolean isAdmitted(ReviewJob job) {
        return job.getAdmissionAttempt() != null && job.getAdmissionDigest() != null;
    }

    /** The private feedback this review's attempts already prepared on its admitted results, per destination. */
    public PrivateFeedbackProgress privateProgress(ReviewJob job) {
        if (!isAdmitted(job) || assignments.review(job).kind() != ReviewKind.ITEM) return PrivateFeedbackProgress.NONE;
        var admitted = proof.forAttempt(job.getWorkspaceId(), job.getReviewId(), job.getId(), citationAttempt(job));
        return admitted.isEmpty() ? PrivateFeedbackProgress.NONE : composition.privateProgress(job, admitted);
    }

    public static int citationAttempt(ReviewJob job) {
        return Objects.requireNonNull(job.getAdmissionAttempt());
    }

    public static boolean isContinuation(ReviewJob job) {
        return isAdmitted(job) && citationAttempt(job) != job.getAttempt();
    }

    public record AdmissionIdentity(UUID jobId, long workspaceId, int attempt, UUID leaseId) {}

    public static class StaleAttemptException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public static class PublicHistoryRefusedException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public PublicHistoryRefusedException(String message) {
            super(message);
        }

        public PublicHistoryRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
