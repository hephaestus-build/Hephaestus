package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Restores the original captured text for composition without asking an item source to capture again. */
@Component
@ConditionalOnCaptureRole
public class ReviewCompositionContinuation {
    public static final String CONTINUATION_PATH = SandboxLayout.INPUTS_PREFIX + "composition-continuation.json";
    private final ReviewCompositionBasisRepository bases;
    private final JobEvidenceFiles files;
    private final ReviewJobRepository jobs;
    private final ResultAdmissionService admission;
    private final ObjectMapper mapper;

    public ReviewCompositionContinuation(
            ReviewCompositionBasisRepository bases,
            JobEvidenceFiles files,
            ReviewJobRepository jobs,
            ResultAdmissionService admission,
            ObjectMapper mapper) {
        this.bases = bases;
        this.files = files;
        this.jobs = jobs;
        this.admission = admission;
        this.mapper = mapper;
    }

    public void holdRecoveryCustody() {
        files.holdCopyAdmission();
    }

    /** Whether the admitted job may still owe private feedback, from the pieces its review already prepared. */
    public boolean privateFeedbackOwed(ReviewJob job) {
        return admission.privateFeedbackOwed(job);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int requeue(ReviewJob job, String workerId, ReviewJobRepository.CompositionRequeue request) {
        var original = bases.findOriginalCopy(job.getWorkspaceId(), job.getId()).orElse(null);
        if (original == null
                || !files.vouchesForOriginalCopy(
                        original.copyId(), job.getId(), job.getWorkspaceId(), original.admissionAttempt())) return 0;
        if (jobs.lockContinuableAttempt(
                        job.getId(), workerId, request.attempt(), ReviewCompositionBasis.VERSION, request.now())
                .isEmpty()) return 0;
        return jobs.requeueLockedForComposition(job.getId(), workerId, request);
    }

    public PreparedJobInputs prepareInputs(ReviewJob job) {
        var basis = bases.findByWorkspaceIdAndJobId(job.getWorkspaceId(), job.getId())
                .orElseThrow(() -> unavailable("The original inputs were removed"));
        if (basis.getBasisVersion() != ReviewCompositionBasis.VERSION
                || !basis.getExpiresAt().isAfter(Instant.now())
                || !basis.getAdmissionDigest().equals(job.getAdmissionDigest())
                || basis.getAdmissionAttempt() != ResultAdmissionService.citationAttempt(job)
                || !files.vouchesForOriginalCopy(
                        basis.getCopyId(), job.getId(), job.getWorkspaceId(), basis.getAdmissionAttempt())) {
            throw unavailable("The original capture no longer permits composition");
        }
        var staged = new TreeMap<String, byte[]>();
        basis.getPayload()
                .path("files")
                .properties()
                .forEach(entry -> staged.put(
                        entry.getKey(),
                        Base64.getDecoder().decode(entry.getValue().asString())));
        if (!ReviewCompositionBasis.digest(staged).equals(basis.getBasisDigest()))
            throw unavailable("The retained inputs do not match their digest");
        byte[] manifestBytes = staged.get(SandboxLayout.MANIFEST_PATH);
        if (manifestBytes == null) throw unavailable("The original manifest is unavailable");
        var manifest = mapper.readValue(manifestBytes, JobFolderIndex.class);
        for (var text : manifest.citableTexts()) {
            byte[] bytes = staged.get(text.stagedPath());
            if (bytes == null || !ProvenanceDigest.sha256Hex(bytes).equals(text.sha256())) {
                throw unavailable("The original citable text is unavailable");
            }
        }
        files.beginPersonCapture(job);
        try {
            staged.put(CONTINUATION_PATH, mapper.writeValueAsBytes(admission.capturedAdmissionResponse(job)));
            // The retained inputs are the admitting attempt's; the task is this attempt's own.
            staged.put(
                    SandboxLayout.TASK_ENVELOPE_FILENAME,
                    mapper.writeValueAsBytes(TaskEnvelope.practiceReview(
                            job.getReviewId(), job.getId(), job.getAttempt(), job.getWorkspaceId())));
            return files.prepare(job, new PreparedEvidence(staged, Map.of(), List.of(), manifest, List.of()), null);
        } catch (RuntimeException failed) {
            files.abortPersonCapture(job);
            throw failed;
        }
    }

    /** What the admitting attempt's public review launch composes from: the admission its review launch received. */
    public byte[] admittedResponse(ReviewJob job) {
        return mapper.writeValueAsBytes(admission.admittedResponse(job));
    }

    @Transactional
    public void forget(ReviewJob job) {
        bases.deleteForJob(job.getWorkspaceId(), job.getId());
    }

    private static JobPreparationException unavailable(String message) {
        return new JobPreparationException(message);
    }
}
