package de.tum.cit.aet.hephaestus.agent.handler;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireInt;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireMetadata;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireText;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedReview;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmission;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedObservations;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.Task;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Handles issue practice reviews and delivers eligible feedback as issue comments. */
public class IssueReviewHandler implements JobTypeHandler {

    private static final Logger log = LoggerFactory.getLogger(IssueReviewHandler.class);

    /** Issues support public task feedback at artifact level, but never a diff placement. */
    static final Set<FeedbackChannel> ISSUE_REVIEW_CHANNELS = Set.copyOf(EnumSet.allOf(FeedbackChannel.class));

    private final JsonMapper objectMapper;
    private final PracticeReviewPreparation preparation;
    private final ReviewResultParser resultParser;
    private final FeedbackCompositionResultParser compositionResultParser;
    private final ReviewOutputService deliveryService;
    private final InContextDeliveryGate inContextDeliveryGate;
    private final FeedbackLedgerRecorder feedbackLedgerRecorder;
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;
    private final PracticeFeedbackCommentFormatter commentFormatter;
    private final FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter;
    private final ObservationRepository observationRepository;
    private final PracticeFeedbackDispatchService dispatchService;
    private final FeedbackDeliveryService feedbackDeliveryService;

    IssueReviewHandler(
            JsonMapper objectMapper,
            PracticeReviewPreparation preparation,
            ReviewResultParser resultParser,
            FeedbackCompositionResultParser compositionResultParser,
            ReviewOutputService deliveryService,
            InContextDeliveryGate inContextDeliveryGate,
            FeedbackLedgerRecorder feedbackLedgerRecorder,
            PracticeFeedbackDeliveryPolicy deliveryPolicy,
            PracticeFeedbackCommentFormatter commentFormatter,
            FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter,
            ObservationRepository observationRepository,
            PracticeFeedbackDispatchService dispatchService,
            FeedbackDeliveryService feedbackDeliveryService) {
        this.objectMapper = objectMapper;
        this.preparation = preparation;
        this.resultParser = resultParser;
        this.compositionResultParser = compositionResultParser;
        this.deliveryService = deliveryService;
        this.inContextDeliveryGate = inContextDeliveryGate;
        this.feedbackLedgerRecorder = feedbackLedgerRecorder;
        this.deliveryPolicy = deliveryPolicy;
        this.commentFormatter = commentFormatter;
        this.feedbackResponseSuppressionFilter = feedbackResponseSuppressionFilter;
        this.observationRepository = observationRepository;
        this.dispatchService = dispatchService;
        this.feedbackDeliveryService = feedbackDeliveryService;
    }

    @Override
    public AgentJobType jobType() {
        return AgentJobType.ISSUE_REVIEW;
    }

    @Override
    public JobSubmission createSubmission(JobSubmissionRequest request) {
        if (!(request instanceof IssueReviewSubmissionRequest r)) {
            throw new IllegalArgumentException("Expected IssueReviewSubmissionRequest, got: "
                    + request.getClass().getSimpleName());
        }
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put(
                ReviewOutputService.ORIGIN_METADATA_KEY,
                Objects.requireNonNull(r.observationOrigin()).name());
        metadata.put("artifact_kind", ArtifactKinds.ISSUE.value());
        metadata.put("repository_id", r.repositoryId());
        metadata.put("repository_full_name", r.repositoryFullName());
        metadata.put("issue_id", r.issueId());
        metadata.put("issue_number", r.issueNumber());
        metadata.put("title", r.title());
        metadata.put("body", r.body());
        metadata.put("state", r.state());
        if (r.actorUserId() != null) {
            metadata.put("actor_user_id", r.actorUserId());
        }
        if (r.reviewSnapshotId() != null) {
            metadata.put("review_snapshot_id", r.reviewSnapshotId().toString());
        }
        if (r.url() != null) {
            metadata.put("issue_url", r.url());
        }
        if (r.triggerSignal() != null) {
            metadata.put(
                    PracticeCatalogInjector.SIGNAL_METADATA_KEY,
                    r.triggerSignal().value());
        }

        String version = r.updatedAt() != null ? String.valueOf(r.updatedAt().toEpochMilli()) : "0";
        String phase = r.triggerSignal() != null ? r.triggerSignal().value() : "manual";
        String idempotencyKey =
                "issue_review:" + r.repositoryFullName() + ":" + r.issueNumber() + ":" + phase + ":" + version;
        return new JobSubmission(metadata, idempotencyKey);
    }

    @Override
    public PreparedJobInputs prepareInputs(AgentJob job) {
        JsonNode metadata = requireMetadata(job);
        PreparedJobInputs inputs = preparation.prepare(
                job,
                ArtifactKinds.ISSUE,
                new ContextRequest.IssueReviewRequest(job),
                () -> buildTaskEnvelope(job, metadata),
                // Compose feedback after observations are final; issues support artifact-level notes only.
                files -> FeedbackCompositionInputs.stage(
                        files,
                        ReviewOutputService.originOf(metadata),
                        ISSUE_REVIEW_CHANNELS,
                        EnumSet.of(FeedbackCompositionInputs.InContextPlacementKind.ARTIFACT)));
        log.info(
                "Issue context preparation complete: {} files, issueNumber={}, jobId={}",
                inputs.filesOnDisk().size(),
                metadata.path("issue_number").asInt(),
                job.getId());
        return inputs;
    }

    private TaskEnvelope buildTaskEnvelope(AgentJob job, JsonNode metadata) {
        if (job.getWorkspace() == null) {
            throw new JobPreparationException("Job has no workspace: jobId=" + job.getId());
        }
        int issueNumber = requireInt(metadata, "issue_number");
        String repoName = requireText(metadata, "repository_full_name");
        Task task = new Task(buildPrompt(issueNumber, repoName, job), issueNumber, repoName);
        return TaskEnvelope.of(job.getId(), job.getWorkspace().getId(), task);
    }

    private String buildPrompt(int issueNumber, String repoName, AgentJob job) {
        String prompt = "Review issue #" + issueNumber
                + " in "
                + repoName
                + ". This is an ISSUE, not a pull request — there is no code diff. Read the issue context files "
                + "("
                + SandboxLayout.CONTEXT_PREFIX
                + "metadata.json, "
                + SandboxLayout.CONTEXT_PREFIX
                + "comments.json, and "
                + SandboxLayout.CONTEXT_PREFIX
                + "project_inventory.json for cross-artifact checks like duplicate/overlapping issues), then "
                + "evaluate each practice in inputs/practices/ against the issue and persist every justified observation via the "
                + "report_observation tool. Evidence citations should reference the issue thread/metadata, not source files. "
                + "Follow "
                + SandboxLayout.ORCHESTRATOR_PATH
                + " for the observation schema and rules.";
        log.info("Built issue orchestrator prompt: {} chars, jobId={}", prompt.length(), job.getId());
        return prompt;
    }

    @Override
    public void deliver(AgentJob job) {
        if (ObservationAdmissionService.observationsWereRefused(job)) return;
        ObservationAdmissionService.requireMatchingCompositionDigest(job);
        List<Observation> persisted = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        List<ReviewResultParser.ValidatedObservation> observations = persisted.stream()
                .map(observation -> {
                    CitationVerification.requireVerified(job, observation.getEvidence());
                    return validated(observation);
                })
                .toList();
        if (feedbackDeliveryService.recoverAutomaticPackageIfPresent(job)) return;
        ComposedReview review = PullRequestReviewHandler.reviewToDeliver(
                compositionResultParser, job, persisted, ISSUE_REVIEW_CHANNELS);
        List<ReviewResultParser.ValidatedObservation> eligible =
                feedbackResponseSuppressionFilter.evaluate(job, observations).deliverable();
        List<ReviewResultParser.ValidatedObservation> loudEnough = inContextDeliveryGate.admitInContext(job, eligible);
        List<ReviewResultParser.ValidatedObservation> proposals = inContextDeliveryGate.awaitingApproval(job, eligible);
        switch (AdmittedDelivery.decide(
                review,
                ArtifactKinds.ISSUE,
                observations,
                PullRequestReviewHandler.subjectsOf(persisted),
                proposals,
                loudEnough)) {
            case AdmittedDelivery.Proposed proposed -> feedbackLedgerRecorder.recordProposal(job, proposed.content());
            case AdmittedDelivery.Automatic automatic ->
                postIssueNote(job, automatic.content(), automatic.contributingPracticeSlugs());
        }
    }

    private ReviewResultParser.ValidatedObservation validated(Observation observation) {
        return new ReviewResultParser.ValidatedObservation(
                observation.getPractice().getSlug(),
                observation.getSummary(),
                observation.getOutcome(),
                observation.getSeverity(),
                observation.getEvidence(),
                observation.getEvidenceRationale(),
                new ObservationKeys(
                        observation.getOccurrenceKey(), observation.getRecurrenceKey(), observation.getId()),
                observation.getPracticeRevision() == null
                        ? observation.getPractice().getDeliveryBehavior()
                        : observation.getPracticeRevision().getDeliveryBehavior());
    }

    @Override
    public PreparedObservations prepareObservations(AgentJob job, JsonNode observations) {
        var parsed = resultParser.parseObservations(observations);
        if (parsed.validObservations().isEmpty()) {
            throw new ObservationsRefusedException(
                    "no_valid_observations", "No valid observations in agent output: jobId=" + job.getId());
        }
        var admissible = deliveryService.prepare(job, ReviewResultParser.validateCoherence(parsed.validObservations()));
        return admitted -> deliveryService.publish(admitted, admissible);
    }

    @Override
    public boolean reconcilesDeliveryState() {
        return true;
    }

    void postIssueNote(
            AgentJob job,
            ReviewResultParser.@Nullable DeliveryContent delivery,
            Set<String> contributingPracticeSlugs) {
        if (delivery == null || delivery.mrNote() == null) {
            feedbackLedgerRecorder.recordNothingToPost(job, delivery);
            return;
        }
        PracticeFeedbackDeliveryPolicy.Decision<Issue> decision =
                deliveryPolicy.evaluateIssue(job, DeliveryPolicyStage.AUTOMATIC, null, contributingPracticeSlugs);
        if (!decision.allowed()) {
            if (decision.suppressionReason() != null) recordSuppressed(job, delivery, decision.suppressionReason());
            return;
        }
        String sanitized = PullRequestCommentPoster.sanitize(delivery.mrNote());
        if (sanitized.isBlank()) {
            recordSuppressed(job, delivery, FeedbackSuppressionReason.EMPTY_AFTER_SANITIZE);
            return;
        }
        String formatted = commentFormatter.format(sanitized, job);
        var providerPackage = new ReviewResultParser.DeliveryContent(
                formatted, delivery.diffNotes(), delivery.withheld(), delivery.summaryContributors());
        PracticeFeedbackDispatchService.Result result =
                dispatchService.dispatchAutomaticPackage(job, providerPackage, contributingPracticeSlugs);
        var dispatch = dispatchService.automaticPackage(job);
        if (dispatch.getState() == FeedbackDispatchState.SENT
                || dispatch.getState() == FeedbackDispatchState.SUPPRESSED
                || dispatch.getState() == FeedbackDispatchState.FAILED) {
            feedbackDeliveryService.projectAutomaticPackage(job, dispatch);
        }
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SENT) {
            job.setDeliveryCommentId(result.externalRef());
            return;
        }
        if (result.status() == PracticeFeedbackDispatchService.Result.Status.SUPPRESSED) return;
        throw new JobDeliveryException(
                "The dispatch of the issue review package waits for reconciliation. jobId=" + job.getId());
    }

    private void recordSuppressed(
            AgentJob job, ReviewResultParser.DeliveryContent delivery, FeedbackSuppressionReason reason) {
        feedbackLedgerRecorder.recordSuppressedUnit(job, delivery, reason);
    }
}
