package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedObservations;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ObservationAdmissionService {

    public static final String DIGEST_METADATA_KEY = "observation_admission_digest";

    /** Where a refusal's reason is kept, so the run says what happened to it after the sandbox is gone. */
    public static final String REFUSAL_METADATA_KEY = "observation_admission_refusal";

    /** The refusal reason for a submission that is inadmissible for a reason no retry changes. */
    public static final String INADMISSIBLE_REASON_CODE = "inadmissible_observations";

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
    private final JobTypeHandlerRegistry handlers;
    private final JsonMapper mapper;
    private final TransactionTemplate transactions;

    /**
     * One admission in flight per attempt. The runner repeats a request whose answer did not arrive,
     * and a repeat that started its own preparation would verify the same submission twice — with a
     * container each — so it joins the one already running and gets the same answer.
     */
    private final ConcurrentHashMap<AdmissionIdentity, Flight> flights = new ConcurrentHashMap<>();

    private record Flight(String digest, CompletableFuture<ObjectNode> outcome) {}

    public ObservationAdmissionService(
            AgentJobRepository jobs,
            ObservationRepository observations,
            JobTypeHandlerRegistry handlers,
            JsonMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.observations = observations;
        this.handlers = handlers;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Admits {@code submitted} as the observations of this attempt, or refuses it and records why on the
     * job before rethrowing the refusal.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ObjectNode admit(AdmissionIdentity identity, JsonNode submitted) {
        String digest = ProvenanceDigest.sha256Hex(serializedPayload(submitted));
        Flight mine = new Flight(digest, new CompletableFuture<>());
        Flight running = flights.putIfAbsent(identity, mine);
        if (running != null) {
            if (!running.digest().equals(digest)) throw new AdmissionConflictException();
            return join(running);
        }
        try {
            ObjectNode admitted = admitOnce(identity, submitted, digest);
            mine.outcome().complete(admitted);
            return admitted;
        } catch (RuntimeException exception) {
            mine.outcome().completeExceptionally(exception);
            throw exception;
        } finally {
            flights.remove(identity, mine);
        }
    }

    /** A joined retry answers with the first attempt's own exception, not the join's wrapper. */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static ObjectNode join(Flight running) {
        try {
            return running.outcome().join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            throw exception;
        }
    }

    private ObjectNode admitOnce(AdmissionIdentity identity, JsonNode submitted, String digest) {
        AgentJob candidate = Objects.requireNonNull(transactions.execute(status -> ownedJob(identity)));
        String existing = admissionDigest(candidate);
        if (!existing.isBlank()) {
            return Objects.requireNonNull(transactions.execute(status -> {
                AgentJob job = ownedJob(identity);
                if (!digest.equals(admissionDigest(job))) throw new AdmissionConflictException();
                return response(job, digest, observations.findByAgentJobId(identity.jobId(), identity.workspaceId()));
            }));
        }
        PreparedObservations prepared;
        try {
            prepared = handlers.getHandler(candidate.getJobType()).prepareObservations(candidate, submitted);
        } catch (ObservationsRefusedException refusal) {
            recordRefusal(identity, refusal.reasonCode(), refusal.reason(), refusal.verificationFailures());
            throw refusal;
        } catch (JobDeliveryException inadmissible) {
            recordRefusal(
                    identity,
                    INADMISSIBLE_REASON_CODE,
                    String.valueOf(inadmissible.getMessage()),
                    JsonNodeFactory.instance.arrayNode());
            throw inadmissible;
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            AgentJob job = ownedJob(identity);
            String admitted = admissionDigest(job);
            if (!admitted.isBlank()) {
                if (!digest.equals(admitted)) throw new AdmissionConflictException();
            } else {
                prepared.record(job);
                ObjectNode metadata =
                        job.getMetadata() instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
                metadata.remove(REFUSAL_METADATA_KEY);
                metadata.put(DIGEST_METADATA_KEY, digest);
                job.setMetadata(metadata);
                jobs.save(job);
            }
            return response(job, digest, observations.findByAgentJobId(identity.jobId(), identity.workspaceId()));
        }));
    }

    /** Whether this job's observations were admitted; the digest is the one durable trace of it. */
    public static boolean isAdmitted(AgentJob job) {
        return !admissionDigest(job).isBlank();
    }

    private static String admissionDigest(AgentJob job) {
        return job.getMetadata() == null
                ? ""
                : job.getMetadata().path(DIGEST_METADATA_KEY).asString();
    }

    /** The refused admission left nothing behind; its reason is recorded on its own under the same ownership fence. */
    public void recordRefusal(
            AdmissionIdentity identity, String reasonCode, String reason, JsonNode verificationFailures) {
        transactions.executeWithoutResult(status -> {
            AgentJob job = ownedJob(identity);
            ObjectNode metadata =
                    job.getMetadata() instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
            if (!metadata.path(DIGEST_METADATA_KEY).asString().isBlank()) {
                throw new AdmissionConflictException();
            }
            ObjectNode refusal = mapper.createObjectNode();
            refusal.put("reasonCode", reasonCode);
            refusal.put("reason", reason);
            metadata.set(REFUSAL_METADATA_KEY, refusal);
            metadata.set("citation_verification_failures", verificationFailures.deepCopy());
            job.setMetadata(metadata);
            jobs.save(job);
        });
    }

    private AgentJob ownedJob(AdmissionIdentity identity) {
        AgentJob job = jobs.findByIdWithWorkspaceForUpdate(identity.jobId()).orElseThrow(StaleAttemptException::new);
        if (job.getStatus() != AgentJobStatus.RUNNING
                || !job.getWorkspace().getId().equals(identity.workspaceId())
                || job.getRetryCount() != identity.attempt()
                || !identity.workerId().equals(job.getWorkerId())) {
            throw new StaleAttemptException();
        }
        return job;
    }

    public record AdmissionIdentity(UUID jobId, Long workspaceId, int attempt, String workerId) {}

    public static class StaleAttemptException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;
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
        admitted.forEach(o -> rows.add(project(o)));
        return root;
    }

    private ObjectNode project(Observation observation) {
        ObjectNode out = mapper.createObjectNode();
        out.put("id", observation.getId().toString());
        out.put("practiceSlug", observation.getPractice().getSlug());
        out.put("summary", observation.getSummary());
        out.put("assessmentStatus", observation.getAssessmentStatus().name());
        out.put(
                "outcome",
                observation.getOutcome() == null
                        ? null
                        : observation.getOutcome().name());
        out.put(
                "presence",
                observation.getPresence() == null
                        ? null
                        : observation.getPresence().name());
        out.put(
                "assessment",
                observation.getAssessment() == null
                        ? null
                        : observation.getAssessment().name());
        out.put(
                "severity",
                observation.getSeverity() == null
                        ? null
                        : observation.getSeverity().name());
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
                boolean anchorable = PracticeSubjectClause.DIFF_SOURCE
                                .value()
                                .equals(citation.path("sourceKind").asString())
                        && citation.path("path").isString()
                        && citation.path("startLine").isIntegralNumber()
                        && "NEW".equals(citation.path("side").asString())
                        && "VERIFIED"
                                .equals(citation.path("verification")
                                        .path("status")
                                        .asString())
                        && "EXACT_LOCATION"
                                .equals(citation.path("verification")
                                        .path("scope")
                                        .asString());
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
