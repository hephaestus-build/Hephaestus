package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.StaleAttemptException;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasis;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.StageResponseDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore.StagedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Staging and retained evidence; the caller supplies the locked attempt and rechecks its authority before writing. */
@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class PracticeResultStaging {
    private final ReviewOutputService output;
    private final PracticeStagingStore staging;
    private final ReviewAssignmentStore membership;
    private final ReviewJobAssignments assignments;
    private final JobEvidenceFiles files;
    private final ReviewCompositionBasisRepository bases;
    private final ObjectMapper mapper;
    private final PracticeCatalogInjector catalog;

    @Transactional(propagation = Propagation.MANDATORY)
    public StageResponseDTO stage(
            ReviewJob job, AdmissionIdentity identity, AdmissionRequestDTO request, Runnable recheckAuthority) {
        String digest = ProvenanceDigest.sha256Hex(mapper.writeValueAsBytes(request));
        if (request.results().size() + request.nonResults().size() != 1) throw new AdmissionConflictException();
        String slug = request.results().isEmpty()
                ? request.nonResults().getFirst().practiceSlug()
                : request.results().getFirst().practiceSlug();
        var assignment = assignments.load(job).stream()
                .filter(value -> value.practiceSlug().equals(slug))
                .findFirst()
                .orElseThrow(AdmissionConflictException::new);
        var prior = staging.receipt(job.getWorkspaceId(), job.getReviewId(), job.getId(), assignment.id());
        if (prior.isPresent()) {
            if (!digest.equals(prior.orElseThrow().digest())) throw new AdmissionConflictException();
            return new StageResponseDTO(slug, digest);
        }
        if (ResultAdmissionService.isAdmitted(job)) throw new AdmissionConflictException();
        output.prepareStage(job, request, (owned, candidates) -> {
                    var original =
                            files.originalCompositionInputs(identity).orElseThrow(AdmissionConflictException::new);
                    var copy = files.completeReceipt(identity).orElseThrow(AdmissionConflictException::new);
                    if (!files.holdVouchingReceipt(identity, copy)) throw new StaleAttemptException();
                    var expires = Instant.now().plus(ReviewCompositionBasis.RETENTION);
                    var occasionExpiry = assignments.review(owned).evidenceExpiresAt();
                    if (occasionExpiry != null && occasionExpiry.isBefore(expires)) expires = occasionExpiry;
                    var evidence = StagedEvidence.retain(
                            mapper,
                            mapper.treeToValue(
                                    Objects.requireNonNull(owned.getCapture()).path("manifest"), JobFolderIndex.class),
                            candidates.isEmpty() ? null : candidates.getFirst(),
                            original,
                            copy,
                            expires);
                    if (!candidates.isEmpty() && assignments.review(owned).kind() == ReviewKind.ITEM) {
                        var pinned = new LinkedHashMap<String, byte[]>();
                        catalog.inject(pinned, List.of(assignment), ReviewKind.ITEM, Map.of());
                        evidence.set(
                                "practice",
                                mapper.readTree(Objects.requireNonNull(
                                                pinned.get(SandboxLayout.PRACTICES_PREFIX + "index.json")))
                                        .get(0));
                        String path = SandboxLayout.PRACTICES_PREFIX + assignment.practiceSlug() + ".md";
                        ((ObjectNode) evidence.path("files"))
                                .put(
                                        path,
                                        Base64.getEncoder().encodeToString(Objects.requireNonNull(pinned.get(path))));
                    }
                    evidence.set("nonResults", mapper.valueToTree(request.nonResults()));
                    recheckAuthority.run();
                    staging.stage(
                            owned.getWorkspaceId(),
                            owned.getId(),
                            new StagedPractice(
                                    assignment.id(),
                                    owned.getAttempt(),
                                    digest,
                                    candidates.isEmpty() ? null : candidates.getFirst(),
                                    evidence,
                                    null));
                    storeCompositionInputs(owned, copy, expires, original);
                })
                .record(job);
        return new StageResponseDTO(slug, digest);
    }

    public List<StagedPractice> staged(ReviewJob job) {
        return staging.staged(job.getWorkspaceId(), job.getReviewId(), job.getId());
    }

    public boolean hasStaged(ReviewJob job) {
        return membership.hasStaged(job.getWorkspaceId(), job.getReviewId(), job.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refuseCopy(ReviewJob job, StagedPractice practice) {
        staging.refuseCopy(job.getWorkspaceId(), job.getId(), job.getAttempt(), practice.assignment());
    }

    public boolean workChanged(ReviewJob job) {
        return staging.workChanged(job.getWorkspaceId(), job.getReviewId(), job.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void failOpen(ReviewJob job, ReviewStopReason reason) {
        var capture = job.getCapture() == null
                ? mapper.createObjectNode()
                : (ObjectNode) job.getCapture().deepCopy();
        var nonResults = capture.withArray("nonResults");
        for (var assignment : assignments.open(job)) {
            membership.fail(
                    job.getWorkspaceId(),
                    assignment.id(),
                    reason,
                    "The attempt stopped before this practice could finish.");
            nonResults
                    .addObject()
                    .put("practiceSlug", assignment.practiceSlug())
                    .put("reason", reason.name());
        }
        job.setCapture(capture);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AdmissionReceipt sealResults(ReviewJob job, List<StagedPractice> staged) {
        return output.seal(
                job,
                staged.stream()
                        .map(StagedPractice::candidate)
                        .filter(Objects::nonNull)
                        .toList());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void retainSealBasis(AdmissionIdentity identity, ReviewJob job, String digest, List<StagedPractice> staged) {
        var basis = bases.findByWorkspaceIdAndJobId(job.getWorkspaceId(), job.getId())
                .orElse(null);
        if (basis == null) {
            retainCompositionInputs(identity, job);
            basis = bases.findByWorkspaceIdAndJobId(job.getWorkspaceId(), job.getId())
                    .orElse(null);
        }
        if (basis == null) return;
        if (!files.vouchesForOriginalCopy(
                basis.getCopyId(), job.getId(), job.getWorkspaceId(), basis.getAdmissionAttempt())) {
            bases.deleteForJob(job.getWorkspaceId(), job.getId());
            return;
        }
        StagedEvidence.merge(
                mapper,
                basis,
                staged.stream().filter(value -> value.candidate() != null).toList());
        basis.setAdmissionDigest(digest);
        bases.save(basis);
    }

    private void retainCompositionInputs(AdmissionIdentity identity, ReviewJob job) {
        var review = assignments.review(job);
        if (review.kind() != ReviewKind.ITEM) return;
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ReviewCompositionBasis.RETENTION);
        var occasionExpiry = review.evidenceExpiresAt();
        if (occasionExpiry != null) {
            if (!occasionExpiry.isAfter(now)) return;
            if (occasionExpiry.isBefore(expiresAt)) expiresAt = occasionExpiry;
        }
        var copy = files.completeReceipt(identity).orElse(null);
        if (copy == null || !files.holdVouchingReceipt(identity, copy)) return;
        var original = files.originalCompositionInputs(identity).orElse(null);
        if (original == null) return;
        storeCompositionInputs(job, copy, expiresAt, original);
    }

    private void storeCompositionInputs(ReviewJob job, UUID copy, Instant expiresAt, Map<String, byte[]> original) {
        if (assignments.review(job).kind() != ReviewKind.ITEM) return;
        var basis = bases.findByWorkspaceIdAndJobId(job.getWorkspaceId(), job.getId())
                .orElseGet(ReviewCompositionBasis::new);
        if (copy.equals(basis.getCopyId()) && basis.getAdmissionAttempt() == job.getAttempt()) return;
        basis.setJobId(job.getId());
        basis.setWorkspaceId(job.getWorkspaceId());
        basis.setAdmissionAttempt(job.getAttempt());
        basis.setAdmissionDigest(null);
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
}
