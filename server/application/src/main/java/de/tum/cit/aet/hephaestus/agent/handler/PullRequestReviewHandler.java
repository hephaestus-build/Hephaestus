package de.tum.cit.aet.hephaestus.agent.handler;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireInt;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireLong;
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
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Handles {@link de.tum.cit.aet.hephaestus.agent.AgentJobType#PULL_REQUEST_REVIEW} jobs.
 * The workspace layout is defined in {@code docs/contributor/agent/workspace-abi.mdx}.
 */
public class PullRequestReviewHandler implements JobTypeHandler {

    private static final Logger log = LoggerFactory.getLogger(PullRequestReviewHandler.class);

    private final JsonMapper objectMapper;
    private final PracticeReviewPreparation preparation;
    private final ReviewResultParser resultParser;
    private final FeedbackCompositionResultParser compositionResultParser;
    private final ReviewOutputService deliveryService;
    private final FeedbackDeliveryService feedbackService;
    private final FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter;
    private final InContextDeliveryGate inContextDeliveryGate;
    private final ObservationRepository observationRepository;
    private final PublicReviewEligibility publicReviewEligibility;

    PullRequestReviewHandler(
            JsonMapper objectMapper,
            PracticeReviewPreparation preparation,
            ReviewResultParser resultParser,
            FeedbackCompositionResultParser compositionResultParser,
            ReviewOutputService deliveryService,
            FeedbackDeliveryService feedbackService,
            FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter,
            InContextDeliveryGate inContextDeliveryGate,
            ObservationRepository observationRepository,
            PublicReviewEligibility publicReviewEligibility) {
        this.objectMapper = objectMapper;
        this.preparation = preparation;
        this.resultParser = resultParser;
        this.compositionResultParser = compositionResultParser;
        this.deliveryService = deliveryService;
        this.feedbackService = feedbackService;
        this.feedbackResponseSuppressionFilter = feedbackResponseSuppressionFilter;
        this.inContextDeliveryGate = inContextDeliveryGate;
        this.observationRepository = observationRepository;
        this.publicReviewEligibility = publicReviewEligibility;
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
                ReviewOutputService.ORIGIN_METADATA_KEY,
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
        }
        if (submissionRequest.linkedIssueRevision() != null) {
            metadata.put("linked_issue_revision", submissionRequest.linkedIssueRevision());
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
        // An edit is keyed on what was written as well as the head, so two edits at one head are two reviews.
        String freshness = submissionRequest.reviewId() != null
                ? "review-" + submissionRequest.reviewId()
                : ScmSignals.PULL_REQUEST_LINKED_ISSUE_UPDATED.equals(submissionRequest.triggerSignal())
                        ? Objects.requireNonNull(submissionRequest.linkedIssueRevision())
                        : ScmSignals.PULL_REQUEST_EDITED.equals(submissionRequest.triggerSignal())
                                ? ScmSignals.pullRequestRevision(
                                                ScmSignals.PULL_REQUEST_EDITED,
                                                submissionRequest.headRefOid(),
                                                pullRequestData.title(),
                                                pullRequestData.body())
                                        .orElseThrow()
                                        .value()
                                : submissionRequest.headRefOid();
        String idempotencyKey = "pr_review:" + pullRequestData.repository().nameWithOwner()
                + ":"
                + pullRequestData.number()
                + ":"
                + phase
                + ":"
                + freshness;

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
                    FeedbackCompositionInputs.stage(files, ReviewOutputService.originOf(metadata));
                });

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info(
                "Context preparation complete: {} files, {} ms, repoId={}, pullRequestId={}",
                inputs.filesOnDisk().size(),
                elapsedMs,
                repositoryId,
                pullRequestId);
        return inputs;
    }

    private TaskEnvelope buildTaskEnvelope(AgentJob job, JsonNode metadata) {
        if (job.getWorkspace() == null) {
            throw new JobPreparationException("Job has no workspace: jobId=" + job.getId());
        }
        Task task = new Task(
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

    @Override
    public void deliver(AgentJob job) {
        if (ObservationAdmissionService.observationsWereRefused(job)) return;
        ObservationAdmissionService.requireMatchingCompositionDigest(job);
        deliverAdmitted(job);
    }

    private void deliverAdmitted(AgentJob job) {
        List<Observation> persisted = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        List<ReviewResultParser.ValidatedObservation> scopedObservations = persisted.stream()
                .map(observation -> {
                    CitationVerification.requireVerified(job, observation.getEvidence());
                    return validated(observation);
                })
                .toList();
        if (scopedObservations.isEmpty()) throw new JobDeliveryException("Admitted observation set is empty");
        if (feedbackService.recoverAutomaticPackageIfPresent(job)) return;
        Set<UUID> publicIds = publicReviewEligibility.publicObservationIds(job, persisted);
        ComposedReview review = reviewToDeliver(
                compositionResultParser, job, persisted, publicIds, FeedbackCompositionInputs.EVENT_REVIEW_CHANNELS);
        List<ReviewResultParser.ValidatedObservation> eligible = feedbackResponseSuppressionFilter
                .evaluate(job, scopedObservations)
                .deliverable();
        List<ReviewResultParser.ValidatedObservation> proposals = inContextDeliveryGate.awaitingApproval(job, eligible);
        List<ReviewResultParser.ValidatedObservation> loudEnough = inContextDeliveryGate.admitInContext(job, eligible);
        switch (AdmittedDelivery.decide(
                review,
                ArtifactKinds.PULL_REQUEST,
                scopedObservations,
                subjectsOf(persisted, publicIds),
                proposals,
                loudEnough)) {
            case AdmittedDelivery.Proposed proposed -> feedbackService.recordProposal(job, proposed.content());
            case AdmittedDelivery.Automatic automatic ->
                feedbackService.deliverFeedback(job, automatic.content(), automatic.contributingPracticeSlugs());
        }
    }

    /**
     * The review to deliver on the work. A run owes one unless it is a backfill, its staged channels leave the work
     * out, or no observation both decided something and may appear on the work ({@code publicIds}, so a run about a
     * reviewer owes none); these are read from the run itself, never from settings that can change after it. An owed review that is missing, malformed, or written under an
     * earlier fragment contract fails delivery without a claim about the work; a valid empty review is silence.
     */
    static ComposedReview reviewToDeliver(
            FeedbackCompositionResultParser parser,
            AgentJob job,
            List<Observation> persisted,
            Set<UUID> publicIds,
            Set<FeedbackChannel> stagedChannels) {
        // A backfill stages no composition (FeedbackCompositionInputs), so it never owes a review.
        boolean reviewOwed = ReviewOutputService.originOf(job.getMetadata()) != ObservationOrigin.BACKFILL
                && stagedChannels.contains(FeedbackChannel.IN_CONTEXT)
                && persisted.stream()
                        .anyMatch(observation ->
                                observation.getOutcome().isDecided() && publicIds.contains(observation.getId()));
        if (!reviewOwed) return ComposedReview.empty();
        if (!parser.writtenWhole(job.getOutput())) {
            throw new JobDeliveryException(
                    "The review on the work was composed before it was written whole; it is not delivered. jobId="
                            + job.getId());
        }
        ComposedReview review = parser.review(job.getOutput());
        if (review == null) {
            throw new JobDeliveryException(
                    "The review on the work was owed and not written as its contract requires; nothing is delivered. jobId="
                            + job.getId());
        }
        Set<String> decided = review.decidedObservationIds();
        if (persisted.stream()
                .anyMatch(observation -> observation.getOutcome() == Outcome.NOT_MET
                        && publicIds.contains(observation.getId())
                        && !decided.contains(observation.getId().toString()))) {
            throw new JobDeliveryException(
                    "Public feedback composition left an eligible observation undecided; jobId=" + job.getId());
        }
        return review;
    }

    /** The author each publicly eligible observation is about, by its persisted id. */
    static Map<UUID, Long> subjectsOf(List<Observation> persisted, Set<UUID> publicIds) {
        Map<UUID, Long> subjects = new HashMap<>();
        for (Observation observation : persisted) {
            if (publicIds.contains(observation.getId())) {
                subjects.put(observation.getId(), observation.getAboutUserId());
            }
        }
        return subjects;
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

        // Admission decides, against the job's pinned practice revisions, whether an undecided review owed
        // the captured change a reading.
        var admissible = deliveryService.prepare(job, ReviewResultParser.validateCoherence(parsed.validObservations()));
        return admitted -> deliveryService.publish(admitted, admissible);
    }

    @Override
    public boolean reconcilesDeliveryState() {
        return true;
    }
}
