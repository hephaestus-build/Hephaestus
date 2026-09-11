package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.io.Serial;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ObservationAdmissionService {

    public static final String DIGEST_METADATA_KEY = "observation_admission_digest";

    /** Where a refusal's reason is kept, so the run says what happened to it after the sandbox is gone. */
    public static final String REFUSAL_METADATA_KEY = "observation_admission_refusal";

    static boolean observationsWereRefused(AgentJob job) {
        return job.getMetadata() != null
                && !job.getMetadata()
                        .path(REFUSAL_METADATA_KEY)
                        .path("reasonCode")
                        .asString()
                        .isBlank();
    }

    static void requireMatchingCompositionDigest(AgentJob job) {
        String admitted = job.getMetadata() == null
                ? ""
                : job.getMetadata().path(DIGEST_METADATA_KEY).asString();
        String composed = job.getOutput() == null
                ? ""
                : job.getOutput().path("feedback").path("admissionDigest").asString();
        if (admitted.isBlank() || !admitted.equals(composed)) {
            throw new JobDeliveryException("Feedback was not composed from this job's admitted observations");
        }
    }

    private final AgentJobRepository jobs;
    private final ObservationRepository observations;
    private final PullRequestReviewHandler pullRequests;
    private final IssueReviewHandler issues;
    private final JsonMapper mapper;

    public ObservationAdmissionService(
            AgentJobRepository jobs,
            ObservationRepository observations,
            PullRequestReviewHandler pullRequests,
            IssueReviewHandler issues,
            JsonMapper mapper) {
        this.jobs = jobs;
        this.observations = observations;
        this.pullRequests = pullRequests;
        this.issues = issues;
        this.mapper = mapper;
    }

    @Transactional
    public ObjectNode admit(UUID jobId, JsonNode submitted) {
        AgentJob job = jobs.findByIdWithWorkspaceForUpdate(jobId).orElseThrow();
        if (job.getStatus() != AgentJobStatus.RUNNING) {
            throw new IllegalStateException("Observation admission requires a RUNNING job");
        }
        String digest = ProvenanceDigest.sha256Hex(serializedPayload(submitted));
        JsonNode currentMetadata = job.getMetadata();
        String existing = currentMetadata == null
                ? ""
                : currentMetadata.path(DIGEST_METADATA_KEY).asString();
        if (!existing.isBlank()) {
            if (!existing.equals(digest)) throw new AdmissionConflictException();
            return response(
                    job,
                    existing,
                    observations.findByAgentJobId(jobId, job.getWorkspace().getId()));
        }
        switch (job.getJobType()) {
            case PULL_REQUEST_REVIEW -> pullRequests.admitObservations(job, submitted);
            case ISSUE_REVIEW -> issues.admitObservations(job, submitted);
            default -> throw new IllegalArgumentException("Job type does not admit review observations");
        }
        ObjectNode metadata =
                currentMetadata instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
        metadata.remove(REFUSAL_METADATA_KEY);
        metadata.put(DIGEST_METADATA_KEY, digest);
        job.setMetadata(metadata);
        jobs.save(job);
        return response(
                job,
                digest,
                observations.findByAgentJobId(jobId, job.getWorkspace().getId()));
    }

    /**
     * Records why this review's observations were refused, in its own transaction: the admission that
     * refused them rolls back, and the reason must outlive that rollback — it is all the practice page
     * and the administration surface have to explain a run that recorded nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefusal(UUID jobId, String reasonCode, String reason) {
        jobs.findById(jobId).ifPresent(job -> {
            ObjectNode metadata =
                    job.getMetadata() instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
            ObjectNode refusal = mapper.createObjectNode();
            refusal.put("reasonCode", reasonCode);
            refusal.put("reason", reason);
            metadata.set(REFUSAL_METADATA_KEY, refusal);
            job.setMetadata(metadata);
            jobs.save(job);
        });
    }

    private byte[] serializedPayload(JsonNode submitted) {
        try {
            return mapper.writeValueAsBytes(submitted);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid observation payload", e);
        }
    }

    private ObjectNode response(AgentJob job, String digest, List<Observation> admitted) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("admissionDigest", digest);
        ArrayNode rows = root.putArray("observations");
        java.util.Map<String, java.util.TreeSet<Integer>> validLines =
                job.getJobType() == AgentJobType.PULL_REQUEST_REVIEW
                        ? pullRequests.validDiffLines(job)
                        : java.util.Map.of();
        admitted.forEach(o -> rows.add(project(o, validLines)));
        return root;
    }

    private ObjectNode project(Observation observation, java.util.Map<String, java.util.TreeSet<Integer>> validLines) {
        ObjectNode out = mapper.createObjectNode();
        out.put("id", observation.getId().toString());
        out.put("practiceSlug", observation.getPractice().getSlug());
        out.put("summary", observation.getSummary());
        out.put("presence", observation.getPresence().name());
        if (observation.getAssessment() != null)
            out.put("assessment", observation.getAssessment().name());
        if (observation.getSeverity() != null)
            out.put("severity", observation.getSeverity().name());
        out.put("evidenceRationale", observation.getEvidenceRationale());
        out.set("evidence", observation.getEvidence());
        ArrayNode citations = out.putArray("citations");
        JsonNode source = observation.getEvidence() == null
                ? null
                : observation.getEvidence().path("citations");
        if (source != null && source.isArray()) {
            int index = 0;
            for (JsonNode citation : source) {
                ObjectNode copy = citations.addObject();
                copy.put("index", index++);
                citation.properties().forEach(entry -> copy.set(entry.getKey(), entry.getValue()));
                boolean anchorable = "scm.pull-request.diff"
                                .equals(citation.path("sourceKind").asString())
                        && citation.path("path").isString()
                        && citation.path("startLine").isIntegralNumber()
                        && validLines
                                .getOrDefault(citation.path("path").asString(), new java.util.TreeSet<>())
                                .contains(citation.path("startLine").asInt());
                copy.put("anchorable", anchorable);
            }
        }
        out.put(
                "anchorable",
                citations.valueStream().anyMatch(c -> c.path("anchorable").asBoolean()));
        return out;
    }

    public static class AdmissionConflictException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;
    }
}
