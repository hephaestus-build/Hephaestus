package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import java.io.Serial;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Restores the original composition inputs of an admitted review without asking an item source to capture again. */
@Component
@ConditionalOnCaptureRole
public class ReviewCompositionContinuation {
    private final ReviewCompositionBasisRepository bases;
    private final JobEvidenceFiles files;
    private final ReviewJobRepository jobs;
    private final ObjectMapper mapper;
    private final ReviewAssignmentStore membership;

    public ReviewCompositionContinuation(
            ReviewCompositionBasisRepository bases,
            JobEvidenceFiles files,
            ReviewJobRepository jobs,
            ObjectMapper mapper,
            ReviewAssignmentStore membership) {
        this.bases = bases;
        this.files = files;
        this.jobs = jobs;
        this.mapper = mapper;
        this.membership = membership;
    }

    public boolean hasStaged(ReviewJob job) {
        return membership.hasStaged(job.getWorkspaceId(), job.getReviewId(), job.getId());
    }

    public void holdRecoveryCustody() {
        files.holdCopyAdmission();
    }

    /**
     * Hands a lost admitted attempt on to a new attempt that composes what it left open, whatever its retained inputs
     * permit: the new attempt checks them before it stages anything, and withholds what they no longer permit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int requeue(ReviewJob job, String workerId, ReviewJobRepository.CompositionRequeue request) {
        return jobs.requeueAdmittedForComposition(job.getId(), workerId, request);
    }

    /**
     * The retained composition inputs, staged as this attempt's own folder: the folder's person-copy receipt lets the
     * attempt read earlier feedback again, and the bytes are the ones the admitted citations were verified under. The
     * caller closes the staged folder. Inputs whose receipt no longer vouches for them, as after an erasure, are never
     * staged.
     *
     * @throws InputsUnavailableException when the inputs were erased, removed, expired or no longer match
     */
    public Restored restore(ReviewJob job) {
        var staged = retainedInputs(job);
        byte[] manifestBytes = Objects.requireNonNull(staged.get(SandboxLayout.MANIFEST_PATH));
        var manifest = mapper.readValue(manifestBytes, JobFolderIndex.class);
        files.beginPersonCapture(job);
        try {
            var basis = bases.findByWorkspaceIdAndJobId(job.getWorkspaceId(), job.getId())
                    .orElseThrow();
            files.inheritPersonCapture(job, basis.getCopyId(), basis.getAdmissionAttempt());
            for (var copy : basis.getPayload().path("copies"))
                files.inheritPersonCapture(
                        job,
                        UUID.fromString(copy.path("copyId").asString()),
                        copy.path("attempt").asInt());
            var prepared = files.prepare(
                    job, new PreparedEvidence(new TreeMap<>(staged), Map.of(), List.of(), manifest, List.of()), null);
            return new Restored(prepared, Collections.unmodifiableMap(staged));
        } catch (RuntimeException failed) {
            files.abortPersonCapture(job);
            throw failed;
        }
    }

    public Map<String, byte[]> retainedInputs(ReviewJob job) {
        var basis = bases.findByWorkspaceIdAndJobId(job.getWorkspaceId(), job.getId())
                .orElseThrow(() -> gone("The original inputs were removed"));
        if (!files.vouchesForOriginalCopy(
                basis.getCopyId(), job.getId(), job.getWorkspaceId(), basis.getAdmissionAttempt()))
            throw new InputsUnavailableException("The original capture was erased", true);
        if (!ResultAdmissionService.isAdmitted(job)
                || basis.getAdmissionDigest() == null
                || basis.getBasisVersion() != ReviewCompositionBasis.VERSION
                || !basis.getExpiresAt().isAfter(Instant.now())
                || !basis.getAdmissionDigest().equals(job.getAdmissionDigest())
                || basis.getAdmissionAttempt() > ResultAdmissionService.citationAttempt(job)) {
            throw gone("The original capture no longer permits composition");
        }
        for (var copy : basis.getPayload().path("copies"))
            if (!files.vouchesForOriginalCopy(
                    UUID.fromString(copy.path("copyId").asString()),
                    job.getId(),
                    job.getWorkspaceId(),
                    copy.path("attempt").asInt()))
                throw new InputsUnavailableException("A staged capture was erased", true);
        var staged = new TreeMap<String, byte[]>();
        basis.getPayload()
                .path("files")
                .properties()
                .forEach(entry -> staged.put(
                        entry.getKey(),
                        Base64.getDecoder().decode(entry.getValue().asString())));
        if (!ReviewCompositionBasis.digest(staged).equals(basis.getBasisDigest()))
            throw gone("The retained inputs do not match their digest");
        byte[] manifestBytes = staged.get(SandboxLayout.MANIFEST_PATH);
        if (manifestBytes == null) throw gone("The original manifest is unavailable");
        var manifest = mapper.readValue(manifestBytes, JobFolderIndex.class);
        for (var text : manifest.citableTexts()) {
            byte[] bytes = staged.get(text.stagedPath());
            if (bytes == null || !ProvenanceDigest.sha256Hex(bytes).equals(text.sha256())) {
                throw gone("The original citable text is unavailable");
            }
        }
        return Collections.unmodifiableMap(staged);
    }

    /**
     * An empty folder for an attempt whose inputs are gone: its person-copy receipt still lets the attempt read and
     * prepare what it records instead, a withholding per destination.
     */
    public Restored stageWithoutInputs(ReviewJob job) {
        files.beginPersonCapture(job);
        try {
            return new Restored(files.prepare(job, new PreparedEvidence(Map.of(), null), null), Map.of());
        } catch (RuntimeException failed) {
            files.abortPersonCapture(job);
            throw failed;
        }
    }

    /** The retained inputs of a review, staged for the attempt that composes from them. */
    public record Restored(PreparedJobInputs staged, Map<String, byte[]> files) implements AutoCloseable {
        @Override
        public void close() {
            staged.close();
        }
    }

    @Transactional
    public void forget(ReviewJob job) {
        bases.deleteForJob(job.getWorkspaceId(), job.getId());
    }

    /** The retained inputs cannot be composed from. */
    public static final class InputsUnavailableException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final boolean erased;

        public InputsUnavailableException(String message, boolean erased) {
            super(message);
            this.erased = erased;
        }

        /** Whether the inputs were erased, as opposed to removed, expired or changed. */
        public boolean erased() {
            return erased;
        }
    }

    private static InputsUnavailableException gone(String message) {
        return new InputsUnavailableException(message, false);
    }
}
