package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.metrics.ReviewStageTelemetry;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeAgentRequest;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prepares a review attempt's sandbox: it captures the attempt's inputs, builds the spec of the runner that measures
 * them, and records their provenance before any provider call. Each write is fenced on the attempt's run, so a claim
 * that lost its run prepares nothing more. Every caller prepares an attempt the same way.
 */
@Component
@ConditionalOnAgentRuntime
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
public class ReviewAttemptPreparation {

    private static final Logger log = LoggerFactory.getLogger(ReviewAttemptPreparation.class);

    private final ReviewJobRepository jobRepository;
    private final DefaultReviewExecutionHandler handler;
    private final PracticePiAdapter practiceAgent;
    private final AttemptTokenIssuer attemptTokenIssuer;
    private final SandboxProperties.DefaultResourceLimits reviewLimits;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final ReviewJobTelemetry jobTelemetry;
    private final JobEvidenceFiles evidenceFiles;

    public ReviewAttemptPreparation(
            ReviewJobRepository jobRepository,
            DefaultReviewExecutionHandler handler,
            PracticePiAdapter practiceAgent,
            AttemptTokenIssuer attemptTokenIssuer,
            SandboxProperties sandboxProperties,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            ReviewJobTelemetry jobTelemetry,
            JobEvidenceFiles evidenceFiles) {
        this.jobRepository = jobRepository;
        this.handler = handler;
        this.practiceAgent = practiceAgent;
        this.attemptTokenIssuer = attemptTokenIssuer;
        this.reviewLimits = sandboxProperties.defaultResourceLimits();
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.jobTelemetry = jobTelemetry;
        this.evidenceFiles = evidenceFiles;
    }

    /** The attempt's job, while {@code fence} still holds it. */
    ReviewJob owned(UUID jobId, AgentRunFence fence) {
        ReviewJob row = jobRepository.findById(jobId).orElse(null);
        if (row == null
                || row.getAttempt() != ReviewRunKind.attempt(fence.epoch())
                || !fence.runId().equals(row.getRunId())) throw new ResultAdmissionService.StaleAttemptException();
        return row;
    }

    record PreparedSandbox(@Nullable SandboxSpec spec, PreparedJobInputs stagedInputs) {}

    PreparedSandbox prepare(
            UUID jobId,
            ReviewJob job,
            AgentRunFence fence,
            ConfigSnapshot snapshot,
            AtomicReference<PreparationFailure.Stage> stage) {
        stage.set(PreparationFailure.Stage.INPUTS);

        // Keeping a result commits at capture. If that attempt then failed, this one has nothing left to capture.
        if (handler.keptEveryResult(owned(jobId, fence))) {
            handler.completeWithoutExecution(owned(jobId, fence));
            return new PreparedSandbox(null, new PreparedJobInputs(Map.of(), List.of(), List.of(), null, null));
        }
        var capture = jobTelemetry.stage(ReviewStageTelemetry.Stage.CAPTURE);
        PreparedJobInputs preparedInputs;
        try {
            ReviewJob current = owned(jobId, fence);
            preparedInputs = switch (evidenceFiles.preparation(current)) {
                case NEW -> handler.prepareInputs(current);
                case PUBLISHED -> evidenceFiles.resume(current);
                case INTERRUPTED -> throw new FreshEpochRequired();
                case UNAVAILABLE ->
                    throw new CheckoutUnverifiedException(
                            CheckoutUnverifiedException.Reason.UNAVAILABLE,
                            "The published review inputs are no longer held");
            };
        } catch (InsufficientEvidenceException refused) {
            // The capture ran and found too little evidence; the attempt ends with that, not with a fault.
            capture.end(ReviewStageTelemetry.Outcome.OK);
            throw refused;
        } catch (RuntimeException failed) {
            capture.end(ReviewStageTelemetry.Outcome.FAILED);
            throw failed;
        }
        capture.end(ReviewStageTelemetry.Outcome.OK);

        try {
            var report = preparedInputs.automatedReviewReadinessReport();
            if (report != null && report.decisions().stream().noneMatch(report::runs)) {
                stage.set(PreparationFailure.Stage.PROVENANCE);
                persistPreparedEvidence(jobId, fence, job.getWorkspaceId(), job.getAttempt(), preparedInputs);
                handler.completeWithoutExecution(owned(jobId, fence));
                return new PreparedSandbox(null, preparedInputs);
            }
            stage.set(PreparationFailure.Stage.CREDENTIAL);
            // Sandboxes access providers through the LLM proxy with an attempt-scoped credential.
            Instant started = owned(jobId, fence).getExecutionStartedAt();
            if (started == null) started = jobRepository.databaseClock();
            Instant workDeadline = workDeadline(started, snapshot);
            Instant tokenExpiry = workDeadline.plus(SandboxLayout.RESULT_UPLOAD_GRACE);
            String jobToken = attemptTokenIssuer.issueForReview(
                    jobId, job.getWorkspaceId(), fence.epoch(), fence.runId(), workDeadline, tokenExpiry);
            stage.set(PreparationFailure.Stage.SPEC);
            PracticeAgentRequest adapterRequest = new PracticeAgentRequest(
                    snapshot.model().apiProtocol().wire(),
                    snapshot.model().upstreamModelId(),
                    snapshot.contextWindow(),
                    snapshot.maxOutputTokens(),
                    snapshot.model().reasoningEffort(),
                    jobToken,
                    snapshot.timeoutSeconds(),
                    snapshot.model().forcedToolChoice());

            JsonNode previous = job.getCapture();
            Program published = null;
            if (previous != null
                    && previous.path("attempt").asInt(-1) == job.getAttempt()
                    && fence.runId().toString().equals(previous.path("runId").asString(null))) {
                published = objectMapper.treeToValue(previous.path("program"), Program.class);
                if (published == null
                        || !programDigest(published)
                                .equals(previous.path("programDigest").asString(null)))
                    throw new CheckoutUnverifiedException(
                            CheckoutUnverifiedException.Reason.MISMATCH,
                            "The published execution plan differs from its digest");
            }
            PracticeSandboxSpec agentSpec = published == null
                    ? practiceAgent.buildSandboxSpec(adapterRequest)
                    : new PracticeSandboxSpec(
                            Objects.requireNonNull(published.image()),
                            published.command(),
                            published.environment(),
                            published.files(),
                            jobToken,
                            published.promptDigest());
            SandboxSpec sandboxSpec = buildSandboxSpec(
                    jobId,
                    preparedInputs.filesOnDisk(),
                    preparedInputs.directories(),
                    agentSpec,
                    snapshot,
                    workDeadline,
                    job.getAttempt());
            stage.set(PreparationFailure.Stage.PROVENANCE);
            var program = new Program(
                    sandboxSpec.inputFiles(),
                    agentSpec.promptDigest(),
                    agentSpec.image(),
                    agentSpec.command(),
                    agentSpec.environment());
            if (published != null) {
                endedBefore(job);
            } else {
                persistProvenanceDigests(jobId, fence, job.getAttempt(), preparedInputs, program, started);
            }
            return new PreparedSandbox(sandboxSpec, preparedInputs);
        } catch (RuntimeException exception) {
            preparedInputs.close();
            throw exception;
        }
    }

    static Instant workDeadline(ReviewJob job, ConfigSnapshot snapshot) {
        return workDeadline(Objects.requireNonNull(job.getExecutionStartedAt()), snapshot);
    }

    private static Instant workDeadline(Instant started, ConfigSnapshot snapshot) {
        return started.plusSeconds(snapshot.timeoutSeconds()).truncatedTo(ChronoUnit.SECONDS);
    }

    /** A database read, not the persistence context: the publication reply can be lost after commit. */
    Optional<Boolean> published(UUID jobId, AgentRunFence fence) {
        return jobRepository.hasPublishedPreparation(
                jobId, fence.workspaceId(), fence.runId(), ReviewRunKind.attempt(fence.epoch()));
    }

    /** An incomplete publication can only be replaced under the next fenced epoch. */
    static final class FreshEpochRequired extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** The baseline belongs to the execution's frozen checkpoint, even after that execution made progress. */
    static int endedBefore(ReviewJob job) {
        JsonNode capture = job.getCapture();
        if (capture != null
                && capture.path("attempt").asInt(-1) == job.getAttempt()
                && job.getRunId().toString().equals(capture.path("runId").asString(null))
                && capture.path("endedBefore").asInt(-1) >= 0)
            return capture.path("endedBefore").asInt();
        throw new FreshEpochRequired();
    }

    void persistPreparedEvidence(
            UUID jobId, AgentRunFence fence, long workspaceId, int attempt, PreparedJobInputs preparedInputs) {
        if (workspaceId != fence.workspaceId())
            throw new IllegalArgumentException("The capture belongs to another workspace");
        ReviewJob current = owned(jobId, fence);
        JsonNode existing = current.getCapture();
        if (existing != null
                && existing.path("attempt").asInt(-1) == attempt
                && fence.runId().toString().equals(existing.path("runId").asString(null))) return;
        persistProvenanceDigests(
                jobId, fence, attempt, preparedInputs, new Program(Map.of(), null, null, List.of(), Map.of()), null);
    }

    private record Program(
            Map<String, byte[]> files,
            @Nullable String promptDigest,
            @Nullable String image,
            List<String> command,
            Map<String, String> environment) {}

    /** The adapter's stable execution plan. Credentials and request trace context are supplied separately. */
    private String programDigest(Program program) {
        var plan = objectMapper.createObjectNode();
        plan.put("image", program.image());
        plan.put("promptDigest", program.promptDigest());
        plan.put("filesDigest", ProvenanceDigest.rootDigestHex(program.files()));
        plan.set("command", objectMapper.valueToTree(program.command()));
        plan.set("environment", objectMapper.valueToTree(new TreeMap<>(program.environment())));
        return ProvenanceDigest.sha256Hex(objectMapper.writeValueAsBytes(plan));
    }

    // Persist provenance before provider execution; results without their original input identity cannot be admitted.
    private void persistProvenanceDigests(
            UUID jobId,
            AgentRunFence fence,
            int attempt,
            PreparedJobInputs inputs,
            Program program,
            @Nullable Instant started) {
        var inputFiles = program.files();
        var inputPaths = inputs.filesOnDisk();
        var inputDirectories = inputs.directories();
        String inputsDigest = ProvenanceDigest.inputsDigestHex(inputFiles, inputPaths, inputDirectories, jobId);
        JsonNode capture = capture(snapshotMetadata(inputFiles, inputPaths), inputs.automatedReviewReadinessReport());
        var stamp = capture instanceof ObjectNode object ? object : objectMapper.createObjectNode();
        int baseline = handler.endedPractices(owned(jobId, fence));
        stamp.put("attempt", attempt);
        stamp.put("runId", fence.runId().toString());
        stamp.put("endedBefore", baseline);
        stamp.put("programDigest", programDigest(program));
        stamp.set("program", objectMapper.valueToTree(program));
        if (program.image() != null) stamp.put("image", program.image());
        if (program.promptDigest() != null) stamp.put("promptDigest", program.promptDigest());
        stamp.put("inputsDigest", inputsDigest);
        Instant expiry = inputs.copiesExpireAt();
        if (expiry != null) stamp.put(JobEvidenceFiles.COPIES_EXPIRE_AT, expiry.toString());
        stamp.set(
                "directories",
                objectMapper.valueToTree(
                        inputDirectories.stream().map(EvidenceDirectory::target).toList()));
        stamp.set(
                JobEvidenceFiles.CHECKOUTS,
                objectMapper.valueToTree(JobEvidenceFiles.checkoutDigests(inputDirectories)));
        var checkoutKeys = new TreeMap<String, CheckoutKey>();
        for (EvidenceDirectory directory : inputDirectories) {
            CheckoutKey key = directory.checkout();
            if (key != null) checkoutKeys.put(directory.target(), key);
        }
        stamp.set(JobEvidenceFiles.CHECKOUT_KEYS, objectMapper.valueToTree(checkoutKeys));
        transactionTemplate.executeWithoutResult(status -> {
            if (jobRepository.updateCapture(jobId, fence.workspaceId(), fence.runId(), attempt, stamp) != 1)
                throw new ResultAdmissionService.StaleAttemptException();
            if (started != null && jobRepository.markExecutionStarted(jobId, fence.runId(), attempt, started) != 1)
                throw new ResultAdmissionService.StaleAttemptException();
        });
        log.debug("Provenance digests: jobId={}, prompt={}, inputs={}", jobId, program.promptDigest(), inputsDigest);
    }

    private static Map<String, byte[]> snapshotMetadata(Map<String, byte[]> scaffolding, Map<String, Path> paths) {
        var metadata = new HashMap<>(scaffolding);
        for (String path : List.of(
                SandboxLayout.MANIFEST_PATH,
                SandboxLayout.PRACTICES_PREFIX + "index.json",
                SandboxLayout.CONTEXT_PREFIX + "metadata.json",
                SandboxLayout.CONTEXT_PREFIX + "issue_metadata.json")) {
            Path source = paths.get(path);
            if (source != null) {
                try {
                    metadata.put(path, Files.readAllBytes(source));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }
        return metadata;
    }

    private @Nullable JsonNode capture(
            Map<String, byte[]> inputFiles, @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport) {
        byte[] manifest = inputFiles.get(SandboxLayout.MANIFEST_PATH);
        byte[] practices = inputFiles.get(SandboxLayout.PRACTICES_PREFIX + "index.json");
        // Java null, not NullNode: NullNode serializes to the JSON value null, which is a non-SQL-NULL
        // jsonb and so passes every IS NOT NULL predicate a reader writes against this column.
        if (manifest == null && practices == null && automatedReviewReadinessReport == null) return null;
        if (manifest == null) {
            throw new IllegalStateException("Practice review inputs have an incomplete evidence snapshot");
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.set("manifest", objectMapper.readTree(manifest));
        if (practices != null) {
            snapshot.set("practices", objectMapper.readTree(practices));
        }
        if (automatedReviewReadinessReport != null)
            snapshot.set("readiness", objectMapper.valueToTree(automatedReviewReadinessReport));
        return snapshot;
    }

    private SandboxSpec buildSandboxSpec(
            UUID jobId,
            Map<String, Path> handlerFilesOnDisk,
            List<EvidenceDirectory> handlerDirectories,
            PracticeSandboxSpec agentSpec,
            ConfigSnapshot snapshot,
            Instant workDeadline,
            int attempt) {
        ResourceLimits limits = reviewLimits.forAttempt(Duration.ofSeconds(snapshot.timeoutSeconds()));

        return new SandboxSpec(
                jobId,
                attempt,
                agentSpec.image(),
                agentSpec.command(),
                agentSpec.environment(),
                agentSpec.gatewayToken(),
                limits,
                workDeadline,
                agentSpec.inputFiles(),
                handlerFilesOnDisk,
                handlerDirectories);
    }
}
