package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.handler.IssueReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.PullRequestReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmission;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.AgentJobListRow;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision.Detect;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaults;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AgentJobService {

    private static final Logger log = LoggerFactory.getLogger(AgentJobService.class);

    private static final Set<AgentJobStatus> ACTIVE_STATUSES = Set.of(AgentJobStatus.QUEUED, AgentJobStatus.RUNNING);

    private final AgentJobRepository agentJobRepository;
    private final ReviewMemberAiPolicy memberAiPolicy;
    private final WorkspaceRepository workspaceRepository;
    private final ConnectionService connectionService;
    private final JobTypeHandlerRegistry handlerRegistry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final PracticeReviewProperties reviewProperties;
    private final PracticeRepository practiceRepository;
    private final LlmBudgetService llmBudgetService;
    private final LlmModelResolver llmModelResolver;
    private final SignalRecorder signalRecorder;

    public AgentJobService(
            AgentJobRepository agentJobRepository,
            ReviewMemberAiPolicy memberAiPolicy,
            WorkspaceRepository workspaceRepository,
            ConnectionService connectionService,
            JobTypeHandlerRegistry handlerRegistry,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            PracticeReviewProperties reviewProperties,
            PracticeRepository practiceRepository,
            LlmBudgetService llmBudgetService,
            LlmModelResolver llmModelResolver,
            SignalRecorder signalRecorder) {
        this.agentJobRepository = agentJobRepository;
        this.memberAiPolicy = memberAiPolicy;
        this.workspaceRepository = workspaceRepository;
        this.connectionService = connectionService;
        this.handlerRegistry = handlerRegistry;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.reviewProperties = reviewProperties;
        this.practiceRepository = practiceRepository;
        this.llmBudgetService = llmBudgetService;
        this.llmModelResolver = llmModelResolver;
        this.signalRecorder = signalRecorder;
    }

    @Transactional(readOnly = true)
    public Page<AgentJobListRow> getJobs(Long workspaceId, @Nullable AgentJobStatus status, Pageable pageable) {
        return agentJobRepository.findListRows(workspaceId, status, pageable);
    }

    @Transactional(readOnly = true)
    public AgentJob getJob(Long workspaceId, UUID jobId) {
        return agentJobRepository
                .findByIdAndWorkspaceId(jobId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("AgentJob", jobId.toString()));
    }

    // Submit

    /**
     * Build a detached PR review submission request. Reads the PR's lazy associations, so it MUST be
     * called inside the caller's open session/transaction and the resulting request submitted OUTSIDE it
     * via {@link #submitPrepared}. Null when the branch refs needed to clone/diff are absent.
     *
     * <p>The origin is {@link ObservationOrigin#MANUAL} on both of these builders and not negotiable: the
     * only caller is the dev trigger, and the submission request's default — LIVE whenever a trigger
     * signal is present — would otherwise fold a hand-picked replay into the population the behavioural
     * trend line is drawn from.
     */
    @Nullable
    PullRequestReviewSubmissionRequest buildReviewRequest(PullRequest pr, @Nullable SignalName triggerSignal) {
        if (pr.getHeadRefOid() == null || pr.getHeadRefName() == null || pr.getBaseRefName() == null) {
            return null;
        }
        log.info("Dev trigger: building review request for PR {} ({})", pr.getId(), pr.getHtmlUrl());
        return new PullRequestReviewSubmissionRequest(
                ScmEventPayload.PullRequestData.from(pr),
                pr.getHeadRefName(),
                pr.getHeadRefOid(),
                pr.getBaseRefName(),
                triggerSignal,
                ObservationOrigin.MANUAL);
    }

    /** Issue-shaped companion to {@link #buildReviewRequest}, with the same session requirement. */
    @Nullable
    IssueReviewSubmissionRequest buildIssueRequest(Issue issue, @Nullable SignalName triggerSignal) {
        if (issue.getRepository() == null) {
            return null;
        }
        log.info("Dev trigger: building issue request for issue {} ({})", issue.getId(), issue.getHtmlUrl());
        return new IssueReviewSubmissionRequest(
                issue.getId(),
                issue.getNumber(),
                issue.getRepository().getId(),
                issue.getRepository().getNameWithOwner(),
                issue.getTitle(),
                issue.getBody() != null ? issue.getBody() : "",
                issue.getState() != null ? issue.getState().name() : "OPEN",
                issue.getHtmlUrl(),
                issue.getUpdatedAt(),
                triggerSignal,
                ObservationOrigin.MANUAL);
    }

    /**
     * Submit a prepared dev request and render the result message. Call only after the build
     * transaction commits.
     */
    public String submitPrepared(
            Long workspaceId, AgentJobType jobType, JobSubmissionRequest request, @Nullable SignalKey signalKey) {
        SubmissionOutcome outcome = submitWithOutcome(workspaceId, jobType, request, signalKey);
        if (outcome.job() != null) {
            return "Job submitted: " + outcome.job().getId();
        }
        return "No job created. " + outcome.requireRefusal().describe();
    }

    /**
     * Submit the workspace's practice-review job for one reviewable artifact, if it has an enabled
     * binding and the purse funding that binding still has room.
     *
     * <p>Runs inside a transaction: the caller's, if one is already open, or one of its own otherwise.
     * A caller with its own open transaction must be prepared to retry the whole unit of work if
     * submission marks it rollback-only — {@link IssueUpdateCoalescer#drain} does, so the whole
     * burst's settlement commits or rolls back together with the one job it admits.
     *
     * @param signalKey the ledger entry this submission answers, already won by the caller. Every
     *     refusal is recorded against it, so a review refused for a liftable reason can happen later
     *     instead of being lost. Null for paths that cannot name a signal yet.
     * @return the created (or existing, deduplicated) job; empty when the workspace has no enabled
     *     practice-review binding, or the cap funding it is reached
     */
    public Optional<AgentJob> submit(
            Long workspaceId, AgentJobType jobType, JobSubmissionRequest request, @Nullable SignalKey signalKey) {
        return submit(workspaceId, jobType, request, signalKey, null);
    }

    public Optional<AgentJob> submit(
            Long workspaceId,
            AgentJobType jobType,
            JobSubmissionRequest request,
            @Nullable SignalKey signalKey,
            @Nullable Detect admission) {
        return Optional.ofNullable(submitWithOutcome(workspaceId, jobType, request, signalKey, admission)
                .job());
    }

    /**
     * {@link #submit} for callers that must explain themselves: the same attempt, keeping the reason
     * it stopped on instead of flattening it into an empty result.
     */
    SubmissionOutcome submitWithOutcome(
            Long workspaceId, AgentJobType jobType, JobSubmissionRequest request, @Nullable SignalKey signalKey) {
        return submitWithOutcome(workspaceId, jobType, request, signalKey, null);
    }

    SubmissionOutcome submitWithOutcome(
            Long workspaceId,
            AgentJobType jobType,
            JobSubmissionRequest request,
            @Nullable SignalKey signalKey,
            @Nullable Detect admission) {
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));

        JobTypeHandler handler = handlerRegistry.getHandler(jobType);
        JobSubmission submission = handler.createSubmission(request);
        if (!memberAiPolicy.permitsReview(workspaceId, jobType, submission.metadata()))
            return refuse(signalKey, SignalStateReason.MEMBER_AI_DECLINED);
        WorkspaceAgentBinding binding = memberAiPolicy
                .binding(workspaceId, jobType, submission.metadata())
                .orElse(null);
        if (binding == null) {
            log.debug("No practice-review binding to run: workspaceId={}", workspaceId);
            return refuse(signalKey, SignalStateReason.REVIEW_MODEL_UNBOUND);
        }

        // THE choke point for all sandboxed LLM work, scoped to whoever pays for THIS binding — which is
        // why the binding is resolved first: an exhausted host budget must not pause work the workspace
        // funds itself, or vice versa. Eventually consistent; uncosted in-flight jobs may overshoot.
        if (llmBudgetService.blockSubmission(workspace, jobType.name(), binding.getFundingSource())) {
            return refuse(signalKey, SignalStateReason.BUDGET_EXHAUSTED);
        }

        return submitForBinding(
                workspace, jobType, artifactKindFor(jobType, request), submission, signalKey, admission);
    }

    /**
     * Hold a refused signal open so the reaper can re-offer it, and answer the caller with the reason
     * it was refused. Wrapped in the transaction template because the callers differ in whether they
     * already have one.
     */
    private SubmissionOutcome refuse(@Nullable SignalKey signalKey, SignalStateReason reason) {
        if (signalKey != null) {
            transactionTemplate.executeWithoutResult(status -> signalRecorder.markRefused(signalKey, reason));
        }
        return SubmissionOutcome.refused(reason);
    }

    /**
     * Submit exactly one practice-review job — never a fan-out. The binding is re-fetched inside the
     * transaction because the discovery read in {@link #submit} runs detached.
     */
    private SubmissionOutcome submitForBinding(
            Workspace workspace,
            AgentJobType jobType,
            ArtifactKind artifactKind,
            JobSubmission submission,
            @Nullable SignalKey signalKey,
            @Nullable Detect admission) {
        String detectionKey = submission.idempotencyKey() + ":detection";

        SubmissionOutcome outcome = transactionTemplate.execute(status -> {
            Workspace currentWorkspace = workspaceRepository
                    .findByIdForUpdate(workspace.getId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Workspace", workspace.getId().toString()));
            if (currentWorkspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE) {
                log.debug(
                        "Skipping agent job submission for inactive workspace: workspaceId={}, status={}",
                        currentWorkspace.getId(),
                        currentWorkspace.getStatus());
                return refuseInTransaction(signalKey, SignalStateReason.WORKSPACE_INACTIVE);
            }
            if (!Boolean.TRUE.equals(currentWorkspace.getFeatures().getPracticesEnabled())) {
                log.debug(
                        "Skipping practice review while the workspace feature is off: workspaceId={}",
                        workspace.getId());
                return refuseInTransaction(signalKey, SignalStateReason.PRACTICES_DISABLED);
            }
            if (admission != null
                    && admission.rolloutRevision()
                            != currentWorkspace.getReviewSettings().getRolloutRevision()) {
                return refuseInTransaction(signalKey, SignalStateReason.STALE_ROLLOUT_REVISION);
            }
            // Resolved, not filtered in SQL: a practice that inherits its autonomy stores NULL, and
            // `autonomy <> 'OFF'` answers UNKNOWN for it, which would refuse review for every
            // workspace that left a practice to inherit.
            if (!hasReviewablePractice(currentWorkspace, artifactKind)) {
                log.debug(
                        "Skipping practice review with no active practice for its work type: workspaceId={}, jobType={}",
                        workspace.getId(),
                        jobType);
                return refuseInTransaction(signalKey, SignalStateReason.NO_ACTIVE_PRACTICE);
            }

            if (!memberAiPolicy.permitsReview(workspace.getId(), jobType, submission.metadata()))
                return refuseInTransaction(signalKey, SignalStateReason.MEMBER_AI_DECLINED);
            WorkspaceAgentBinding binding = memberAiPolicy
                    .binding(workspace.getId(), jobType, submission.metadata())
                    .orElse(null);
            if (binding == null) {
                return refuseInTransaction(signalKey, SignalStateReason.REVIEW_MODEL_UNBOUND);
            }

            // A keyed submission was already deduplicated by the ledger's unique constraint, which
            // outlives the job. This status-scoped fallback only catches a duplicate while a job is
            // still active, so it is reached only by paths that cannot name a signal.
            if (signalKey == null) {
                Optional<AgentJob> existing = agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(
                        workspace.getId(), detectionKey, ACTIVE_STATUSES);
                if (existing.isPresent()) {
                    log.info(
                            "Deduplicated job submission: existingJobId={}, idempotencyKey={}",
                            existing.get().getId(),
                            detectionKey);
                    return SubmissionOutcome.joined(existing.get());
                }
            }

            // Rate limiting, not correctness — a workspace may set it to zero. Keyed on the prefix, so
            // it refuses a re-trigger of the same subject at any revision, not just the one submitted.
            int cooldown = workspace.getReviewSettings().resolveCooldownMinutes(reviewProperties.cooldownMinutes());
            if (cooldown > 0) {
                String rawPrefix = extractCooldownKeyPrefix(submission.idempotencyKey());
                String escaped = rawPrefix.replace("%", "\\%").replace("_", "\\_");
                String cooldownPrefix = escaped + "%:detection";
                Instant cutoff = Instant.now().minus(java.time.Duration.ofMinutes(cooldown));
                Optional<AgentJob> recent =
                        agentJobRepository.findRecentJobByKeyPrefix(workspace.getId(), cooldownPrefix, cutoff);
                if (recent.isPresent()) {
                    log.info(
                            "Cooldown active: skipping submission, recentJobId={}, createdAt={}, cooldownMinutes={}, key={}",
                            recent.get().getId(),
                            recent.get().getCreatedAt(),
                            cooldown,
                            detectionKey);
                    return refuseInTransaction(signalKey, SignalStateReason.COOLDOWN_ACTIVE);
                }
            }

            AgentJob job = new AgentJob();
            job.setWorkspace(currentWorkspace);
            job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
            job.setJobType(jobType);
            job.setArtifactKind(artifactKind);
            job.setPracticeRolloutRevision(currentWorkspace.getReviewSettings().getRolloutRevision());
            job.setPracticeTriggerMode(admission == null ? TriggerMode.MANUAL : admission.triggerMode());
            var metadata = submission.metadata().deepCopy();
            if (signalKey != null) {
                if (!(metadata instanceof ObjectNode objectMetadata)) {
                    throw new IllegalArgumentException("Keyed review metadata must be an object");
                }
                objectMetadata.put(
                        AgentJob.SIGNAL_REVISION_METADATA_KEY,
                        signalKey.revision().value());
            }
            job.setMetadata(metadata);
            job.setIdempotencyKey(detectionKey);
            job.setTraceId(resolveTraceId());
            try {
                job.setConfigSnapshot(
                        ConfigSnapshot.from(binding, llmModelResolver).toJson(objectMapper));
            } catch (IllegalStateException unavailableModel) {
                log.warn(
                        "Skipping practice-review binding whose model is no longer available: workspaceId={}",
                        workspace.getId());
                return refuseInTransaction(signalKey, SignalStateReason.MODEL_UNAVAILABLE);
            }
            var resolvedKind = connectionService.findActiveProviderKind(workspace.getId());
            if (resolvedKind.isPresent()) {
                job.setIntegrationKind(resolvedKind.get());
            } else {
                log.warn(
                        "No active SCM connection for workspace {} — agent job will run but feedback delivery "
                                + "will fail (no integrationKind). Configure a provider connection to enable delivery. jobType={}",
                        workspace.getId(),
                        jobType);
            }

            try {
                agentJobRepository.saveAndFlush(job);
            } catch (DataIntegrityViolationException e) {
                // Partial unique index race: another concurrent submit won. Mark rollback so the broken
                // Hibernate Session is cleaned up — which also unwinds this signal's ledger row, leaving
                // the occurrence free to be recorded again rather than consumed by a job that never was.
                log.info("Idempotency constraint caught concurrent duplicate: key={}", detectionKey);
                status.setRollbackOnly();
                return SubmissionOutcome.refused(SignalStateReason.CONCURRENT_DUPLICATE);
            }

            if (signalKey != null) {
                signalRecorder.markTriggered(signalKey, job.getId());
            }

            return SubmissionOutcome.created(job);
        });
        SubmissionOutcome result = Objects.requireNonNull(outcome, "submission outcome");
        AgentJob job = result.job();
        if (job != null && result.created()) {
            TransactionCallbacks.afterCommit(() -> AgentJobTelemetry.queued(job));
        }
        return result;
    }

    private String resolveTraceId() {
        return Objects.requireNonNullElseGet(
                MDC.get(StructuredLogKeys.TRACE_ID),
                () -> UUID.randomUUID().toString().replace("-", ""));
    }

    private SubmissionOutcome refuseInTransaction(@Nullable SignalKey signalKey, SignalStateReason reason) {
        if (signalKey != null) {
            signalRecorder.markRefused(signalKey, reason);
        }
        return SubmissionOutcome.refused(reason);
    }

    /** Whether any practice of this work type resolves to an autonomy that admits a review. */
    private boolean hasReviewablePractice(Workspace workspace, ArtifactKind artifactKind) {
        PracticeAutonomy workspaceDefault =
                WorkspaceReviewDefaults.of(workspace).defaultAutonomy();
        return practiceRepository.findAutonomyRows(workspace.getId()).stream()
                .filter(row -> artifactKind.equals(row.getArtifactKind()))
                .anyMatch(row -> AutonomyResolver.resolvePractice(
                                row.getPracticeAutonomy(), row.getGroupAutonomy(), workspaceDefault)
                        .autonomy()
                        .admitsReview());
    }

    /**
     * The artifact a job of this type is about. The single mapping for both the job row and the
     * observations filed against it — a second one drifts, and the same artifact ends up with two names.
     */
    public static ArtifactKind artifactKindFor(AgentJobType jobType) {
        return switch (jobType) {
            case PULL_REQUEST_REVIEW -> ArtifactKinds.PULL_REQUEST;
            case ISSUE_REVIEW -> ArtifactKinds.ISSUE;
            case CONVERSATION_REVIEW -> ArtifactKinds.CONVERSATION_THREAD;
            case DOCUMENT_REVIEW -> ArtifactKinds.DOCUMENT;
        };
    }

    private static ArtifactKind artifactKindFor(AgentJobType jobType, JobSubmissionRequest request) {
        return artifactKindFor(jobType);
    }

    /**
     * Strip the trailing freshness segment (commit SHA / updatedAt) from an idempotency key of the form
     * {@code "<type>:{nameWithOwner}:{number}:{phase}:{freshness}"}, preserving the (number, phase) scope
     * so cooldown can LIKE-match any freshness of the same subject.
     */
    static String extractCooldownKeyPrefix(String idempotencyKey) {
        int lastColon = idempotencyKey.lastIndexOf(':');
        if (lastColon > 0) {
            return idempotencyKey.substring(0, lastColon + 1);
        }
        return idempotencyKey;
    }
}
