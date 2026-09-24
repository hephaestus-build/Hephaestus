package de.tum.cit.aet.hephaestus.agent.handler;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireInt;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireLong;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireMetadata;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireText;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
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
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedObservations;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.Task;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Handles {@link AgentJobType#PULL_REQUEST_REVIEW} jobs.
 * The workspace layout is defined in {@code docs/contributor/agent/workspace-abi.mdx}.
 */
public class PullRequestReviewHandler implements JobTypeHandler {

    private static final Logger log = LoggerFactory.getLogger(PullRequestReviewHandler.class);

    private final JsonMapper objectMapper;
    private final PracticeCatalogInjector practiceCatalogInjector;
    private final PracticeReviewPreparation preparation;
    private final PracticeDetectionResultParser resultParser;
    private final FeedbackCompositionResultParser compositionResultParser;
    private final PracticeDetectionDeliveryService deliveryService;
    private final FeedbackDeliveryService feedbackService;
    private final FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter;
    private final InContextDeliveryGate inContextDeliveryGate;
    private final ObservationRepository observationRepository;
    private final RecurringLapses recurringLapses;

    PullRequestReviewHandler(
            JsonMapper objectMapper,
            PracticeCatalogInjector practiceCatalogInjector,
            PracticeReviewPreparation preparation,
            PracticeDetectionResultParser resultParser,
            FeedbackCompositionResultParser compositionResultParser,
            PracticeDetectionDeliveryService deliveryService,
            FeedbackDeliveryService feedbackService,
            FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter,
            InContextDeliveryGate inContextDeliveryGate,
            ObservationRepository observationRepository,
            RecurringLapses recurringLapses) {
        this.objectMapper = objectMapper;
        this.practiceCatalogInjector = practiceCatalogInjector;
        this.preparation = preparation;
        this.resultParser = resultParser;
        this.compositionResultParser = compositionResultParser;
        this.deliveryService = deliveryService;
        this.feedbackService = feedbackService;
        this.feedbackResponseSuppressionFilter = feedbackResponseSuppressionFilter;
        this.inContextDeliveryGate = inContextDeliveryGate;
        this.observationRepository = observationRepository;
        this.recurringLapses = recurringLapses;
    }

    @Override
    public AgentJobType jobType() {
        return AgentJobType.PULL_REQUEST_REVIEW;
    }

    @Override
    public JobSubmission createSubmission(JobSubmissionRequest request) {
        if (!(request instanceof PullRequestReviewSubmissionRequest submissionRequest)) {
            throw new IllegalArgumentException("Expected PullRequestReviewSubmissionRequest, got: "
                    + request.getClass().getSimpleName());
        }

        ScmEventPayload.PullRequestData pullRequestData = submissionRequest.pullRequest();

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put(
                PracticeDetectionDeliveryService.ORIGIN_METADATA_KEY,
                Objects.requireNonNull(submissionRequest.observationOrigin()).name());
        metadata.put("repository_id", pullRequestData.repository().id());
        metadata.put("repository_full_name", pullRequestData.repository().nameWithOwner());
        metadata.put("pull_request_id", pullRequestData.id());
        metadata.put("pr_number", pullRequestData.number());
        metadata.put("pr_url", pullRequestData.htmlUrl());
        metadata.put("commit_sha", submissionRequest.headRefOid());
        if (submissionRequest.baseRefOid() != null) {
            metadata.put("base_ref_oid", submissionRequest.baseRefOid());
        }
        metadata.put("source_branch", submissionRequest.headRefName());
        metadata.put("target_branch", submissionRequest.baseRefName());
        metadata.put("title", pullRequestData.title());
        metadata.put("body", pullRequestData.body());
        // Who this author-run is about and who merged: a MERGER practice is reviewed only when they are
        // the same person (PracticeCatalogInjector.attributable), so the two ids travel with the job.
        if (pullRequestData.authorId() != null) {
            metadata.put(PracticeCatalogInjector.AUTHOR_ID_METADATA_KEY, pullRequestData.authorId());
        }
        if (pullRequestData.mergedById() != null) {
            metadata.put(PracticeCatalogInjector.MERGED_BY_ID_METADATA_KEY, pullRequestData.mergedById());
        }
        // When present, the catalog injector materialises ONLY the practices bound to this signal, so an
        // authoring practice is not re-litigated on a fixup push. Null = run the full focus set.
        if (submissionRequest.triggerSignal() != null) {
            metadata.put(
                    PracticeCatalogInjector.SIGNAL_METADATA_KEY,
                    submissionRequest.triggerSignal().value());
            // Use the signal-time draft state for both gate and catalog selection.
            metadata.put(PracticeCatalogInjector.DRAFT_METADATA_KEY, pullRequestData.isDraft());
        }
        if (submissionRequest.reviewId() != null && submissionRequest.aboutUserId() != null) {
            metadata.put("review_id", submissionRequest.reviewId());
            metadata.put("about_user_id", submissionRequest.aboutUserId());
            metadata.put("subject_role", "REVIEWER");
        }

        // The occasion is part of the key: an authoring review, a push re-scan, a reviewer pass and a
        // retrospective of the SAME head SHA are DIFFERENT reviews over different practice sets, so a
        // retrospective must never be deduped against an earlier authoring job for the same commit. It
        // sits BEFORE the SHA so extractCooldownKeyPrefix scopes cooldown per (pr, occasion).
        String phase = submissionRequest.triggerSignal() != null
                ? submissionRequest.triggerSignal().value()
                : "manual";
        String idempotencyKey = "pr_review:" + pullRequestData.repository().nameWithOwner()
                + ":"
                + pullRequestData.number()
                + ":"
                + phase
                + ":"
                + (submissionRequest.reviewId() != null
                        ? "review-" + submissionRequest.reviewId()
                        : submissionRequest.headRefOid());

        return new JobSubmission(metadata, idempotencyKey);
    }

    @Override
    public PreparedJobInputs prepareInputs(AgentJob job) {
        long startNanos = System.nanoTime();
        JsonNode metadata = requireMetadata(job);
        long repositoryId = requireLong(metadata, "repository_id");
        long pullRequestId = requireLong(metadata, "pull_request_id");

        PreparedJobInputs inputs = preparation.prepare(
                job,
                ArtifactKinds.PULL_REQUEST,
                new ContextRequest.PracticeReviewRequest(job),
                () -> buildTaskEnvelope(job, metadata),
                files -> {
                    // Compose feedback after observations are final. Backfills omit composition.
                    FeedbackCompositionInputs.stage(files, PracticeDetectionDeliveryService.originOf(metadata));
                });

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info(
                "Context preparation complete: {} files, {} ms, repoId={}, pullRequestId={}",
                inputs.files().size(),
                elapsedMs,
                repositoryId,
                pullRequestId);
        return inputs;
    }

    private TaskEnvelope buildTaskEnvelope(AgentJob job, JsonNode metadata) {
        if (job.getWorkspace() == null) {
            throw new JobPreparationException("Job has no workspace: jobId=" + job.getId());
        }
        Task task = new Task.PracticeReview(
                buildPrompt(job), requireInt(metadata, "pr_number"), requireText(metadata, "repository_full_name"));
        return TaskEnvelope.of(job.getId(), job.getWorkspace().getId(), task);
    }

    private String buildPrompt(AgentJob job) {
        JsonNode metadata = requireMetadata(job);
        int pullRequestNumber = requireInt(metadata, "pr_number");
        String repoName = requireText(metadata, "repository_full_name");

        String prompt = "Review merge request #" + pullRequestNumber
                + " in "
                + repoName
                + ". Read the context files, then persist every justified observation via the report_observation tool. "
                + "Follow "
                + SandboxLayout.ORCHESTRATOR_PATH
                + " for the schema and rules.";
        log.info("Built orchestrator prompt: {} chars, jobId={}", prompt.length(), job.getId());
        return prompt;
    }

    // Delivery

    @Override
    public void deliver(AgentJob job) {
        if (ObservationAdmissionService.observationsWereRefused(job)) return;
        ObservationAdmissionService.requireMatchingCompositionDigest(job);
        deliverAdmitted(job);
    }

    private void deliverAdmitted(AgentJob job) {
        List<Observation> persisted = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        List<PracticeDetectionResultParser.ValidatedObservation> scopedObservations = persisted.stream()
                .map(observation -> {
                    CitationVerification.requireVerified(job, observation.getEvidence());
                    return validated(observation);
                })
                .toList();
        if (scopedObservations.isEmpty()) throw new JobDeliveryException("Admitted observation set is empty");
        Set<String> recurring = recurringLapses.recurringSlugs(persisted);
        if (feedbackService.recoverAutomaticPackageIfPresent(job)) return;
        List<PracticeDetectionResultParser.ValidatedObservation> eligible = feedbackResponseSuppressionFilter
                .evaluate(job, scopedObservations)
                .deliverable();
        List<PracticeDetectionResultParser.ValidatedObservation> proposals =
                inContextDeliveryGate.awaitingApproval(job, eligible);
        List<PracticeDetectionResultParser.ValidatedObservation> loudEnough =
                inContextDeliveryGate.admitInContext(job, eligible);
        List<PracticeDetectionResultParser.ValidatedObservation> deliverable = loudEnough;
        List<ComposedFeedbackUnit> units = compositionResultParser.parse(job.getOutput(), FeedbackChannel.IN_CONTEXT);
        String lead = compositionResultParser.lead(job.getOutput());
        Map<String, String> why = practiceCatalogInjector.whyBySlug(job.getWorkspace(), ArtifactKinds.PULL_REQUEST);
        // Everything either surface would compose from: both render an all-clear when no problem
        // survives the gates, so the coverage question is asked once, over the union.
        List<PracticeDetectionResultParser.ValidatedObservation> composable = java.util.stream.Stream.concat(
                        proposals.stream(), deliverable.stream())
                .toList();
        if (ReviewCoverage.withholdsAllClear(job.getOutput(), composable)) {
            log.info("Withholding an all-clear from a review that did not reach every practice: jobId={}", job.getId());
            return;
        }
        if (!proposals.isEmpty()) {
            Set<String> included = composable.stream()
                    .map(PracticeDetectionResultParser.ValidatedObservation::occurrenceKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<PracticeDetectionResultParser.ValidatedObservation> reviewPackage = scopedObservations.stream()
                    .filter(observation -> included.contains(observation.occurrenceKey()))
                    .toList();
            feedbackService.recordProposal(
                    job,
                    DeliveryComposer.composeAdmitted(
                            reviewPackage, ArtifactKinds.PULL_REQUEST, why, units, lead, recurring),
                    reviewPackage);
            return;
        }
        var content =
                DeliveryComposer.composeAdmitted(deliverable, ArtifactKinds.PULL_REQUEST, why, units, lead, recurring);
        Set<String> contributingPracticeSlugs = deliverable.stream()
                .map(PracticeDetectionResultParser.ValidatedObservation::practiceSlug)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        feedbackService.deliverFeedback(job, content, contributingPracticeSlugs);
    }

    private PracticeDetectionResultParser.ValidatedObservation validated(Observation observation) {
        return new PracticeDetectionResultParser.ValidatedObservation(
                observation.getPractice().getSlug(),
                observation.getSummary(),
                observation.getAssessmentStatus(),
                observation.getPresence(),
                observation.getAssessment(),
                observation.getSeverity(),
                observation.getEvidence(),
                observation.getEvidenceRationale(),
                new ObservationKeys(observation.getOccurrenceKey(), observation.getRecurrenceKey()),
                observation.getPracticeRevision() == null
                        ? observation.getPractice().getDeliveryBehavior()
                        : observation.getPracticeRevision().getDeliveryBehavior());
    }

    @Override
    public PreparedObservations prepareObservations(AgentJob job, JsonNode observations) {
        var parsed = resultParser.parseObservations(observations);
        if (!parsed.discarded().isEmpty()) {
            log.info(
                    "Discarded {} observations during parsing: jobId={}, reasons={}",
                    parsed.discarded().size(),
                    job.getId(),
                    parsed.discarded());
        }
        if (parsed.validObservations().isEmpty()) {
            throw new ObservationsRefusedException(
                    "no_valid_observations",
                    "No valid observations in agent output: jobId=" + job.getId()
                            + ", discarded="
                            + parsed.discarded().size());
        }

        CapturedEvidence captured = CapturedEvidence.of(job, objectMapper);
        // Refuse an entirely unassessed review only when it also reports no use of the captured diff.
        boolean nothingDecided =
                parsed.validObservations().stream().noneMatch(f -> (f.assessmentStatus() == AssessmentStatus.ASSESSED));
        boolean changeCaptured = captured.availableSources().contains(PracticeSubjectClause.DIFF_SOURCE);
        if (nothingDecided && changeCaptured && !readTheDiff(parsed.validObservations())) {
            throw new ObservationsRefusedException(
                    "did_not_read_the_diff",
                    "No observation decided anything or quoted the change, and a change was captured — the"
                            + " review answered without reading it. Refusing to deliver. jobId="
                            + job.getId());
        }
        List<PracticeDetectionResultParser.ValidatedObservation> scopedObservations = parsed.validObservations();

        var admissible =
                deliveryService.prepare(job, PracticeDetectionResultParser.validateCoherence(scopedObservations));
        return admitted -> deliveryService.publish(admitted, admissible);
    }

    @Override
    public ExistingDeliveryLookup findExistingDelivery(AgentJob job) {
        return feedbackService.findExistingSummary(job);
    }

    @Override
    public boolean reconcilesMoreThanOneProviderObject() {
        return true;
    }

    /**
     * Whether an observation cites the diff or names it in a consulted-source warrant.
     * This prevents a refusal; it does not replace citation verification at admission.
     */
    static boolean readTheDiff(List<PracticeDetectionResultParser.ValidatedObservation> observations) {
        for (var observation : observations) {
            JsonNode evidence = observation.evidence();
            if (evidence == null) {
                continue;
            }
            for (JsonNode citation : evidence.path("citations")) {
                if (PracticeSubjectClause.DIFF_SOURCE
                        .value()
                        .equals(citation.path("sourceKind").asString())) {
                    return true;
                }
            }
            // Metadata-only practices can report consulting the diff without quoting it.
            for (String warrant : List.of("search", "inapplicability", "undecidability")) {
                for (JsonNode consulted : evidence.path(warrant).path("consulted")) {
                    if (PracticeSubjectClause.DIFF_SOURCE.value().equals(consulted.asString())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
