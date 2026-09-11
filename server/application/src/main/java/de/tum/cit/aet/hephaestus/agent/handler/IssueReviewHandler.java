package de.tum.cit.aet.hephaestus.agent.handler;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireInt;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireText;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmission;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.Task;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelopeWriter;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final WorkspaceContextBuilder workspaceContextBuilder;
    private final TaskEnvelopeWriter taskEnvelopeWriter;
    private final PracticeCatalogInjector practiceCatalogInjector;
    private final PracticeDetectionResultParser resultParser;
    private final FeedbackCompositionResultParser compositionResultParser;
    private final PracticeDetectionDeliveryService deliveryService;
    private final InContextDeliveryGate inContextDeliveryGate;
    private final PullRequestCommentPoster commentPoster;
    private final FeedbackLedgerRecorder feedbackLedgerRecorder;
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;
    private final PracticeFeedbackCommentFormatter commentFormatter;
    private final FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter;
    private final ObservationRepository observationRepository;
    private final PracticeFeedbackDispatchService dispatchService;
    private final FeedbackDeliveryService feedbackDeliveryService;

    IssueReviewHandler(
            JsonMapper objectMapper,
            WorkspaceContextBuilder workspaceContextBuilder,
            TaskEnvelopeWriter taskEnvelopeWriter,
            PracticeCatalogInjector practiceCatalogInjector,
            PracticeDetectionResultParser resultParser,
            FeedbackCompositionResultParser compositionResultParser,
            PracticeDetectionDeliveryService deliveryService,
            InContextDeliveryGate inContextDeliveryGate,
            PullRequestCommentPoster commentPoster,
            FeedbackLedgerRecorder feedbackLedgerRecorder,
            PracticeFeedbackDeliveryPolicy deliveryPolicy,
            PracticeFeedbackCommentFormatter commentFormatter,
            FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter,
            ObservationRepository observationRepository,
            PracticeFeedbackDispatchService dispatchService,
            FeedbackDeliveryService feedbackDeliveryService) {
        this.objectMapper = objectMapper;
        this.workspaceContextBuilder = workspaceContextBuilder;
        this.taskEnvelopeWriter = taskEnvelopeWriter;
        this.practiceCatalogInjector = practiceCatalogInjector;
        this.resultParser = resultParser;
        this.compositionResultParser = compositionResultParser;
        this.deliveryService = deliveryService;
        this.inContextDeliveryGate = inContextDeliveryGate;
        this.commentPoster = commentPoster;
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
                PracticeDetectionDeliveryService.ORIGIN_METADATA_KEY,
                Objects.requireNonNull(r.observationOrigin()).name());
        metadata.put("artifact_kind", ArtifactKinds.ISSUE.value());
        metadata.put("repository_id", r.repositoryId());
        metadata.put("repository_full_name", r.repositoryFullName());
        metadata.put("issue_id", r.issueId());
        metadata.put("issue_number", r.issueNumber());
        metadata.put("title", r.title());
        metadata.put("body", r.body());
        metadata.put("state", r.state());
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
        JsonNode metadata = job.getMetadata();
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new JobPreparationException("Job has no metadata: jobId=" + job.getId());
        }
        SignalName signal = PracticeCatalogInjector.signalOf(job);
        List<Practice> practices = practiceCatalogInjector.resolveEligiblePractices(job, ArtifactKinds.ISSUE);
        PreparedEvidence prepared = workspaceContextBuilder.prepare(
                new ContextRequest.IssueReviewRequest(job), EvidencePlan.compile(practices));
        var artifactSourceManifest = prepared.manifest();
        var readiness = workspaceContextBuilder.prepareAutomatedReviewReadiness(
                prepared.manifest(), practices, job.getId().toString(), job.getCreatedAt(), signal, prepared.files());
        List<Practice> eligible = practices;
        practices = readiness.readyPractices();
        // A practice not put to the model leaves no trace in the delivered review, so a reader cannot
        // distinguish it from one that was assessed and produced no observations. The readiness report
        // records why — evidence we could not read, or a subject that was not in this work — and both the
        // administration surface and the artifact trace read it back from there.
        if (practices.size() < eligible.size()) {
            log.info(
                    "Not asking {} of {} practice(s): jobId={}, skipped={}",
                    eligible.size() - practices.size(),
                    eligible.size(),
                    job.getId(),
                    readiness.report().decisions().stream()
                            .filter(decision -> !decision.ready())
                            .map(decision -> decision.practiceSlug() + decision.reasonCodes())
                            .toList());
        }
        if (practices.isEmpty()) {
            throw new InsufficientEvidenceException(
                    "No practice has sufficient evidence: jobId=" + job.getId(),
                    new PreparedJobInputs(
                            prepared.files(),
                            prepared.filesOnDisk(),
                            prepared.cleanups(),
                            artifactSourceManifest,
                            readiness.report()));
        }
        Map<String, byte[]> files = new LinkedHashMap<>(prepared.files());
        files.put(SandboxLayout.TASK_ENVELOPE_FILENAME, taskEnvelopeWriter.write(buildTaskEnvelope(job, metadata)));
        practiceCatalogInjector.inject(files, job, ArtifactKinds.ISSUE, practices);
        // See PullRequestReviewHandler: a second, separate turn composes this developer's feedback once
        // the measurements are final. An issue has no diff, so the note it may place is artifact-level.
        FeedbackCompositionInputs.stage(
                files,
                PracticeDetectionDeliveryService.originOf(metadata),
                ISSUE_REVIEW_CHANNELS,
                EnumSet.of(FeedbackCompositionInputs.InContextPlacementKind.ARTIFACT));
        log.info(
                "Issue context preparation complete: {} files, issueNumber={}, jobId={}",
                files.size(),
                metadata.path("issue_number").asInt(),
                job.getId());
        return new PreparedJobInputs(
                files, prepared.filesOnDisk(), prepared.cleanups(), artifactSourceManifest, readiness.report());
    }

    private TaskEnvelope buildTaskEnvelope(AgentJob job, JsonNode metadata) {
        if (job.getWorkspace() == null) {
            throw new JobPreparationException("Job has no workspace: jobId=" + job.getId());
        }
        int issueNumber = requireInt(metadata, "issue_number");
        String repoName = requireText(metadata, "repository_full_name");
        Task task = new Task.PracticeReview(buildPrompt(issueNumber, repoName, job), issueNumber, repoName);
        return TaskEnvelope.of(job.getId(), job.getWorkspace().getId(), task);
    }

    private String buildPrompt(int issueNumber, String repoName, AgentJob job) {
        String prompt = "Review issue #" + issueNumber
                + " in "
                + repoName
                + ". This is an ISSUE, not a pull request — there is no code diff. Read the issue context files "
                + "(inputs/context/issue_summary.md, inputs/context/metadata.json, inputs/context/comments.json, and "
                + "inputs/context/project_inventory.json for cross-artifact checks like duplicate/overlapping issues), then "
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
        if (feedbackDeliveryService.recoverAutomaticPackageIfPresent(job)) return;
        ObservationAdmissionService.requireMatchingCompositionDigest(job);
        List<PracticeDetectionResultParser.ValidatedObservation> observations =
                observationRepository
                        .findByAgentJobId(job.getId(), job.getWorkspace().getId())
                        .stream()
                        .map(this::validated)
                        .toList();
        List<PracticeDetectionResultParser.ValidatedObservation> eligible =
                feedbackResponseSuppressionFilter.evaluate(job, observations).deliverable();
        List<PracticeDetectionResultParser.ValidatedObservation> loudEnough =
                inContextDeliveryGate.admitInContext(job, eligible);
        List<PracticeDetectionResultParser.ValidatedObservation> proposals =
                inContextDeliveryGate.awaitingApproval(job, eligible);
        Map<String, String> why = practiceCatalogInjector.whyBySlug(job.getWorkspace(), ArtifactKinds.ISSUE);
        List<ComposedFeedbackUnit> units = compositionResultParser.parse(job.getOutput(), FeedbackChannel.IN_CONTEXT);
        String lead = compositionResultParser.lead(job.getOutput());
        // Everything either surface would compose from: both render an all-clear when no problem
        // survives the gates, so the coverage question is asked once, over the union.
        List<PracticeDetectionResultParser.ValidatedObservation> composable = java.util.stream.Stream.concat(
                        proposals.stream(), loudEnough.stream())
                .toList();
        if (ReviewCoverage.withholdsAllClear(job.getOutput(), composable)) {
            log.info("Withholding an all-clear from a review that did not reach every practice: jobId={}", job.getId());
            return;
        }
        if (!proposals.isEmpty()) {
            Set<String> included = composable.stream()
                    .map(PracticeDetectionResultParser.ValidatedObservation::occurrenceKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<PracticeDetectionResultParser.ValidatedObservation> reviewPackage = observations.stream()
                    .filter(observation -> included.contains(observation.occurrenceKey()))
                    .toList();
            feedbackLedgerRecorder.recordProposal(
                    job,
                    DeliveryComposer.compose(reviewPackage, ArtifactKinds.ISSUE, why, null, units, lead),
                    reviewPackage);
            return;
        }
        var note = DeliveryComposer.compose(loudEnough, ArtifactKinds.ISSUE, why, null, units, lead);
        postIssueNote(
                job,
                note,
                loudEnough.stream()
                        .map(PracticeDetectionResultParser.ValidatedObservation::practiceSlug)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private PracticeDetectionResultParser.ValidatedObservation validated(Observation observation) {
        return new PracticeDetectionResultParser.ValidatedObservation(
                observation.getPractice().getSlug(),
                observation.getSummary(),
                observation.getPresence(),
                observation.getAssessment(),
                observation.getSeverity(),
                observation.getEvidence(),
                observation.getEvidenceRationale(),
                new ObservationKeys(observation.getOccurrenceKey(), observation.getRecurrenceKey()));
    }

    public void admitObservations(AgentJob job, JsonNode observations) {
        ObjectNode output = objectMapper.createObjectNode();
        ObjectNode raw = objectMapper.createObjectNode();
        raw.set("observations", observations);
        output.put("rawOutput", raw.toString());
        var parsed = resultParser.parse(output);
        if (parsed.validObservations().isEmpty()) {
            throw new ObservationsRefusedException(
                    "no_valid_observations", "No valid observations in agent output: jobId=" + job.getId());
        }
        var admitted = new ArrayList<>(PracticeDetectionResultParser.coerceCoherence(
                parsed.validObservations(), practiceCatalogInjector.defectDetectorSlugs(job)));
        deliveryService.deliver(job, admitted);
    }

    @Override
    public ExistingDeliveryLookup findExistingDelivery(AgentJob job) {
        return commentPoster.findExistingSummaryComment(job);
    }

    void postIssueNote(
            AgentJob job,
            PracticeDetectionResultParser.@Nullable DeliveryContent delivery,
            Set<String> contributingPracticeSlugs) {
        if (delivery == null || delivery.mrNote() == null) return;
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
        var providerPackage =
                new PracticeDetectionResultParser.DeliveryContent(formatted, delivery.diffNotes(), delivery.withheld());
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
                "Issue review package dispatch is awaiting reconciliation: jobId=" + job.getId());
    }

    private void recordSuppressed(
            AgentJob job, PracticeDetectionResultParser.DeliveryContent delivery, FeedbackSuppressionReason reason) {
        feedbackLedgerRecorder.recordSuppressedUnit(job, delivery, reason);
    }
}
