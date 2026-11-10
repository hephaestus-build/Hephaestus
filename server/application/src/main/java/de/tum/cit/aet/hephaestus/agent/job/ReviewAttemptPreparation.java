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
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public ReviewAttemptPreparation(
            ReviewJobRepository jobRepository,
            DefaultReviewExecutionHandler handler,
            PracticePiAdapter practiceAgent,
            AttemptTokenIssuer attemptTokenIssuer,
            SandboxProperties sandboxProperties,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            ReviewJobTelemetry jobTelemetry) {
        this.jobRepository = jobRepository;
        this.handler = handler;
        this.practiceAgent = practiceAgent;
        this.attemptTokenIssuer = attemptTokenIssuer;
        this.reviewLimits = sandboxProperties.defaultResourceLimits();
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.jobTelemetry = jobTelemetry;
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
            preparedInputs = handler.prepareInputs(owned(jobId, fence));
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
            Instant workDeadline =
                    Instant.now().plusSeconds(snapshot.timeoutSeconds()).truncatedTo(ChronoUnit.SECONDS);
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

            PracticeSandboxSpec agentSpec = practiceAgent.buildSandboxSpec(adapterRequest);
            SandboxSpec sandboxSpec = buildSandboxSpec(
                    jobId,
                    preparedInputs.filesOnDisk(),
                    preparedInputs.directories(),
                    agentSpec,
                    snapshot,
                    workDeadline,
                    job.getAttempt());
            stage.set(PreparationFailure.Stage.PROVENANCE);
            persistProvenanceDigests(
                    jobId,
                    fence,
                    job.getWorkspaceId(),
                    agentSpec.promptDigest(),
                    sandboxSpec.inputFiles(),
                    preparedInputs.filesOnDisk(),
                    preparedInputs.directories(),
                    job.getAttempt(),
                    preparedInputs.automatedReviewReadinessReport(),
                    preparedInputs.copiesExpireAt());
            return new PreparedSandbox(sandboxSpec, preparedInputs);
        } catch (RuntimeException exception) {
            preparedInputs.close();
            throw exception;
        }
    }

    void persistPreparedEvidence(
            UUID jobId, AgentRunFence fence, long workspaceId, int attempt, PreparedJobInputs preparedInputs) {
        persistProvenanceDigests(
                jobId,
                fence,
                workspaceId,
                null,
                Map.of(),
                preparedInputs.filesOnDisk(),
                preparedInputs.directories(),
                attempt,
                preparedInputs.automatedReviewReadinessReport(),
                preparedInputs.copiesExpireAt());
    }

    // Persist provenance before provider execution; results without their original input identity cannot be admitted.
    private void persistProvenanceDigests(
            UUID jobId,
            AgentRunFence fence,
            long workspaceId,
            @Nullable String promptDigest,
            Map<String, byte[]> inputFiles,
            Map<String, Path> inputPaths,
            List<EvidenceDirectory> inputDirectories,
            int attempt,
            @Nullable AutomatedReviewReadinessReport automatedReviewReadinessReport,
            @Nullable Instant copiesExpireAt) {
        String inputsDigest = ProvenanceDigest.inputsDigestHex(inputFiles, inputPaths, inputDirectories, jobId);
        JsonNode capture = capture(snapshotMetadata(inputFiles, inputPaths), automatedReviewReadinessReport);
        var stamp = capture instanceof ObjectNode object ? object : objectMapper.createObjectNode();
        if (promptDigest != null) stamp.put("promptDigest", promptDigest);
        stamp.put("inputsDigest", inputsDigest);
        // The attempt's transcript binds to this, so no copy it keeps outlives the occasion record its inputs copy.
        if (copiesExpireAt != null) stamp.put(JobEvidenceFiles.COPIES_EXPIRE_AT, copiesExpireAt.toString());
        stamp.set(
                "directories",
                objectMapper.valueToTree(
                        inputDirectories.stream().map(EvidenceDirectory::target).toList()));
        // Admission compares each search of the sandbox's checkout with these, read from this copy before it starts.
        stamp.set(
                JobEvidenceFiles.CHECKOUTS,
                objectMapper.valueToTree(JobEvidenceFiles.checkoutDigests(inputDirectories)));
        Integer updated = transactionTemplate.execute(
                status -> jobRepository.updateCapture(jobId, workspaceId, fence.runId(), attempt, stamp));
        if (updated == null || updated != 1) {
            throw new IllegalStateException("Provenance digest write matched no job row: jobId=" + jobId);
        }
        log.debug("Provenance digests: jobId={}, prompt={}, inputs={}", jobId, promptDigest, inputsDigest);
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
